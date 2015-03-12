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
package tdb.datastore

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern.pipe
import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

import tdb.Constants._
import tdb.list._
import tdb.messages._
import tdb.worker.WorkerInfo
import tdb.util.HashRange

object ModifierActor {
  def props(conf: ListConf, workerInfo: WorkerInfo, datastoreId: DatastoreId): Props =
    Props(classOf[ModifierActor], conf, workerInfo, datastoreId)
}

class ModifierActor(conf: ListConf, workerInfo: WorkerInfo, datastoreId: DatastoreId)
  extends Actor with ActorLogging {
  import context.dispatcher

  private val datastore = new Datastore(workerInfo, log, datastoreId)

  val listId = ""

  val modifier = conf match {
    case conf: AggregatorListConf[_] =>
      if (conf.chunkSize == 1)
        new AggregatorListModifier(listId, datastore, self, conf)
      else
        new AggregatorChunkListModifier(listId, datastore, self, conf)
    case conf: ColumnListConf =>
      new ColumnListModifier(datastore, conf)
    case conf: ListConf =>
      if (conf.chunkSize == 1)
        new DoubleListModifier(datastore)
      else
        new DoubleChunkListModifier(datastore, conf)
  }

  def receive = {
    case CreateModMessage(value: Any) =>
      sender ! datastore.createMod(value)

    case CreateModMessage(null) =>
      sender ! datastore.createMod(null)

    case GetModMessage(modId: ModId, taskRef: ActorRef) =>
      val respondTo = sender
      datastore.getMod(modId, taskRef).onComplete {
        case Success(v) =>
          if (v == null) {
            respondTo ! NullMessage
          } else {
            respondTo ! v
          }
        case Failure(e) => e.printStackTrace()
      }

      datastore.addDependency(modId, taskRef)

    case GetModMessage(modId: ModId, null) =>
      val respondTo = sender
      datastore.getMod(modId, null).onComplete {
        case Success(v) =>
          if (v == null) {
            respondTo ! NullMessage
          } else {
            respondTo ! v
          }
        case Failure(e) => e.printStackTrace()
      }

    case UpdateModMessage(modId: ModId, value: Any, task: ActorRef) =>
      datastore.updateMod(modId, value, task) pipeTo sender

    case UpdateModMessage(modId: ModId, value: Any, null) =>
      datastore.updateMod(modId, value, null) pipeTo sender

    case UpdateModMessage(modId: ModId, null, task: ActorRef) =>
      datastore.updateMod(modId, null, task) pipeTo sender

    case UpdateModMessage(modId: ModId, null, null) =>
      datastore.updateMod(modId, null, null) pipeTo sender

    case RemoveModsMessage(modIds: Iterable[ModId], taskRef: ActorRef) =>
      datastore.removeMods(modIds, taskRef) pipeTo sender

    case LoadPartitionsMessage
        (fileName: String,
         numWorkers: Int,
         workerIndex: Int,
         partitions: Int) =>
      log.debug("LoadPartitionsMessage")
      val range = new HashRange(
        workerIndex * partitions,
        (workerIndex + 1) * partitions,
        numWorkers * partitions)

      datastore.loadPartitions(fileName, range)

      sender ! "done"

    case GetAdjustableListMessage(listId: String) =>
      sender ! modifier.getAdjustableList()

    case ToBufferMessage(listId: String) =>
      sender ! modifier.toBuffer()

    case PutMessage(listId: String, key: Any, value: Any) =>
      // This solves a bug where sometimes deserialized Scala objects show up as
      // null in matches. We should figure out a better way of solving this.
      key.toString
      value.toString

      val futures = mutable.Buffer[Future[Any]]()
      futures += modifier.put(key, value)
      futures += datastore.informDependents(listId, key)
      Future.sequence(futures) pipeTo sender

    case PutAllMessage(listId: String, values: Iterable[(Any, Any)]) =>
      val futures = mutable.Buffer[Future[Any]]()
      for ((key, value) <- values) {
        futures += modifier.put(key, value)
        futures += datastore.informDependents(listId, key)
      }
      Future.sequence(futures) pipeTo sender

    case PutInMessage(listId: String, key: Any, column: String, value: Any) =>
      modifier.putIn(column, key, value) pipeTo sender

    case GetMessage(listId: String, key: Any, taskRef: ActorRef) =>
      sender ! modifier.get(key)
      datastore.addKeyDependency(listId, key, taskRef)

    case RemoveMessage(listId: String, key: Any, value: Any) =>
      val futures = mutable.Buffer[Future[Any]]()
      futures += modifier.remove(key, value)
      futures += datastore.informDependents(listId, key)
      Future.sequence(futures) pipeTo sender

    case RemoveAllMessage(listId: String, values: Iterable[(Any, Any)]) =>
      val futures = mutable.Buffer[Future[Any]]()
      for ((key, value) <- values) {
        futures += modifier.remove(key, value)
        futures += datastore.informDependents(listId, key)
      }
      Future.sequence(futures) pipeTo sender

    case RegisterDatastoreMessage(workerId: WorkerId, datastoreRef: ActorRef) =>
      datastore.datastores(workerId) = datastoreRef

    case ClearMessage() =>
      datastore.clear()

    case x => log.warning("Received unhandled message " + x + " from " + sender)
  }
}
