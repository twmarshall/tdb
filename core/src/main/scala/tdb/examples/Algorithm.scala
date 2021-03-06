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
package tdb.examples

import java.lang.management.ManagementFactory
import scala.collection.mutable.{Buffer, Map}

import tdb.{Adjustable, Mutator}
import tdb.Debug._
import tdb.list.ListConf
import tdb.master.{MasterConf, MasterConnector}
import tdb.worker.WorkerConf

abstract class Algorithm[Output](val conf: AlgorithmConf) {

  val repeatedRuns =
    for (run <- conf.runs; i <- 0 until conf.updateRepeat)
      yield run

  val connector =
    if (conf.master != "") {
      MasterConnector(conf.master)
    } else {
      val args = Array("--cacheSize", conf.cacheSize.toString,
        "--envHomePath", conf.envHomePath)
      val masterConf = new MasterConf(
        Array("--port", Experiment.port.toString, "--log", conf.logLevel))

      MasterConnector(
        workerArgs = args,
        masterConf = masterConf)
    }

  val mutator = new Mutator(connector)

  var output: Output = null.asInstanceOf[Output]

  def adjust: Adjustable[Output]

  var mapCount = 0
  var reduceCount = 0

  var updateSize = 0

  var naiveLoadElapsed: Long = 0

  val results = Map[String, Double]()

  val actualRuns = Buffer[String]()

  protected def generateNaive()

  protected def runNaive(): Any

  protected def loadInitial()

  protected def hasUpdates(): Boolean

  protected def loadUpdate(): Int

  protected def checkOutput(output: Output): Boolean

  def run(): Map[String, Double] = {
    System.gc()

    if (Experiment.verbosity > 1) {
      println("Generate")
    }

    val beforeLoad = System.currentTimeMillis()
    generateNaive()
    naiveLoadElapsed = System.currentTimeMillis() - beforeLoad
    if (conf.naive) {
      actualRuns += "naive"
      results("naive-load") = naiveLoadElapsed

      if (Experiment.verbosity > 1) {
        println("Naive run.")
      }

      val gcBefore = getGCTime()
      val before = System.currentTimeMillis()
      runNaive()
      results("naive-gc") = getGCTime() - gcBefore
      results("naive") = System.currentTimeMillis() - before -
        results("naive-gc")
    }

    // Initial run.
    actualRuns += "initial"
    System.gc()
    initial()

    if (Experiment.conf.prompts()) {
      prompt
    }

    if (Experiment.dots) {
      mutator.printDDGDots("pagerank.dot")
    }

    if (Experiment.verbosity > 1) {
      if (mapCount != 0) {
        println("map count = " + mapCount)
        mapCount = 0
      }
      if (reduceCount != 0) {
        println("reduce count = " + reduceCount)
        reduceCount = 0
      }
      println("starting prop")
    }

    var r = 1
    while (hasUpdates()) {
      System.gc()
      update()

      if (Experiment.conf.prompts()) {
        prompt
      }
    }

    Experiment.confs("runs") = actualRuns.toList

    if (Experiment.verbosity > 1) {
      if (mapCount != 0)
        println("map count = " + mapCount)
      if (reduceCount != 0)
        println("reduce count = " + reduceCount)
    }

    mutator.shutdown()
    connector.shutdown()

    results
  }

  def initial() {
    if (Experiment.verbosity > 1) {
      println("Initial load.")
    }

    val beforeLoad = System.currentTimeMillis()
    loadInitial()
    val loadElapsed = System.currentTimeMillis() - beforeLoad

    if (Experiment.verbosity > 1) {
      println("Initial run.")
    }

    val gcBefore = getGCTime()
    val before = System.currentTimeMillis()
    output = mutator.run[Output](adjust)
    val elapsed = System.currentTimeMillis() - before
    val gcElapsed = getGCTime() - gcBefore

    if (Experiment.check) {
      assert(checkOutput(output))
    }

    results("initial") = elapsed - gcElapsed
    results("initial-load") = loadElapsed
    results("initial-gc") = gcElapsed
  }

  def update() {
    if (Experiment.verbosity > 1) {
      println("Updating")
    }

    val beforeLoad = System.currentTimeMillis()
    updateSize = loadUpdate()
    val loadElapsed = System.currentTimeMillis() - beforeLoad

    if (Experiment.verbosity > 1) {
      println("Running change propagation.")
    }

    val gcBefore = getGCTime()
    val before = System.currentTimeMillis()
    mutator.propagate()
    val elapsed = System.currentTimeMillis() - before
    val gcElapsed = getGCTime() - gcBefore

    if (Experiment.check) {
      assert(checkOutput(output))
    }

    if (actualRuns.contains(updateSize + "")) {
      val oldCount = results(updateSize + "-count")

      /*def averageIn(oldAverage: Double, newValue: Double) =
        (oldAverage * oldCount + newValue) / (oldCount + 1)

      results(updateSize + "") =
        averageIn(results(updateSize + ""), elapsed - gcElapsed)
      results(updateSize + "-load") =
        averageIn(results(updateSize + "-load"), loadElapsed)
      results(updateSize + "-gc") =
          averageIn(results(updateSize + "-gc"), gcElapsed)
      results(updateSize + "-count") = oldCount + 1*/

      results(updateSize + "-" + oldCount) = elapsed - gcElapsed
      results(updateSize + "-" + oldCount + "-load") = loadElapsed
      results(updateSize + "-" + oldCount + "-gc") = gcElapsed
      results(updateSize + "-count") = oldCount + 1
      actualRuns += updateSize + "-" + oldCount
    } else {
      results(updateSize + "") = elapsed - gcElapsed
      results(updateSize + "-load") = loadElapsed
      results(updateSize + "-gc") = gcElapsed
      results(updateSize + "-count") = 1
      actualRuns += updateSize + ""
    }
  }

  private def getGCTime(): Long = {
    var garbageCollectionTime: Long = 0

    val iter = ManagementFactory.getGarbageCollectorMXBeans().iterator()
    while (iter.hasNext()) {
      val gc = iter.next()

      val time = gc.getCollectionTime()

      if(time >= 0) {
        garbageCollectionTime += time
      }
    }

    garbageCollectionTime
  }
}
