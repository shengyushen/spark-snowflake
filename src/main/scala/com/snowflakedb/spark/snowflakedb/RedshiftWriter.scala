/*
 * Copyright 2015 TouchType Ltd
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

package com.snowflakedb.spark.snowflakedb

import java.net.URI
import java.sql.{Connection, Date, SQLException, Timestamp}

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import org.apache.hadoop.fs.{Path, FileSystem}
import org.apache.spark.TaskContext
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import com.snowflakedb.spark.snowflakedb.Parameters.MergedParameters

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SQLContext}
import org.apache.spark.sql.types._

/**
 * Functions to write data to Redshift.
 *
 * At a high level, writing data back to Redshift involves the following steps:
 *
 *   - Use the spark-avro library to save the DataFrame to S3 using Avro serialization. Prior to
 *     saving the data, certain data type conversions are applied in order to work around
 *     limitations in Avro's data type support and Redshift's case-insensitive identifier handling.
 *
 *     While writing the Avro files, we use accumulators to keep track of which partitions were
 *     non-empty. After the write operation completes, we use this to construct a list of non-empty
 *     Avro partition files.
 *
 *   - Use JDBC to issue any CREATE TABLE commands, if required.
 *
 *   - If there is data to be written (i.e. not all partitions were empty), then use the list of
 *     non-empty Avro files to construct a JSON manifest file to tell Redshift to load those files.
 *     This manifest is written to S3 alongside the Avro files themselves. We need to use an
 *     explicit manifest, as opposed to simply passing the name of the directory containing the
 *     Avro files, in order to work around a bug related to parsing of empty Avro files (see #96).
 *
 *   - Use JDBC to issue a COPY command in order to instruct Redshift to load the Avro data into
 *     the appropriate table. If the Overwrite SaveMode is being used, then by default the data
 *     will be loaded into a temporary staging table, which later will atomically replace the
 *     original table via a transaction.
 */
