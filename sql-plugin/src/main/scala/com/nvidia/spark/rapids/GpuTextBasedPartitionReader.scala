/*
 * Copyright (c) 2022, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids

import java.time.DateTimeException

import scala.collection.mutable.ListBuffer
import scala.math.max

import ai.rapids.cudf.{ColumnVector, DType, HostMemoryBuffer, NvtxColor, NvtxRange, Scalar, Schema, Table}
import com.nvidia.spark.rapids.DateUtils.{toStrf, TimestampFormatConversionException}
import com.nvidia.spark.rapids.shims.GpuTypeShims
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodecFactory

import org.apache.spark.TaskContext
import org.apache.spark.sql.connector.read.PartitionReader
import org.apache.spark.sql.execution.QueryExecutionException
import org.apache.spark.sql.execution.datasources.{HadoopFileLinesReader, PartitionedFile}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.rapids.{ExceptionTimeParserPolicy, GpuToTimestamp, LegacyTimeParserPolicy}
import org.apache.spark.sql.types.{DataTypes, DecimalType, StructField, StructType}
import org.apache.spark.sql.vectorized.ColumnarBatch

/**
 * The text based PartitionReader
 * @param conf the Hadoop configuration
 * @param partFile file split to read
 * @param dataSchema schema of the data
 * @param readDataSchema the Spark schema describing what will be read
 * @param lineSeparatorInRead An optional byte line sep.
 * @param maxRowsPerChunk maximum number of rows to read in a batch
 * @param maxBytesPerChunk maximum number of bytes to read in a batch
 * @param execMetrics metrics to update during read
 */
