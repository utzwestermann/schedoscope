/**
  * Copyright 2015 Otto (GmbH & Co KG)
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
package org.schedoscope.scheduler.commandline

import org.schedoscope.scheduler.messages._
import org.schedoscope.scheduler.service.SchedoscopeService

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * This class parses Schedoscope cli commands passed to it, forwards them to a Schedoscope service to execute,
  * and renders the result.
  */
class SchedoscopeCliCommandRunner(schedoscope: SchedoscopeService) {

  lazy val TIMEOUT: FiniteDuration = 3600 seconds

  object Action extends Enumeration {
    val VIEWS, TRANSFORMATIONS, QUEUES, MATERIALIZE, COMMANDS, INVALIDATE, NEWDATA, SHUTDOWN = Value
  }

  import Action._

  case class Config(action: Option[Action.Value] = None, viewUrlPath: Option[String] = None, status: Option[String] = None, typ: Option[String] = None, dependencies: Option[Boolean] = Some(false), filter: Option[String] = None, issueFilter: Option[String] = None, mode: Option[String] = None, overview: Option[Boolean] = None, all: Option[Boolean] = None)

  val parser = new scopt.OptionParser[Config]("schedoscope-control") {
    override def showUsageOnError = true

    head("schedoscope-control")
    help("help") text "print usage"

    cmd("views") action { (_, c) => c.copy(action = Some(VIEWS)) } text "lists all views, along with their status" children(
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional() valueName "<status>" text "filter views by their status (e.g. 'transforming')",
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } optional() valueName "<viewUrlPath>" text "view url path (e.g. 'my.database/MyView/Partition1/Partition2'). ",
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional() valueName "<regex>" text "regular expression to filter view display (e.g. 'my.database/.*/Partition1/.*'). ",
      opt[String]('i', "issueFilter") action { (x, c) => c.copy(issueFilter = Some(x)) } optional() valueName "<errors|incomplete>" text "filter views by the dependencies that had issues when transforming (e.g. 'errors' or 'incomplete' or 'errorsANDincomplete')",
      opt[Unit]('d', "dependencies") action { (_, c) => c.copy(dependencies = Some(true)) } optional() text "include dependencies",
      opt[Unit]('o', "overview") action { (_, c) => c.copy(overview = Some(true)) } optional() text "show only overview, skip individual views",
      opt[Unit]('a', "all") action { (_, c) => c.copy(all = Some(true)) } optional() text "show details for views")

    cmd("transformations") action { (_, c) => c.copy(action = Some(TRANSFORMATIONS)) } text "show status of transformation drivers" children(
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional() valueName "<status>" text "filter transformation drivers by their status (e.g. 'running, idle')",
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional() valueName "<regex>" text "regular expression to filter transformation driver display (e.g. '.*hive.*'). ")

    cmd("materialize") action { (_, c) => c.copy(action = Some(MATERIALIZE)) } text "materialize view(s)" children(
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional() valueName "<status>" text "filter views to be materialized by their status (e.g. 'failed')",
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } optional() valueName "<viewUrlPath>" text "view url path (e.g. 'my.database/MyView/Partition1/Partition2'). ",
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional() valueName "<regex>" text "regular expression to filter views to be materialized (e.g. 'my.database/.*/Partition1/.*'). ",
      opt[String]('i', "issueFilter") action { (x, c) => c.copy(issueFilter = Some(x)) } optional() valueName "<errors|incomplete>" text "materialize views that have other dependencies marked with errors or incomplete data (e.g. 'errors' or 'incomplete' or 'errorsANDincomplete')",
      opt[String]('m', "mode") action { (x, c) => c.copy(mode = Some(x)) } optional() valueName "<mode>" text "materialization mode. Supported modes are currently 'RESET_TRANSFORMATION_CHECKSUMS, RESET_TRANSFORMATION_CHECKSUMS_AND_TIMESTAMPS'")

    cmd("invalidate") action { (_, c) => c.copy(action = Some(INVALIDATE)) } text "invalidate view(s)" children(
      opt[String]('s', "status") action { (x, c) => c.copy(status = Some(x)) } optional() valueName "<status>" text "filter views to be invalidated by their status (e.g. 'transforming')",
      opt[String]('v', "viewUrlPath") action { (x, c) => c.copy(viewUrlPath = Some(x)) } optional() valueName "<viewUrlPath>" text "view url path (e.g. 'my.database/MyView/Partition1/Partition2'). ",
      opt[String]('f', "filter") action { (x, c) => c.copy(filter = Some(x)) } optional() valueName "<regex>" text "regular expression to filter views to be invalidated (e.g. 'my.database/.*/Partition1/.*'). ",
      opt[String]('i', "issueFilter") action { (x, c) => c.copy(issueFilter = Some(x)) } optional() valueName "<errors|incomplete>" text "invalidate views that have other dependencies marked with errors or incomplete data (e.g. 'errors' or 'incomplete' or 'errorsANDincomplete')",
      opt[Unit]('d', "dependencies") action { (_, c) => c.copy(dependencies = Some(true)) } optional() text "invalidate dependencies as well")

    cmd("shutdown") action { (_, c) => c.copy(action = Some(SHUTDOWN)) } text "shutdown program"

    checkConfig { c =>
      if (c.action.isEmpty) failure("A command is required")
      else if (c.action.get == MATERIALIZE && c.mode.isDefined && !MaterializeViewMode.values.map {
        _.toString
      }.contains(c.mode.get)) failure(s"mode ${c.mode.get} not supported. Supported are: '${MaterializeViewMode.values.map(_.toString())}'")
      else
        success
    }
  }

  def run(args: Array[String]) {
    parser.parse(args, Config()) match {

      case Some(config) =>

        println("Starting " + config.action.get.toString + " ...")

        try {
          val res = config.action.get match {
            case TRANSFORMATIONS =>
              val res = schedoscope.transformations(config.status, config.filter)
              Await.result(res, TIMEOUT)

            case VIEWS =>
              val res = schedoscope.views(config.viewUrlPath, config.status, config.filter, config.issueFilter, config.dependencies, config.overview, config.all)
              Await.result(res, TIMEOUT)

            case MATERIALIZE =>
              val res = schedoscope.materialize(config.viewUrlPath, config.status, config.filter, config.issueFilter, config.mode)
              Await.result(res, TIMEOUT)

            case INVALIDATE =>
              val res = schedoscope.invalidate(config.viewUrlPath, config.status, config.filter, config.issueFilter, config.dependencies)
              Await.result(res, TIMEOUT)

            case SHUTDOWN =>
              schedoscope.shutdown()
              System.exit(0)

            case _ => {
              println("Unsupported Action: " + config.action.get.toString)
            }
          }
          println("\nRESULTS\n=======")
          println(SchedoscopeCliFormat.serialize(res))
        } catch {
          case t: Throwable => println(s"\nERROR: ${t.getMessage}\n")
        }

      case None => // usage information has already been displayed
    }
  }
}

