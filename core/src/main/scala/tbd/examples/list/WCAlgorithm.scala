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
import scala.collection.immutable.HashMap
import scala.collection.mutable.Map
import scala.collection.parallel.{ForkJoinTaskSupport, ParIterable}
import scala.concurrent.forkjoin.ForkJoinPool

import tbd._
import tbd.Constants._
import tbd.datastore.StringData
import tbd.list._
import tbd.TBD._
import tbd.util._

object WCAlgorithm {
  def wordcount(s: String): HashMap[String, Int] = {
    HashMap(mutableWordcount(s).toSeq: _*)
  }

  def mutableWordcount(s: String, counts: Map[String, Int] = Map[String, Int]())
      : Map[String, Int] = {
    for (word <- s.split("\\W+")) {
      if (counts.contains(word)) {
        counts(word) += 1
      } else {
        counts(word) = 1
      }
    }

    counts
  }

  def countReduce(s: String, counts: Map[String, Int]): Map[String, Int] = {
    for (word <- s.split("\\W+")) {
      if (counts.contains(word)) {
        counts(word) += 1
      } else {
        counts(word) = 1
      }
    }

    counts
  }

  def reduce(map1: HashMap[String, Int], map2: HashMap[String, Int])
      : HashMap[String, Int] = {
    map1.merged(map2)({ case ((k, v1),(_, v2)) => (k, v1 + v2)})
  }

  def mutableReduce(map1: Map[String, Int], map2: Map[String, Int])
      : Map[String, Int] = {
    val counts = map2.clone()
    for ((key, value) <- map1) {
      if (counts.contains(key)) {
        counts(key) += map1(key)
      } else {
        counts(key) = map1(key)
      }
    }
    counts
  }

  def mapper(pair: (Int, String)) = {
    val mapPtr = OffHeapMap.create()
    for (word <- pair._2.split("\\W+")) {
      OffHeapMap.increment(mapPtr, word, 1)
    }

    (pair._1, mapPtr)
  }

  def reducer
      (pair1: (Int, Pointer),
       pair2: (Int, Pointer)) = {
    (pair1._1, OffHeapMap.merge(pair1._2, pair2._2))
  }
}

class WCAdjust(list: AdjustableList[Int, String])
  extends Adjustable[Mod[(Int, Pointer)]] {

  def run(implicit c: Context) = {
    val counts = list.map(WCAlgorithm.mapper)
    counts.reduce(WCAlgorithm.reducer)
  }
}

class WCAlgorithm(_conf: Map[String, _], _listConf: ListConf)
    extends Algorithm[String, Mod[(Int, Pointer)]](_conf, _listConf) {
  val input = mutator.createList[Int, String](listConf)

  val data = new StringData(input, count, mutations, Experiment.check)

  val adjust = new WCAdjust(input.getAdjustableList())

  var naiveTable: ParIterable[String] = _
  def generateNaive() {
    data.generate()
    naiveTable = Vector(data.table.values.toSeq: _*).par
    naiveTable.tasksupport =
      new ForkJoinTaskSupport(new ForkJoinPool(partitions * 2))
  }

  def runNaive() {
    naiveHelper(naiveTable)
  }

  private def naiveHelper(input: GenIterable[String] = naiveTable) = {
    input.aggregate(Map[String, Int]())((x, line) =>
      WCAlgorithm.countReduce(line, x), WCAlgorithm.mutableReduce)
  }

  def checkOutput(
      table: Map[Int, String],
      output: Mod[(Int, Pointer)]) = {
    val answer = naiveHelper(table.values).toBuffer.sortWith(_._1 < _._1)
    val out = OffHeapMap.toBuffer(mutator.read(output)._2).sortWith(_._1 < _._1)

    out == answer
  }
}

class ChunkWCAdjust(list: AdjustableList[Int, String])
  extends Adjustable[Mod[(Int, HashMap[String, Int])]] {

  def chunkMapper(chunk: Vector[(Int, String)]) = {
    var counts = Map[String, Int]()

    for (page <- chunk) {
      counts = WCAlgorithm.mutableWordcount(page._2, counts)
    }

    (0, HashMap(counts.toSeq: _*))
  }

  def chunkReducer(
      pair1: (Int, HashMap[String, Int]),
      pair2: (Int, HashMap[String, Int])) = {
    (pair1._1, WCAlgorithm.reduce(pair1._2, pair2._2))
  }

  def run(implicit c: Context): Mod[(Int, HashMap[String, Int])] = {
    val counts = list.chunkMap(chunkMapper)
    counts.reduce(chunkReducer)
  }
}

class ChunkWCAlgorithm(_conf: Map[String, _], _listConf: ListConf)
    extends Algorithm[String, Mod[(Int, HashMap[String, Int])]](_conf, _listConf) {
  val input = mutator.createList[Int, String](listConf)

  val data = new StringData(input, count, mutations, Experiment.check)

  val adjust = new ChunkWCAdjust(input.getAdjustableList())

  var naiveTable: ParIterable[String] = _
  def generateNaive() {
    data.generate()
    naiveTable = Vector(data.table.values.toSeq: _*).par
    naiveTable.tasksupport =
      new ForkJoinTaskSupport(new ForkJoinPool(partitions * 2))
  }

  def runNaive() {
    naiveHelper(naiveTable)
  }

  private def naiveHelper(input: GenIterable[String] = naiveTable) = {
    input.aggregate(Map[String, Int]())((x, line) =>
      WCAlgorithm.countReduce(line, x), WCAlgorithm.mutableReduce)
  }

  def checkOutput(
      table: Map[Int, String],
      output: Mod[(Int, HashMap[String, Int])]) = {
    val answer = naiveHelper(table.values)
    mutator.read(output)._2 == answer
  }
}