abstract class GpuTextBasedPartitionReader(
    conf: Configuration,
    partFile: PartitionedFile,
    dataSchema: StructType,
    readDataSchema: StructType,
    lineSeparatorInRead: Option[Array[Byte]],
    maxRowsPerChunk: Integer,
    maxBytesPerChunk: Long,
    execMetrics: Map[String, GpuMetric])
  extends PartitionReader[ColumnarBatch] with ScanWithMetrics with Arm {
  import GpuMetric._

  private var batch: Option[ColumnarBatch] = None
  private val lineReader = new HadoopFileLinesReader(partFile, lineSeparatorInRead, conf)
  private var isFirstChunkForIterator: Boolean = true
  private var isExhausted: Boolean = false
  private var maxDeviceMemory: Long = 0

  metrics = execMetrics

  private lazy val estimatedHostBufferSize: Long = {
    val rawPath = new Path(partFile.filePath)
    val fs = rawPath.getFileSystem(conf)
    val path = fs.makeQualified(rawPath)
    val fileSize = fs.getFileStatus(path).getLen
    val codecFactory = new CompressionCodecFactory(conf)
    val codec = codecFactory.getCodec(path)
    if (codec != null) {
      // wild guess that compression is 2X or less
      partFile.length * 2
    } else if (partFile.start + partFile.length == fileSize) {
      // last split doesn't need to read an additional record
      partFile.length
    } else {
      // wild guess for extra space needed for the record after the split end offset
      partFile.length + 128 * 1024
    }
  }

  /**
   * Grows a host buffer, returning a new buffer and closing the original
   * after copying the data into the new buffer.
   * @param original the original host memory buffer
   */
  private def growHostBuffer(original: HostMemoryBuffer, needed: Long): HostMemoryBuffer = {
    val newSize = Math.max(original.getLength * 2, needed)
    closeOnExcept(HostMemoryBuffer.allocate(newSize)) { result =>
      result.copyFromHostBuffer(0, original, 0, original.getLength)
      original.close()
      result
    }
  }

  private def readPartFile(): (HostMemoryBuffer, Long) = {
    withResource(new NvtxRange("Buffer file split", NvtxColor.YELLOW)) { _ =>
      isFirstChunkForIterator = false
      val separator = lineSeparatorInRead.getOrElse(Array('\n'.toByte))
      var succeeded = false
      var totalSize: Long = 0L
      var totalRows: Integer = 0
      var hmb = HostMemoryBuffer.allocate(estimatedHostBufferSize)
      try {
        while (lineReader.hasNext
          && totalRows != maxRowsPerChunk
          && totalSize <= maxBytesPerChunk /* soft limit and returns at least one row */) {
          val line = lineReader.next()
          val lineSize = line.getLength
          val newTotal = totalSize + lineSize + separator.length
          if (newTotal > hmb.getLength) {
            hmb = growHostBuffer(hmb, newTotal)
          }
          // Can have an empty line, do not write this to buffer but add the separator
          // and totalRows
          if (lineSize != 0) {
            hmb.setBytes(totalSize, line.getBytes, 0, lineSize)
          }
          hmb.setBytes(totalSize + lineSize, separator, 0, separator.length)
          totalRows += 1
          totalSize = newTotal
        }
        //Indicate this is the last chunk
        isExhausted = !lineReader.hasNext
        succeeded = true
      } finally {
        if (!succeeded) {
          hmb.close()
        }
      }
      (hmb, totalSize)
    }
  }

  private def readBatch(): Option[ColumnarBatch] = {
    withResource(new NvtxRange(getFileFormatShortName + " readBatch", NvtxColor.GREEN)) { _ =>
      val isFirstChunk = partFile.start == 0 && isFirstChunkForIterator
      val table = readToTable(isFirstChunk)
      try {
        if (readDataSchema.isEmpty) {
          table.map(t => new ColumnarBatch(Array.empty, t.getRowCount.toInt))
        } else {
          table.map(GpuColumnVector.from(_, readDataSchema.toArray.map(_.dataType)))
        }
      } finally {
        metrics(NUM_OUTPUT_BATCHES) += 1
        table.foreach(_.close())
      }
    }
  }

  private def readToTable(isFirstChunk: Boolean): Option[Table] = {
    val (dataBuffer, dataSize) = metrics(BUFFER_TIME).ns {
      readPartFile()
    }
    try {
      if (dataSize == 0) {
        None
      } else {
        val newReadDataSchema: StructType = if (readDataSchema.isEmpty) {
          val smallestField =
            dataSchema.min(Ordering.by[StructField, Integer](_.dataType.defaultSize))
          StructType(Seq(smallestField))
        } else {
          readDataSchema
        }

        // read boolean and numeric columns as strings in cuDF
        val dataSchemaWithStrings = StructType(dataSchema.fields
          .map(f => {
            f.dataType match {
              case DataTypes.BooleanType | DataTypes.ByteType | DataTypes.ShortType |
                   DataTypes.IntegerType | DataTypes.LongType | DataTypes.FloatType |
                   DataTypes.DoubleType | _: DecimalType | DataTypes.DateType |
                   DataTypes.TimestampType =>
                f.copy(dataType = DataTypes.StringType)
              case other if GpuTypeShims.supportCsvRead(other) =>
                f.copy(dataType = DataTypes.StringType)
              case _ =>
                f
            }
          }))
        val cudfSchema = GpuColumnVector.from(dataSchemaWithStrings)

        // about to start using the GPU
        GpuSemaphore.acquireIfNecessary(TaskContext.get(), metrics(SEMAPHORE_WAIT_TIME))

        // The buffer that is sent down
        val table = withResource(new NvtxWithMetrics(getFileFormatShortName + " decode",
          NvtxColor.DARK_GREEN, metrics(GPU_DECODE_TIME))) { _ =>
          readToTable(dataBuffer, dataSize, cudfSchema, newReadDataSchema, isFirstChunk)
        }
        maxDeviceMemory = max(GpuColumnVector.getTotalDeviceMemoryUsed(table), maxDeviceMemory)

        // parse boolean and numeric columns that were read as strings
        val castTable = withResource(table) { _ =>
          val columns = new ListBuffer[ColumnVector]()
          // Table increases the ref counts on the columns so we have
          // to close them after creating the table
          withResource(columns) { _ =>
            for (i <- 0 until table.getNumberOfColumns) {
              val castColumn = newReadDataSchema.fields(i).dataType match {
                case DataTypes.BooleanType =>
                  castStringToBool(table.getColumn(i))
                case DataTypes.ByteType =>
                  castStringToInt(table.getColumn(i), DType.INT8)
                case DataTypes.ShortType =>
                  castStringToInt(table.getColumn(i), DType.INT16)
                case DataTypes.IntegerType =>
                  castStringToInt(table.getColumn(i), DType.INT32)
                case DataTypes.LongType =>
                  castStringToInt(table.getColumn(i), DType.INT64)
                case DataTypes.FloatType =>
                  castStringToFloat(table.getColumn(i), DType.FLOAT32)
                case DataTypes.DoubleType =>
                  castStringToFloat(table.getColumn(i), DType.FLOAT64)
                case dt: DecimalType =>
                  castStringToDecimal(table.getColumn(i), dt)
                case DataTypes.DateType =>
                  castStringToDate(table.getColumn(i), DType.TIMESTAMP_DAYS, failOnInvalid = true)
                case DataTypes.TimestampType =>
                  castStringToTimestamp(table.getColumn(i), timestampFormat,
                    DType.TIMESTAMP_MICROSECONDS)
                case other if GpuTypeShims.supportCsvRead(other) =>
                  GpuTypeShims.csvRead(table.getColumn(i), other)
                case _ =>
                  table.getColumn(i).incRefCount()
              }
              columns += castColumn
            }
            new Table(columns: _*)
          }
        }

        handleResult(newReadDataSchema, castTable)
      }
    } finally {
      dataBuffer.close()
    }
  }

  def dateFormat: String
  def timestampFormat: String

  def castStringToDate(input: ColumnVector, dt: DType, failOnInvalid: Boolean): ColumnVector = {
    val cudfFormat = DateUtils.toStrf(dateFormat, parseString = true)
    withResource(input.strip()) { stripped =>
      withResource(stripped.isTimestamp(cudfFormat)) { isDate =>
        if (failOnInvalid && GpuOverrides.getTimeParserPolicy == ExceptionTimeParserPolicy) {
          withResource(isDate.all()) { all =>
            if (all.isValid && !all.getBoolean) {
              throw new DateTimeException("One or more values is not a valid date")
            }
          }
        }
        withResource(stripped.asTimestamp(dt, cudfFormat)) { asDate =>
          withResource(Scalar.fromNull(dt)) { nullScalar =>
            isDate.ifElse(asDate, nullScalar)
          }
        }
      }
    }
  }

  def castStringToTimestamp(
      lhs: ColumnVector,
      sparkFormat: String,
      dtype: DType): ColumnVector = {

    val optionalSeconds = raw"(?:\:\d{2})?"
    val optionalMicros = raw"(?:\.\d{1,6})?"
    val twoDigits = raw"\d{2}"
    val fourDigits = raw"\d{4}"

    val regexRoot = sparkFormat
      .replace("'T'", "T")
      .replace("yyyy", fourDigits)
      .replace("MM", twoDigits)
      .replace("dd", twoDigits)
      .replace("HH", twoDigits)
      .replace("mm", twoDigits)
      .replace("[:ss]", optionalSeconds)
      .replace(":ss", optionalSeconds) // Spark always treats seconds portion as optional
      .replace("[.SSSXXX]", optionalMicros)
      .replace("[.SSS][XXX]", optionalMicros)
      .replace("[.SSS]", optionalMicros)
      .replace("[.SSSSSS]", optionalMicros)
      .replace(".SSSXXX", optionalMicros)
      .replace(".SSSSSS", optionalMicros)
      .replace(".SSS", optionalMicros)

    // Spark treats timestamp portion as optional always
    val regexOptionalTime = regexRoot.split('T') match {
      case Array(d, t) =>
        d + "(?:[ T]" + t + ")?"
      case _ =>
        regexRoot
    }
    val regex = regexOptionalTime + raw"Z?\Z"

    // get a list of all possible cuDF formats that we need to check for
    val cudfFormats = GpuTextBasedDateUtils.toCudfFormats(sparkFormat, parseString = true)


    // filter by regexp first to eliminate invalid entries
    val regexpFiltered = withResource(lhs.strip()) { stripped =>
      withResource(stripped.matchesRe(regex)) { matchesRe =>
        withResource(Scalar.fromNull(DType.STRING)) { nullString =>
          matchesRe.ifElse(stripped, nullString)
        }
      }
    }

    // fix timestamps that have milliseconds but no microseconds
    // example ".296" => ".296000"
    val sanitized = withResource(regexpFiltered) { _ =>
      // cannot replace with back-refs directly because cuDF cannot support "\1000\2" so we
      // first substitute with a placeholder and then replace that. The placeholder value
      // `@` was chosen somewhat arbitrarily but should be safe since we do not support any
      // date/time formats that contain the `@` character
      val placeholder = "@"
      withResource(regexpFiltered.stringReplaceWithBackrefs(
        raw"(\.\d{3})(Z?)\Z", raw"\1$placeholder\2")) { tmp =>
        withResource(Scalar.fromString(placeholder)) { from =>
          withResource(Scalar.fromString("000")) { to =>
            tmp.stringReplace(from, to)
          }
        }
      }
    }

    def isTimestamp(fmt: String): ColumnVector = {
      val pos = fmt.indexOf('T')
      if (pos == -1) {
        sanitized.isTimestamp(fmt)
      } else {
        // Spark supports both ` ` and `T` as the delimiter so we have to test
        // for both formats when calling `isTimestamp` in cuDF but the
        // `asTimestamp` method ignores the delimiter so we only need to call that
        // with one format
        val withSpaceDelim = fmt.substring(0, pos) + ' ' + fmt.substring(pos + 1)
        withResource(sanitized.isTimestamp(fmt)) { isValidFmt1 =>
          withResource(sanitized.isTimestamp(withSpaceDelim)) { isValidFmt2 =>
            isValidFmt1.or(isValidFmt2)
          }
        }
      }
    }

    def asTimestampOrNull(fmt: String): ColumnVector = {
      withResource(Scalar.fromNull(dtype)) { nullScalar =>
        withResource(isTimestamp(fmt)) { isValid =>
          withResource(sanitized.asTimestamp(dtype, fmt)) { ts =>
            isValid.ifElse(ts, nullScalar)
          }
        }
      }
    }

    def asTimestampOr(fmt: String, orValue: ColumnVector): ColumnVector = {
      withResource(orValue) { _ =>
        withResource(isTimestamp(fmt)) { isValid =>
          withResource(sanitized.asTimestamp(dtype, fmt)) { ts =>
            isValid.ifElse(ts, orValue)
          }
        }
      }
    }

    withResource(sanitized) { _ =>
      if (cudfFormats.length == 1) {
        asTimestampOrNull(cudfFormats.head)
      } else {
        cudfFormats.tail.foldLeft(asTimestampOrNull(cudfFormats.head)) { (input, fmt) =>
          asTimestampOr(fmt, input)
        }
      }
    }
  }

  def castStringToBool(input: ColumnVector): ColumnVector

  def castStringToFloat(input: ColumnVector, dt: DType): ColumnVector = {
    GpuCast.castStringToFloats(input, ansiEnabled = false, dt)
  }

  def castStringToDecimal(input: ColumnVector, dt: DecimalType): ColumnVector = {
    GpuCast.castStringToDecimal(input, ansiEnabled = false, dt)
  }

  def castStringToInt(input: ColumnVector, intType: DType): ColumnVector = {
    withResource(input.isInteger(intType)) { isInt =>
      withResource(input.castTo(intType)) { asInt =>
        withResource(Scalar.fromNull(intType)) { nullValue =>
          isInt.ifElse(asInt, nullValue)
        }
      }
    }
  }

  /**
   * Read the host buffer to GPU table
   * @param dataBuffer host buffer to be read
   * @param dataSize the size of host buffer
   * @param cudfSchema the cudf schema of the data
   * @param readDataSchema the Spark schema describing what will be read
   * @param isFirstChunk if it is the first chunk
   * @return table
   */
  def readToTable(
    dataBuffer: HostMemoryBuffer,
    dataSize: Long,
    cudfSchema: Schema,
    readDataSchema: StructType,
    isFirstChunk: Boolean): Table

  /**
   * File format short name used for logging and other things to uniquely identity
   * which file format is being used.
   *
   * @return the file format short name
   */
  def getFileFormatShortName: String

  /**
   * Handle the table decoded by GPU
   *
   * Please note that, this function owns table which is supposed to be closed in this function
   * But for the optimization, we just return the original table.
   *
   * @param readDataSchema the Spark schema describing what will be read
   * @param table the table decoded by GPU
   * @return the new optional Table
   */
  def handleResult(readDataSchema: StructType, table: Table): Option[Table] = {
    val numColumns = table.getNumberOfColumns

    closeOnExcept(table) { _ =>
      if (readDataSchema.length != numColumns) {
        throw new QueryExecutionException(s"Expected ${readDataSchema.length} columns " +
          s"but only read ${table.getNumberOfColumns} from $partFile")
      }
    }

    // For the GPU resource handling convention, we should close input table and return a new
    // table just like below code. But for optimization, we just return the input table.
    // withResource(table) { _
    //  val cols = (0 until  table.getNumberOfColumns).map(i => table.getColumn(i))
    //  Some(new Table(cols: _*))
    // }
    Some(table)
  }

  override def next(): Boolean = {
    batch.foreach(_.close())
    batch = if (isExhausted) {
      metrics(PEAK_DEVICE_MEMORY).set(maxDeviceMemory)
      None
    } else {
      readBatch()
    }

    // NOTE: At this point, the task may not have yet acquired the semaphore if `batch` is `None`.
    // We are not acquiring the semaphore here since this next() is getting called from
    // the `PartitionReaderIterator` which implements a standard iterator pattern, and
    // advertises `hasNext` as false when we return false here. No downstream tasks should
    // try to call next after `hasNext` returns false, and any task that produces some kind of
    // data when `hasNext` is false is responsible to get the semaphore themselves.
    batch.isDefined
  }

  override def get(): ColumnarBatch = {
    val ret = batch.getOrElse(throw new NoSuchElementException)
    batch = None
    ret
  }

  override def close(): Unit = {
    lineReader.close()
    batch.foreach(_.close())
    batch = None
    isExhausted = true
  }
}

