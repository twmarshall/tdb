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

import java.io.Serializable
import scala.collection.mutable.{Buffer, Map}

import tdb._
import tdb.TDB._

class HashPartitionedDoubleList[T, U]
    (_partitions: Buffer[DoubleList[T, U]])
  extends PartitionedDoubleList[T, U](_partitions) with Serializable {

  //println("new HashPartitionedDoubleList")

  override def reduceByKey(f: (U, U) => U)
      (implicit c: Context, o: Ordering[T]): AdjustableList[T, U] = {
    c.log.debug("HashPartitionedDoubleList.reduceByKey")
    def innerReduce(i: Int)(implicit c: Context): Buffer[DoubleList[T, U]] = {
      if (i < partitions.size) {
        val (mappedPartition, mappedRest) = parWithHint({
          c => partitions(i).reduceByKey(f)(c, o)
        }, partitions(i).datastoreId)({
          c => innerReduce(i + 1)(c)
        })

        mappedRest += mappedPartition
      } else {
        Buffer[DoubleList[T, U]]()
      }
    }

    new HashPartitionedDoubleList(innerReduce(0))
  }
}
