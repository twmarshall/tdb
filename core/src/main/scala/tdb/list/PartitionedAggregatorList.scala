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
package tdb.list

import akka.actor.ActorRef
import java.io.Serializable
import scala.collection.mutable.Buffer

import tdb.{Context, Mod, Mutator}

class PartitionedAggregatorList[T, U]
    (partitions: Buffer[AggregatorList[T, U]], conf: ListConf)
  extends AdjustableList[T, U] with Serializable {

  def filter(pred: ((T, U)) => Boolean)
      (implicit c: Context): AdjustableList[T, U] = ???

  def flatMap[V, W](f: ((T, U)) => Iterable[(V, W)])
      (implicit c: Context): AdjustableList[V, W] = ???

  def join[V]
      (that: AdjustableList[T, V], condition: ((T, V), (T, U)) => Boolean)
      (implicit c: Context): AdjustableList[T, (U, V)] = ???

  def map[V, W](f: ((T, U)) => (V, W))
      (implicit c: Context): AdjustableList[V, W] = ???

  def reduce(f: ((T, U), (T, U)) => (T, U))
      (implicit c: Context): Mod[(T, U)] = ???

  /* Meta functions */
  def toBuffer(mutator: Mutator): Buffer[(T, U)] = {
    val buf = Buffer[(T, U)]()

    for (partition <- partitions) {
      buf ++= partition.toBuffer(mutator)
    }

    buf
  }
}
