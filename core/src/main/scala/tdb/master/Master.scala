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
package tdb.master

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.pattern.{ask, pipe}
import java.io.File
import scala.collection.mutable
import scala.collection.mutable.{Buffer, Map}
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

import tdb.{Adjustable, TDB}
import tdb.Constants._
import tdb.datastore.{Datastore, ModifierActor}
import tdb.messages._
import tdb.list._
import tdb.stats.Stats
import tdb.util._
import tdb.worker.{Task, Worker, WorkerInfo}

object Master {
  def props(): Props = Props(classOf[Master])
}

class Master extends Actor with ActorLogging {
  import context.dispatcher

  Stats.registeredWorkers.clear()

  log.info("Master launched.")

  private val scheduler = new Scheduler()

  private val workers = Map[WorkerId, ActorRef]()

  private val tasks = mutable.Set[TaskInfo]()

  private val datastoreRefs = Map[DatastoreId, ActorRef]()

  // Maps mutatorIds to the Task the mutator's computation was launched on.
  private val rootTasks = Map[Int, ActorRef]()

  private var nextMutatorId = 0

  private var nextWorkerId: WorkerId = 0

  private var nextTaskId: TaskId = 1

  // The next Worker to schedule a Task on.
  private var nextWorker: WorkerId = 0

  // The total number of cores reported by all registered Workers.
  private var totalCores = 0

  private var nextInputId: InputId = 0

