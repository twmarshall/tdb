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

package tbd.visualization

import tbd.visualization.analysis._
import collection.mutable.{MutableList}

abstract class TraceDistancePlotGenerator[T](
  val distanceAlgorithm: TraceComparison) extends ExperimentSink[T] {

  protected val experiments = MutableList[ExperimentResult[T]]()

  def getPlot(): PlotInfo

  def resultReceived(result: ExperimentResult[T],
                     sender: ExperimentSource[T]) {
    experiments += result
  }

  override def finish() = {
    println(getPlot().formatAsText())
  }
}

case class PlotInfo(val data: Array[Array[Float]],
                    val xaxis: Array[Float],
                    val yaxis: Array[Float],
                    val xaxisDescription: String = "",
                    val yaxisDescription: String = "",
                    val plotTitle: String = "") {
  def formatAsText(seperator: String = "\t"): String = {
    val sb = new StringBuilder()

    sb.append(plotTitle).append("\n")
    sb.append(yaxisDescription).append("\\").append(xaxisDescription).append("\t\n\t")

    xaxis.foreach(x => sb.append(x).append("\t"))

    sb.append("\n")

    val yIterator = yaxis.iterator
    val dataIterator = data.iterator

    while(yIterator.hasNext && dataIterator.hasNext) {
      val y = yIterator.next
      val data = dataIterator.next

      sb.append(y).append("\t")
      data.foreach(x => sb.append(x).append("\t"))
      sb.append("\n")
    }

    sb.toString()
  }
}