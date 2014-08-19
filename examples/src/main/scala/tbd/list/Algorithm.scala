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

import scala.collection.{GenIterable, GenMap}
import scala.collection.mutable.Map

import tbd.{Adjustable, Mutator}
import tbd.datastore.Data
import tbd.list.ListConf
import tbd.master.Main

abstract class Algorithm[Input, Output](_conf: Map[String, _],
    _listConf: ListConf) extends Adjustable[Output] {
  val conf = _conf
  val listConf = _listConf

  val count = conf("counts").asInstanceOf[String].toInt
  val cacheSize = conf("cacheSizes").asInstanceOf[String].toInt
  val chunkSize = conf("chunkSizes").asInstanceOf[String].toInt
  val mutations = conf("mutations").asInstanceOf[Array[String]]
  val partition = conf("partitions").asInstanceOf[String].toInt
  //val memoized = conf("memoized") == "true"
  val store = conf("store").asInstanceOf[String]

  val main = new Main(store, cacheSize)
  val mutator = new Mutator(main)

  var output: Output = null.asInstanceOf[Output]

  var mapCount = 0
  var reduceCount = 0

  def data: Data[Input]

  var naiveLoadElapsed: Long = 0

  def naive(): (Long, Long) = {
    if (Experiment.verbose) {
      println("Naive load.")
    }

    val beforeLoad = System.currentTimeMillis()
    generateNaive()
    naiveLoadElapsed = System.currentTimeMillis() - beforeLoad

    if (Experiment.verbose) {
      println("Naive run.")
    }

    val before = System.currentTimeMillis()
    runNaive()
    val elapsed = System.currentTimeMillis() - before

    (elapsed, naiveLoadElapsed)
  }

  protected def generateNaive()

  protected def runNaive(): Any

  def initial(): (Long, Long) = {
    if (Experiment.verbose) {
      println("Initial load.")
    }

    val beforeLoad = System.currentTimeMillis()
    data.load()
    val loadElapsed = naiveLoadElapsed + System.currentTimeMillis() - beforeLoad

    if (!Experiment.check) {
      data.clearValues()
    }

    if (Experiment.verbose) {
      println("Initial run.")
    }

    val before = System.currentTimeMillis()
    output = mutator.run[Output](this)
    val elapsed = System.currentTimeMillis() - before

    if (Experiment.check) {
      assert(checkOutput(data.table, output))
    }

    (elapsed, loadElapsed)
  }

  protected def checkOutput(table: Map[Int, Input], output: Output): Boolean

  def update(count: Double): (Long, Long) = {
    if (Experiment.verbose) {
      println("Updating " + count)
    }

    var i = 0
    val beforeLoad = System.currentTimeMillis()
    while (i < count) {
      i += 1
      data.update()
    }
    val loadElapsed = System.currentTimeMillis() - beforeLoad

    if (Experiment.verbose) {
      println("Running change propagation.")
    }
    val before = System.currentTimeMillis()
    mutator.propagate()
    val elapsed = System.currentTimeMillis() - before

    if (Experiment.check) {
      assert(checkOutput(data.table, output))
    }

    (elapsed, loadElapsed)
  }

  def shutdown() {
    mutator.shutdown()
    main.shutdown()
  }
}