object GpuTextBasedDateUtils {

  private val supportedDateFormats = Set(
    "yyyy-MM-dd",
    "yyyy/MM/dd",
    "yyyy-MM",
    "yyyy/MM",
    "MM-yyyy",
    "MM/yyyy",
    "MM-dd-yyyy",
    "MM/dd/yyyy",
    "dd-MM-yyyy",
    "dd/MM/yyyy"
  )

  private val supportedTsPortionFormats = Set(
    "HH:mm:ss.SSSXXX",
    "HH:mm:ss[.SSS][XXX]",
    "HH:mm:ss[.SSSXXX]",
    "HH:mm",
    "HH:mm:ss",
    "HH:mm[:ss]",
    "HH:mm:ss.SSS",
    "HH:mm:ss[.SSS]"
  )

  def tagCudfFormat(
      meta: RapidsMeta[_, _, _],
      sparkFormat: String,
      parseString: Boolean): Unit = {
    if (GpuOverrides.getTimeParserPolicy == LegacyTimeParserPolicy) {
      try {
        // try and convert the format to cuDF format - this will throw an exception if
        // the format contains unsupported characters or words
        toCudfFormats(sparkFormat, parseString)
        // format parsed ok but we have no 100% compatible formats in LEGACY mode
        if (GpuToTimestamp.LEGACY_COMPATIBLE_FORMATS.contains(sparkFormat)) {
          // LEGACY support has a number of issues that mean we cannot guarantee
          // compatibility with CPU
          // - we can only support 4 digit years but Spark supports a wider range
          // - we use a proleptic Gregorian calender but Spark uses a hybrid Julian+Gregorian
          //   calender in LEGACY mode
          if (SQLConf.get.ansiEnabled) {
            meta.willNotWorkOnGpu("LEGACY format in ANSI mode is not supported on the GPU")
          } else if (!meta.conf.incompatDateFormats) {
            meta.willNotWorkOnGpu(s"LEGACY format '$sparkFormat' on the GPU is not guaranteed " +
              s"to produce the same results as Spark on CPU. Set " +
              s"${RapidsConf.INCOMPATIBLE_DATE_FORMATS.key}=true to force onto GPU.")
          }
        } else {
          meta.willNotWorkOnGpu(s"LEGACY format '$sparkFormat' is not supported on the GPU.")
        }
      } catch {
        case e: TimestampFormatConversionException =>
          meta.willNotWorkOnGpu(s"Failed to convert ${e.reason} ${e.getMessage}")
      }
    } else {
      val parts = sparkFormat.split("'T'", 2)
      if (parts.isEmpty) {
        meta.willNotWorkOnGpu(s"the timestamp format '$sparkFormat' is not supported")
      }
      if (parts.headOption.exists(h => !supportedDateFormats.contains(h))) {
        meta.willNotWorkOnGpu(s"the timestamp format '$sparkFormat' is not supported")
      }
      if (parts.length > 1 && !supportedTsPortionFormats.contains(parts(1))) {
        meta.willNotWorkOnGpu(s"the timestamp format '$sparkFormat' is not supported")
      }
      try {
        // try and convert the format to cuDF format - this will throw an exception if
        // the format contains unsupported characters or words
        toCudfFormats(sparkFormat, parseString)
      } catch {
        case e: TimestampFormatConversionException =>
          meta.willNotWorkOnGpu(s"Failed to convert ${e.reason} ${e.getMessage}")
      }
    }
  }