  def receive = {
    // Worker
    case RegisterWorkerMessage(_workerInfo: WorkerInfo) =>
      val workerId = nextWorkerId
      val workerInfo = _workerInfo.copy(workerId = workerId)
      sender ! workerInfo

      totalCores += workerInfo.numCores

      val workerRef = AkkaUtil.getActorFromURL(
        workerInfo.worker, context.system)
      val datastoreRef = AkkaUtil.getActorFromURL(
        workerInfo.datastore, context.system)

      context.watch(workerRef)
      scheduler.addWorker(workerRef)

      log.info("Registering worker at " + workerRef)

      workers(workerId) = workerRef
      datastoreRefs(workerId) = datastoreRef

      nextWorkerId = incrementWorkerId(nextWorkerId)

      Stats.registeredWorkers += workerInfo

      for ((thatWorkerId, thatDatastoreRef) <- datastoreRefs) {
        thatDatastoreRef ! RegisterDatastoreMessage(workerId, datastoreRef)
        datastoreRef ! RegisterDatastoreMessage(thatWorkerId, thatDatastoreRef)
      }

    case ScheduleTaskMessage
        (parent: ActorRef, workerId: WorkerId, adjust: Adjustable[_]) =>
      val taskId = nextTaskId
      nextTaskId += 1

      val workerRef =
        if (workerId == -1) {
          scheduler.nextWorker()
        } else {
          workers(workerId)
        }

      val f = (workerRef ? CreateTaskMessage(taskId, parent))
        .mapTo[ActorRef]
      val respondTo = sender
      f.onComplete {
        case Success(taskRef) =>
          val taskInfo = new TaskInfo(
            taskId, taskRef, adjust, parent, workerRef)
          tasks += taskInfo
          val outputFuture = taskRef ? RunTaskMessage(adjust)

          outputFuture.onComplete {
            case Success(output) =>
              respondTo ! (taskRef, output)
            case Failure(e) =>
              e.printStackTrace()
          }
        case Failure(e) =>
          e.printStackTrace()
      }

    // Mutator
    case RegisterMutatorMessage =>
      log.info("Registering mutator " + nextMutatorId)
      sender ! nextMutatorId
      nextMutatorId += 1

    case RunMutatorMessage(adjust: Adjustable[_], mutatorId: Int) =>
      log.info("Starting initial run for mutator " + mutatorId)

      val taskId = nextTaskId
      nextTaskId += 1

      val workerRef = scheduler.nextWorker()
      val taskRefFuture = workerRef ? CreateTaskMessage(taskId, workerRef)
      val taskRef = Await.result(taskRefFuture.mapTo[ActorRef], DURATION)

      (taskRef ? RunTaskMessage(adjust)) pipeTo sender

      rootTasks(mutatorId) = taskRef

    case PropagateMutatorMessage(mutatorId: Int) =>
      log.info("Initiating change propagation for mutator " + mutatorId)

      (rootTasks(mutatorId) ? PropagateTaskMessage) pipeTo sender

    case GetMutatorDDGMessage(mutatorId: Int) =>
      (rootTasks(mutatorId) ? GetTaskDDGMessage) pipeTo sender

    case ShutdownMutatorMessage(mutatorId: Int) =>
      log.info("Shutting down mutator " + mutatorId)

      for ((workerId, workerRef) <- workers) {
        workerRef ! ClearMessage
      }

      Stats.clear()
      if (rootTasks.contains(mutatorId)) {
        Await.result(rootTasks(mutatorId) ? ClearModsMessage, DURATION)

        val f = rootTasks(mutatorId) ? ShutdownTaskMessage
        rootTasks -= mutatorId

        Await.result(f, DURATION)
      }

      for ((workerId, datastoreRef) <- datastoreRefs) {
        datastoreRef ! ClearMessage()
      }

      sender ! "done"

    // Datastore
    case CreateModMessage(value: Any) =>
      (scheduler.nextWorker() ? CreateModMessage(value)) pipeTo sender

    case CreateModMessage(null) =>
      (scheduler.nextWorker() ? CreateModMessage(null)) pipeTo sender

    case GetModMessage(modId: ModId, null) =>
      val datastoreId = getDatastoreId(modId)
      (datastoreRefs(datastoreId) ? GetModMessage(modId, null)) pipeTo sender

    case UpdateModMessage(modId: ModId, value: Any, null) =>
      val datastoreRef = datastoreRefs(getDatastoreId(modId))
      (datastoreRef ? UpdateModMessage(modId, value, null)) pipeTo sender

    case UpdateModMessage(modId: ModId, null, null) =>
      val datastoreRef = datastoreRefs(getDatastoreId(modId))
      (datastoreRef ? UpdateModMessage(modId, null, null)) pipeTo sender

    case CreateListMessage(_conf: ListConf) =>
      val futures = Buffer[Future[(Map[DatastoreId, ActorRef], ObjHasher[ActorRef])]]()

      val inputId = nextInputId
      nextInputId += 1

      val conf =
        if (_conf.partitions == 0) {
          _conf.clone(partitions = totalCores, inputId = inputId)
        } else {
          _conf.clone(inputId = inputId)
        }

      var index = 0
      for ((workerId, workerRef) <- workers) {
        val future = {
          val message = CreateListIdsMessage(conf, index, workers.size)
          workerRef ? message
        }

        futures += future.mapTo[(Map[DatastoreId, ActorRef], ObjHasher[ActorRef])]
        index += 1
      }

      var hasher: ObjHasher[ActorRef] = null
      Await.result(Future.sequence(futures), DURATION).foreach {
        case (newDatastores, thisHasher) =>
          if (hasher == null) {
            hasher = thisHasher
          } else {
            hasher = ObjHasher.combineHashers(hasher, thisHasher)
          }
          datastoreRefs ++= newDatastores
      }
      assert(hasher.isComplete())

      val input = conf match {
        case aggregatorConf: AggregatorListConf =>
          new AggregatorInput(inputId, hasher, aggregatorConf, workers.values)

        case SimpleListConf(_, _, 1, _, false, _, _) =>
          new HashPartitionedDoubleListInput(
            inputId, hasher, conf, workers.values)

        case SimpleListConf(_, _, _, _, false, _, _) =>
          new HashPartitionedDoubleChunkListInput(
            inputId, hasher, conf, workers.values)
        case columnConf: ColumnListConf =>
          if (columnConf.chunkSize > 1)
            new ColumnChunkListInput(inputId, hasher, columnConf)
          else
            new ColumnListInput(inputId, hasher, columnConf)
        case _ => ???
      }

      sender ! input

    case Terminated(deadWorker: ActorRef) =>
      scheduler.removeWorker(deadWorker)

      val newTasks = mutable.Set[TaskInfo]()
      log.warning("Received Terminated for " + deadWorker)
      tasks.map {
        case info =>
          if (info.workerRef == deadWorker) {
            log.warning("Relaunching " + info)
            val workerRef = scheduler.nextWorker()

            val f = (workerRef ? CreateTaskMessage(info.id, info.parent))
              .mapTo[ActorRef]

            f.onComplete {
              case Success(taskRef) =>
                val taskInfo = new TaskInfo(
                  info.id, taskRef, info.adjust, info.parent, workerRef)
                newTasks += taskInfo
                val outputFuture = taskRef ? RunTaskMessage(info.adjust)
              case Failure(e) =>
                e.printStackTrace()
            }
          }
      }

    case x =>
      log.warning("Master actor received unhandled message " +
                  x + " from " + sender + " " + x.getClass)
  }
}
