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
package thomasdb.list

import scala.collection.mutable.{Buffer, Map}

import thomasdb.Constants.WorkerId

class HashPartitionedDoubleChunkListInput[T, U]
    (workers: Map[WorkerId, Buffer[DoubleChunkListInput[T, U]]],
     conf: ListConf)
  extends ListInput[T, U] with java.io.Serializable {

  val workerIds = workers.keys.toBuffer

  val partitions = workers.flatMap {
    case (workerId, buf) => buf
  }.toBuffer

  val nextPartition = workerIds.map(_ => 0)

  val numWorkers = workerIds.size

  val numPartitions = partitions.size

  private def getPartition(key: T) = {
    partitions(key.hashCode() % numPartitions)
  }

  def put(key: T, value: U) = {
    getPartition(key).put(key, value)
  }

  def asyncPut(key: T, value: U) = {
    getPartition(key).asyncPut(key, value)
  }

  def update(key: T, value: U) = {
    getPartition(key).update(key, value)
  }

  def remove(key: T, value: U) = {
    getPartition(key).remove(key, value)
  }

  def load(data: Map[T, U]) = {
    for ((key, value) <- data) {
      getPartition(key).put(key, value)
    }
  }

  def putAfter(key: T, newPair: (T, U)) = ???

  def getAdjustableList(): AdjustableList[T, U] = {
    val adjustablePartitions = Map[WorkerId, Buffer[DoubleChunkList[T, U]]]()
    for ((workerId, partitions) <- workers) {
      adjustablePartitions(workerId) = Buffer[DoubleChunkList[T, U]]()
      for (partition <- partitions) {
        adjustablePartitions(workerId) += partition.getAdjustableList()
      }
    }

    new HashPartitionedDoubleChunkList(adjustablePartitions, conf)
  }
}
