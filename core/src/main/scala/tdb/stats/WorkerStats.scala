/**
 * Copyright (C) 2013 Carnegie Mellon University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package tdb.stats

import akka.actor.{Actor, ActorLogging}
import org.mashupbots.socko.events.HttpRequestEvent
import org.mashupbots.socko.routes._
import java.io._
import scala.collection.mutable.{Buffer, Map}
import sys.process._

object WorkerStats {

  var datastoreMisses = 0

  var datastoreReads = 0

  var datastoreWrites = 0

  var modsCreated = 0

  var numTasks = 0

  // BerkeleyDB
  var berkeleyReads = 0

  var berkeleyWrites = 0

  def newTick() = {
    val tick = new WorkerTick(
      datastoreMisses,
      datastoreReads,
      datastoreWrites,
      modsCreated,
      numTasks,
      berkeleyReads,
      berkeleyWrites)

    datastoreMisses = 0
    datastoreReads = 0
    datastoreWrites = 0
    modsCreated = 0
    numTasks = 0
    berkeleyReads = 0
    berkeleyWrites = 0

    tick
  }
}

case class WorkerTick
    (datastoreMisses: Int,
     datastoreReads: Int,
     datastoreWrites: Int,
     modsCreated: Int,
     numTasks: Int,
     berkeleyReads: Int,
     berkeleyWrites: Int)

class WorkerStats extends Actor with ActorLogging {
  val stats = Buffer[WorkerTick]()

  var nextTick = 0

  private val webuiRoot = "webui/"

  private def getBytes(path: String): Array[Byte] = {
    val f = new File("webui/" + path)
    val buf = new BufferedInputStream(new FileInputStream("webui/" + path))
    val arr = new Array[Byte](f.length().toInt)
    buf.read(arr)

    arr
  }

  def receive = {
    case "tick" =>
      stats += WorkerStats.newTick()
      nextTick += 1

    case request: HttpRequestEvent =>
      request match {
        case GET(Path("/tasks.png")) =>
          val command = "python webui/chart.py " + stats.map(_.numTasks).mkString(" ")

          val output = command.!!

          request.response.write(getBytes("tasks.png"), "image/png")

        case GET(Path("/datastore.png")) =>
          val output = new BufferedWriter(new FileWriter(webuiRoot + "datastore.txt"))

          val lastReads = Buffer[Int]()
          val lastWrites = Buffer[Int]()
          val lastCreates = Buffer[Int]()
          for (tick <- stats) {
            lastReads += tick.datastoreReads
            lastWrites += tick.datastoreWrites
            lastCreates += tick.modsCreated

            if (lastReads.size > 10) {
              lastReads -= lastReads.head
              lastWrites -= lastWrites.head
              lastCreates -= lastCreates.head
            }

            output.write(lastReads.reduce(_ + _) + " " + lastWrites.reduce(_ + _) + " " +
                         lastCreates.reduce(_ + _) + "\n")
          }
          output.close()

          "python webui/datastore.py".!

          request.response.write(getBytes("datastore.png"), "image/png")

        case GET(Path("/berkeleydb.png")) =>
          val output = new BufferedWriter(new FileWriter(webuiRoot + "berkeleydb.txt"))

          val lastReads = Buffer[Int]()
          val lastWrites = Buffer[Int]()
          for (tick <- stats) {
            lastReads += tick.berkeleyReads
            lastWrites += tick.berkeleyWrites

            if (lastReads.size > 10) {
              lastReads -= lastReads.head
              lastWrites -= lastWrites.head
            }

            output.write(lastReads.reduce(_ + _) + " " + lastWrites.reduce(_ + _) + "\n")
          }
          output.close()

          "python webui/berkeleydb.py".!

          request.response.write(getBytes("berkeleydb.png"), "image/png")

        case GET(Path("/")) =>
          var s = "Worker<br>"

          s += """
            <table>
              <tr>
                <td><a href='tasks.png'>tasks</></td>
              </tr>
            </table"""

          val title = "TDB Worker"

          request.response.write(Stats.createPage(title, s), "text/html; charset=UTF-8")
        case _ =>
          request.response.write("This page does not exist.")
      }

    case x =>
      log.warning("Received unhandled message " +
                  x + " from " + sender + " " + x.getClass)
  }
}
