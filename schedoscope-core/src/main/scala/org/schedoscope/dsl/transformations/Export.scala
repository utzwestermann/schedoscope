/**
 * Copyright 2016 Otto (GmbH & Co KG)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.schedoscope.dsl.transformations

import org.apache.hadoop.mapreduce.Job
import org.schedoscope.Settings
import org.schedoscope.dsl.{ Field, View }
import org.schedoscope.export.jdbc.JdbcExportJob
import org.schedoscope.export.jdbc.exception.{ RetryException, UnrecoverableException }
import org.schedoscope.export.redis.RedisExportJob
import org.schedoscope.scheduler.driver.{ Driver, DriverRunFailed, DriverRunState, DriverRunSucceeded, RetryableDriverException }
import org.schedoscope.Schedoscope

/**
 * A helper class to with constructors for exportTo() MR jobs.
 */
object Export {

  /**
   * This function configures the JDBC export job and returns a MapreduceTransformation.
   *
   * @param v The view to export
   * @param jdbcConnection A JDBC connection string
   * @param dbUser The database user
   * @param dbPass the database password
   * @param distributionKey The distribution key (only relevant for exasol)
   * @param storageEngine The underlying storage engine (only relevant for MySQL)
   * @param numReducers The number of reducers, defines concurrency
   * @param commitSize The size of batches for JDBC inserts
   * @param isKerberized Is the cluster kerberized?
   * @param kerberosPrincipal The kerberos principal to use
   * @param metastoreUri The thrift URI to the metastore
   */
  def Jdbc(
    v: View,
    jdbcConnection: String,
    dbUser: String,
    dbPass: String,
    distributionKey: Field[_] = null,
    storageEngine: String = Schedoscope.settings.jdbcStorageEngine,
    numReducers: Int = Schedoscope.settings.jdbcExportNumReducers,
    commitSize: Int = Schedoscope.settings.jdbcExportBatchSize,
    isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
    kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
    metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { (p => s"${p.n} = '${p.v.get}'") }
          .mkString(" and ")

        val distributionField = if (distributionKey != null) distributionKey.n else null

        new JdbcExportJob().configure(
          conf.get("isKerberized").get.asInstanceOf[Boolean],
          conf.get("metastoreUri").get.asInstanceOf[String],
          conf.get("kerberosPrincipal").get.asInstanceOf[String],
          conf.get("jdbcConnection").get.asInstanceOf[String],
          conf.get("dbUser").getOrElse(null).asInstanceOf[String],
          conf.get("dbPass").getOrElse(null).asInstanceOf[String],
          v.dbName,
          v.n,
          filter,
          conf.get("storageEngine").get.asInstanceOf[String],
          distributionField,
          conf.get("numReducers").get.asInstanceOf[Int],
          conf.get("commitSize").get.asInstanceOf[Int])

      },
      jdbcPostCommit)

    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "jdbcConnection" -> jdbcConnection,
        "dbUser" -> dbUser,
        "dbPass" -> dbPass,
        "storageEngine" -> storageEngine,
        "numReducers" -> numReducers,
        "commitSize" -> commitSize,
        "isKerberized" -> isKerberized,
        "kerberosPrincipal" -> kerberosPrincipal,
        "metastoreUri" -> metastoreUri))

  }

  /**
   * This function runs the post commit action and finalizes the database tables.
   *
   * @param job The MR job object
   * @param driver The schedoscope driver
   * @param runState The job's runstate
   */
  def jdbcPostCommit(
    job: Job,
    driver: Driver[MapreduceTransformation],
    runState: DriverRunState[MapreduceTransformation]): DriverRunState[MapreduceTransformation] = {

    val jobConfigurer = new JdbcExportJob()

    try {

      jobConfigurer.postCommit(runState.isInstanceOf[DriverRunSucceeded[MapreduceTransformation]], job.getConfiguration)
      runState

    } catch {
      case ex: RetryException         => throw new RetryableDriverException(ex.getMessage, ex)
      case ex: UnrecoverableException => DriverRunFailed(driver, ex.getMessage, ex)
    }
  }

  /**
   * This function configures the Redis export job and returns a MapreduceTransformation.
   *
   * @param v The view
   * @param redisHost The Redis hostname
   * @param key The field to use as the Redis key
   * @param value An optional field to export. If null, all fields are attached to the key as a map. If not null, only that field's value is attached to the key.
   * @param keyPrefix An optional key prefix
   * @param replace A flag indicating of existing keys should be replaced (or extended)
   * @param flush A flag indicating if the key space should be flushed before writing data
   * @param redisPort The Redis port (default 6379)
   * @param redisKeySpace The Redis key space (default 0)
   * @param numReducers The number of reducers, defines concurrency
   * @param pipeline A flag indicating that the Redis pipeline mode should be used for writing data
   * @param isKerberized Is the cluster kerberized?
   * @param kerberosPrincipal The kerberos principal to use
   * @param metastoreUri The thrift URI to the metastore
   */
  def Redis(
    v: View,
    redisHost: String,
    key: Field[_],
    value: Field[_] = null,
    keyPrefix: String = "",
    replace: Boolean = false,
    flush: Boolean = false,
    redisPort: Int = 6379,
    redisKeySpace: Int = 0,
    numReducers: Int = Schedoscope.settings.redisExportNumReducers,
    pipeline: Boolean = Schedoscope.settings.redisExportUsesPipelineMode,
    isKerberized: Boolean = !Schedoscope.settings.kerberosPrincipal.isEmpty(),
    kerberosPrincipal: String = Schedoscope.settings.kerberosPrincipal,
    metastoreUri: String = Schedoscope.settings.metastoreUri) = {

    val t = MapreduceTransformation(
      v,
      (conf) => {

        val filter = v.partitionParameters
          .map { (p => s"${p.n} = '${p.v.get}'") }
          .mkString(" and ")

        val valueFieldName = if (value != null) value.n else null

        new RedisExportJob().configure(
          conf.get("isKerberized").get.asInstanceOf[Boolean],
          conf.get("metastoreUri").get.asInstanceOf[String],
          conf.get("kerberosPrincipal").get.asInstanceOf[String],
          conf.get("redisHost").get.asInstanceOf[String],
          conf.get("redisPort").get.asInstanceOf[Int],
          conf.get("redisKeySpace").get.asInstanceOf[Int],
          v.dbName,
          v.n,
          filter,
          key.n,
          valueFieldName,
          keyPrefix,
          conf.get("numReducers").get.asInstanceOf[Int],
          replace,
          conf.get("pipeline").get.asInstanceOf[Boolean],
          flush)

      })

    t.directoriesToDelete = List()
    t.configureWith(
      Map(
        "redisHost" -> redisHost,
        "redisPort" -> redisPort,
        "redisKeySpace" -> redisKeySpace,
        "numReducers" -> numReducers,
        "pipeline" -> pipeline,
        "isKerberized" -> isKerberized,
        "kerberosPrincipal" -> kerberosPrincipal,
        "metastoreUri" -> metastoreUri))

  }
}