private[snowflakedb] class RedshiftWriter(
    jdbcWrapper: JDBCWrapper,
    s3ClientFactory: AWSCredentials => AmazonS3Client) {

  private val log = LoggerFactory.getLogger(getClass)

  // Execute a given string, logging it
  def sql(conn : Connection, query : String): Boolean = {
    log.info(query)
    conn.prepareStatement(query).execute()
  }

  /**
   * Generate CREATE TABLE statement for Snowflake
   */
  // Visible for testing.
  private[snowflakedb] def createTableSql(data: DataFrame, params: MergedParameters): String = {
    val schemaSql = jdbcWrapper.schemaString(data.schema)
    // snowflake-todo: for now, we completely ignore
    // params.distStyle and params.distKey
    val table = params.table.get
    s"CREATE TABLE IF NOT EXISTS $table ($schemaSql)"
  }

  /**
   * Generate the COPY SQL command
   */
  private def copySql(
      sqlContext: SQLContext,
      params: MergedParameters,
      creds: AWSCredentials,
      filesToCopyPrefix: String): String = {
    val awsAccessKey = creds.getAWSAccessKeyId
    val awsSecretKey = creds.getAWSSecretKey
    val fixedUrl = Utils.fixS3Url(filesToCopyPrefix)
    s"""
       |COPY INTO ${params.table.get}
       |FROM '$fixedUrl'
       |CREDENTIALS = (
       |    AWS_KEY_ID='$awsAccessKey'
       |    AWS_SECRET_KEY='$awsSecretKey'
       |)
       |FILE_FORMAT = (
       |    TYPE=CSV
       |    /* COMPRESSION=none */
       |    FIELD_DELIMITER='|'
       |    /* ESCAPE='\\\\' */
       |    FIELD_OPTIONALLY_ENCLOSED_BY='"'
       |    TIMESTAMP_FORMAT='YYYY-MM-DD HH24:MI:SS.FF3 TZHTZM'
       |  )
    """.stripMargin.trim
  }

  /**
   * Sets up a staging table then runs the given action, passing the temporary table name
   * as a parameter.
   */
  private def withStagingTable(
      conn: Connection,
      table: TableName,
      action: (String) => Unit) {
    val randomSuffix = Math.abs(Random.nextInt()).toString
    val tempTable =
      table.copy(unescapedTableName = s"${table.unescapedTableName}_staging_$randomSuffix")
    log.info(s"Loading new data for Snowflake table '$table' using temporary table '$tempTable'")

    try {
      action(tempTable.toString)

      if (jdbcWrapper.tableExists(conn, table.toString)) {
        // Rename temp table to final table. Use SWAP to make it atomic.
        sql(conn, s"ALTER TABLE ${table} SWAP WITH ${tempTable}")
      } else {
        // Table didn't exist, just rename temp table to it
        sql(conn, s"ALTER TABLE $tempTable RENAME TO $table")
      }
    } finally {
      // If anything went wrong (or if SWAP worked), delete the temp table
      sql(conn, s"DROP TABLE IF EXISTS $tempTable")
    }
  }

  /**
   * Perform the Redshift load, including deletion of existing data in the case of an overwrite,
   * and creating the table if it doesn't already exist.
   */
  private def doRedshiftLoad(
      conn: Connection,
      data: DataFrame,
      saveMode: SaveMode,
      params: MergedParameters,
      creds: AWSCredentials,
      filesToCopyPrefix: Option[String]): Unit = {

    // Overwrites must drop the table, in case there has been a schema update
    if (saveMode == SaveMode.Overwrite) {
      val deleteStatement = s"DROP TABLE IF EXISTS ${params.table.get}"
      log.info(deleteStatement)
      val deleteExisting = conn.prepareStatement(deleteStatement)
      deleteExisting.execute()
    }

    // If the table doesn't exist, we need to create it first, using JDBC to infer column types
    val createStatement = createTableSql(data, params)
    log.info(createStatement)
    val createTable = conn.prepareStatement(createStatement)
    createTable.execute()

    // Perform the load if there were files loaded
    if (filesToCopyPrefix.isDefined) {
      // Load the temporary data into the new file
      val copyStatement = copySql(data.sqlContext, params,
                                  creds, filesToCopyPrefix.get)
      log.info(copyStatement)
      val copyData = conn.prepareStatement(copyStatement)
      try {
        copyData.execute()
      } catch {
        case e: SQLException =>
          // snowflake-todo: try to provide more error information,
          // possibly from actual SQL output
          log.error("Error occurred while loading files to Snowflake: " + e)
          throw e
      }
    }

    // Execute postActions
    params.postActions.foreach { action =>
      val actionSql = if (action.contains("%s")) action.format(params.table.get) else action
      log.info("Executing postAction: " + actionSql)
      conn.prepareStatement(actionSql).execute()
    }
  }

  /**
   * Serialize temporary data to S3, ready for Snowflake COPY
   *
   * @return the URL of the file prefix in S3 that should be loaded
   *         (usually "$tempDir/part"),if at least one record was written,
   *         and None otherwise.
   */
  private def unloadData(
      sqlContext: SQLContext,
      data: DataFrame,
      tempDir: String): Option[String] = {
    // spark-avro does not support Date types. In addition, it converts Timestamps into longs
    // (milliseconds since the Unix epoch). Redshift is capable of loading timestamps in
    // 'epochmillisecs' format but there's no equivalent format for dates. To work around this, we
    // choose to write out both dates and timestamps as strings using the same timestamp format.
    // For additional background and discussion, see #39.

    // Convert the rows so that timestamps and dates become formatted strings:
    val conversionFunctions: Array[Any => Any] = data.schema.fields.map { field =>
      field.dataType match {
        case DateType => (v: Any) => v match {
          case null => null
          case t: Timestamp => Conversions.formatTimestamp(t)
          case d: Date => Conversions.formatDate(d)
        }
        case TimestampType => (v: Any) => {
          if (v == null) null else Conversions.formatTimestamp(v.asInstanceOf[Timestamp])
        }
        case _ => (v: Any) => v
      }
    }

    // Use Spark accumulators to determine which partitions were non-empty.
    val nonEmptyPartitions =
      sqlContext.sparkContext.accumulableCollection(mutable.HashSet.empty[Int])

    val convertedRows: RDD[Row] = data.mapPartitions { iter =>
      if (iter.hasNext) {
        nonEmptyPartitions += TaskContext.get.partitionId()
      }
      iter.map { row =>
        val convertedValues: Array[Any] = new Array(conversionFunctions.length)
        var i = 0
        while (i < conversionFunctions.length) {
          convertedValues(i) = conversionFunctions(i)(row(i))
          i += 1
        }
        Row.fromSeq(convertedValues)
      }
    }

    // Convert all column names to lowercase, which is necessary for Redshift to be able to load
    // those columns (see #51).
    val schemaWithLowercaseColumnNames: StructType =
      StructType(data.schema.map(f => f.copy(name = f.name.toLowerCase)))

    if (schemaWithLowercaseColumnNames.map(_.name).toSet.size != data.schema.size) {
      throw new IllegalArgumentException(
        "Cannot save table to Redshift because two or more column names would be identical" +
        " after conversion to lowercase: " + data.schema.map(_.name).mkString(", "))
    }

    // Update the schema so that Avro writes date and timestamp columns as formatted timestamp
    // strings. This is necessary for Redshift to be able to load these columns (see #39).
    val convertedSchema: StructType = StructType(
      schemaWithLowercaseColumnNames.map {
        case StructField(name, DateType, nullable, meta) =>
          StructField(name, StringType, nullable, meta)
        case StructField(name, TimestampType, nullable, meta) =>
          StructField(name, StringType, nullable, meta)
        case other => other
      }
    )

    sqlContext.createDataFrame(convertedRows, convertedSchema)
      .write
      .format("com.databricks.spark.csv")
      .option("quote", "\"")
      .option("escape", "\\")
      .option("delimiter", "|")
      .save(tempDir)

    if (nonEmptyPartitions.value.isEmpty) {
      None
    } else {
      // Verify there was at least one file created.
      // The saved filenames are going to be of the form part*
      val fs = FileSystem.get(URI.create(tempDir), sqlContext.sparkContext.hadoopConfiguration)
      val firstFile = fs.listStatus(new Path(tempDir))
        .iterator
        .map(_.getPath.getName)
        .filter(_.startsWith("part"))
        .take(1)
        .toSeq
        .headOption.getOrElse(throw new Exception("No part files were written!"))
      // Note - temp dir already has "/", no need to add it
      Some(tempDir + "part")
    }
  }

  /**
   * Write a DataFrame to a Redshift table, using S3 and Avro serialization
   */
  def saveToRedshift(
      sqlContext: SQLContext,
      data: DataFrame,
      saveMode: SaveMode,
      params: MergedParameters) : Unit = {
    if (params.table.isEmpty) {
      throw new IllegalArgumentException(
        "For save operations you must specify a Redshift table name with the 'dbtable' parameter")
    }

    val creds: AWSCredentials = params.temporaryAWSCredentials.getOrElse(
      AWSCredentialsUtils.load(params.rootTempDir, sqlContext.sparkContext.hadoopConfiguration))

    Utils.assertThatFileSystemIsNotS3BlockFileSystem(
      new URI(params.rootTempDir), sqlContext.sparkContext.hadoopConfiguration)

    Utils.checkThatBucketHasObjectLifecycleConfiguration(params.rootTempDir, s3ClientFactory(creds))

    val conn = jdbcWrapper.getConnector(params)

    try {
      val tempDir = params.createPerQueryTempDir()
      val filesToCopyPrefix = unloadData(sqlContext, data, tempDir)
      if (saveMode == SaveMode.Overwrite && params.useStagingTable) {
        withStagingTable(conn, params.table.get, stagingTable => {
          val updatedParams = MergedParameters(params.parameters.updated("dbtable", stagingTable))
          doRedshiftLoad(conn, data, saveMode, updatedParams, creds, filesToCopyPrefix)
        })
      } else {
        doRedshiftLoad(conn, data, saveMode, params, creds, filesToCopyPrefix)
      }
    } finally {
      conn.close()
    }
  }
}

object DefaultRedshiftWriter extends RedshiftWriter(
  DefaultJDBCWrapper,
  awsCredentials => new AmazonS3Client(awsCredentials))