  /**
   * Get the list of all cuDF formats that need to be checked for when parsing timestamps. The
   * returned formats must be ordered such that the first format is the most lenient and the
   * last is the least lenient.
   *
   * For example, the spark format `yyyy-MM-dd'T'HH:mm:ss[.SSS][XXX]` would result in the
   * following cuDF formats being returned, in this order:
   *
   * - `%Y-%m-%d`
   * - `%Y-%m-%dT%H:%M`
   * - `%Y-%m-%dT%H:%M:%S`
   * - `%Y-%m-%dT%H:%M:%S.%f`
   */
  def toCudfFormats(sparkFormat: String, parseString: Boolean): Seq[String] = {
    val hasZsuffix = sparkFormat.endsWith("Z")
    val formatRoot = if (hasZsuffix) {
      sparkFormat.substring(0, sparkFormat.length-1)
    } else {
      sparkFormat
    }

    // strip off suffixes that cuDF will not recognize
    val cudfSupportedFormat = formatRoot
      .replace("'T'", "T")
      .replace("[.SSSXXX]", "")
      .replace("[.SSS][XXX]", "")
      .replace("[.SSS]", "")
      .replace("[.SSSSSS]", "")
      .replace(".SSSXXX", "")
      .replace(".SSS", "")
      .replace("[:ss]", "")

    val cudfFormat = toStrf(cudfSupportedFormat, parseString)
    val suffix = if (hasZsuffix) "Z" else ""

    val optionalFractional = Seq("[.SSS][XXX]", "[.SSS]", "[.SSSSSS]", "[.SSS][XXX]",
      ".SSSXXX", ".SSS")
    val baseFormats = if (optionalFractional.exists(formatRoot.endsWith)) {
      val cudfFormat1 = cudfFormat + suffix
      val cudfFormat2 = cudfFormat + ".%f" + suffix
      Seq(cudfFormat1, cudfFormat2)
    } else if (formatRoot.endsWith("[:ss]")) {
      Seq(cudfFormat + ":%S" + suffix)
    } else {
      Seq(cudfFormat)
    }

    val pos = baseFormats.head.indexOf('T')
    val formatsIncludingDateOnly = if (pos == -1) {
      baseFormats
    } else {
      Seq(baseFormats.head.substring(0, pos)) ++ baseFormats
    }

    // seconds are always optional in Spark
    val formats = ListBuffer[String]()
    for (fmt <- formatsIncludingDateOnly) {
      if (fmt.contains(":%S") && !fmt.contains("%f")) {
        formats += fmt.replace(":%S", "")
      }
      formats += fmt
    }
    formats
  }

}