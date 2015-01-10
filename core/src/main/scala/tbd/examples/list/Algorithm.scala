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
package tbd.examples.list

import java.lang.management.ManagementFactory
import scala.collection.mutable.Map

import tbd.{Adjustable, Mutator}
import tbd.list.ListConf
import tbd.master.MasterConnector
import tbd.util.Data

abstract class Algorithm[Input, Output](val conf: AlgorithmConf) {

  var runs = conf.runs

  val connector =
    if (conf.master != "") {
      MasterConnector(conf.master)
    } else {
      MasterConnector(
        port = Experiment.port,
        cacheSize = conf.cacheSize,
        storeType = conf.store)
    }

  val mutator = new Mutator(connector)

  var output: Output = null.asInstanceOf[Output]

  def adjust: Adjustable[Output]

  var mapCount = 0
  var reduceCount = 0

  def data: Data[Input]

  var naiveLoadElapsed: Long = 0

  protected def generateNaive()

  protected def runNaive(): Any

  protected def loadInitial() {
    data.load()
  }

  protected def checkOutput(output: Output): Boolean

  def run(): Map[String, Double] = {
    val results = Map[String, Double]()

    // Naive run.
    if (Experiment.verbosity > 1) {
      println("Naive load.")
    }

    val beforeLoad = System.currentTimeMillis()
    generateNaive()
    naiveLoadElapsed = System.currentTimeMillis() - beforeLoad
    results("naive-load") = naiveLoadElapsed

    if (Experiment.verbosity > 1) {
      println("Naive run.")
    }

    val gcBefore = getGCTime()
    val before = System.currentTimeMillis()
    runNaive()
    results("naive") = System.currentTimeMillis() - before
    results("naive-nogc") = results("naive") - (getGCTime() - gcBefore)

    // Initial run.
    val (initialTime, initialLoad, initialNoGC) = initial()
    results("initial") = initialTime
    results("initial-load") = initialLoad
    results("initial-nogc") = initialNoGC

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
    runs = List("naive", "initial")

    while (data.hasUpdates()) {
      val (updateTime, updateLoad, updateNoGC) = update()
      results(r + "") = updateTime
      results(r + "-load") = updateLoad
      results(r + "-nogc") = updateNoGC
      runs :+= r + ""
      r += 1
    }

    Experiment.confs("runs") = runs

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

  def initial() = {
    if (Experiment.verbosity > 1) {
      println("Initial load.")
    }

    val beforeLoad = System.currentTimeMillis()
    loadInitial()
    val loadElapsed = naiveLoadElapsed + System.currentTimeMillis() - beforeLoad

    if (Experiment.verbosity > 1) {
      println("Initial run.")
    }

    val gcBefore = getGCTime()
    val before = System.currentTimeMillis()
    output = mutator.run[Output](adjust)
    val elapsed = System.currentTimeMillis() - before
    val noGCElapsed = elapsed - (getGCTime() - gcBefore)

    if (Experiment.check) {
      assert(checkOutput(output))
    }

    (elapsed, loadElapsed, noGCElapsed)
  }

  def update() = {
    if (Experiment.verbosity > 1) {
      println("Updating")
    }

    val beforeLoad = System.currentTimeMillis()
    data.update()
    val loadElapsed = System.currentTimeMillis() - beforeLoad

    if (Experiment.verbosity > 1) {
      println("Running change propagation.")
    }

    val gcBefore = getGCTime()
    val before = System.currentTimeMillis()
    mutator.propagate()
    val elapsed = System.currentTimeMillis() - before
    val noGCElapsed = elapsed - (getGCTime() - gcBefore)

    if (Experiment.check) {
      assert(checkOutput(output))
    }

    (elapsed, loadElapsed, noGCElapsed)
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
