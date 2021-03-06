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
import akka.pattern.ask
import scala.collection.mutable.{Buffer, Map}
import scala.concurrent.{Await, Future}

import ColumnList._
import tdb.{Resolver, Traceable, TraceableBuffer}
import tdb.Constants._
import tdb.messages._
import tdb.util.ObjHasher

class ColumnListInput[T]
    (val inputId: InputId,
     val hasher: ObjHasher[TaskId],
     conf: ColumnListConf,
     masterRef: ActorRef)
  extends ListInput[T, Columns]
  with Traceable[(String, Iterable[(T, Any)]), (T, Iterable[String]), Iterable[Any]] {
  import scala.concurrent.ExecutionContext.Implicits.global

  protected val resolver = new Resolver(masterRef)

  def get
      (parameters: (T, Iterable[String]),
       nodeId: NodeId,
       taskRef: ActorRef): Iterable[Any] = {
    val datastoreId = hasher.getObj(parameters._1)
    val future = resolver.send(
      datastoreId, GetFromMessage(parameters, nodeId, taskRef))
    Await.result(future.mapTo[Iterable[Any]], DURATION)
  }

  def loadFile(fileName: String) = ???

  def put(key: T, value: Columns) = ???

  def asyncPut(key: T, value: Columns): Future[_] = ???

  def asyncPutAllIn(column: String, values: Iterable[(T, Any)]): Future[_] = {
    val hashedPut = hasher.hashAll(values)

    val futures = Buffer[Future[Any]]()
    for ((hash, buf) <- hashedPut) {
      if (buf.size > 0) {
        val datastoreId = hasher.objs(hash)
        futures += resolver.send(datastoreId, PutAllInMessage(column, buf))
      }
    }

    Future.sequence(futures)
  }

  def putIn(column: String, key: T, value: Any) = {
    Await.result(asyncPutIn(column, key, value), DURATION)
  }

  def asyncPutIn(column: String, key: T, value: Any): Future[_] = {
    val datastoreId = hasher.getObj(key)
    resolver.send(datastoreId, PutInMessage(column, key, value))
  }

  def get(key: T, taskRef: ActorRef): Columns = ???

  def remove(key: T, value: Columns) = ???

  def removeAll(values: Iterable[(T, Columns)]) = ???

  def asyncRemove(key: T, value: Columns): Future[_] = ???

  def load(data: Map[T, Columns]): Unit = ???

  def getAdjustableList(): AdjustableList[T, Columns] = {
    val adjustablePartitions = Buffer[ColumnList[T]]()

    for (datastoreId <- hasher.objs.values) {
      val future = resolver.send(datastoreId, GetAdjustableListMessage())
      adjustablePartitions +=
        Await.result(future.mapTo[ColumnList[T]], DURATION)
    }

    new PartitionedColumnList(adjustablePartitions, conf)
  }

  def getBuffer(): InputBuffer[T, Columns] = new ColumnBuffer(this, conf)

  def getTraceableBuffer() =
    new ColumnBuffer(this, conf)
}

class ColumnBuffer[T]
    (input: ColumnListInput[T], conf: ColumnListConf)
  extends InputBuffer[T, Columns]
  with TraceableBuffer[(String, Iterable[(T, Any)]), (T, Iterable[String]), Iterable[Any]] {

  val toPut = Map[String, Map[T, Any]]()

  def putAll(values: Iterable[(T, Columns)]) = ???

  def putAllIn(column: String, values: Iterable[(T, Any)]) = {
    if (!toPut.contains(column)) {
      toPut(column) =  Map[T, Any]()
    }

    conf.columns(column)._1 match {
      case c: AggregatedColumn =>
        for ((k, v) <- values) {
          if (toPut(column).contains(k)) {
            toPut(column)(k) = c.aggregator(toPut(column)(k), v)
          } else {
            toPut(column)(k) = v
          }
        }
      case _ => ???
    }
  }

  def putIn(parameters: (String, Iterable[(T, Any)])) = {
    val (column, values) = parameters

    if (!toPut.contains(column)) {
      toPut(column) =  Map[T, Any]()
    }

    conf.columns(column)._1 match {
      case c: AggregatedColumn =>
        for ((k, v) <-  values) {
          if (toPut(column).contains(k)) {
            toPut(column)(k) = c.aggregator(toPut(column)(k), v)
          } else {
            toPut(column)(k) = v
          }
        }
      case _ => ???
    }
  }

  def remove(parameters: Any) = {
    parameters match {
      case (column: String, values: Iterable[(T, Any)]) =>
        if (!toPut.contains(column)) {
          toPut(column) =  Map[T, Any]()
        }

        conf.columns(column)._1 match {
          case c: AggregatedColumn =>
            for ((k, v) <- values) {
              if (toPut(column).contains(k)) {
                toPut(column)(k) = c.deaggregator(toPut(column)(k), v)
              } else {
                toPut(column)(k) = c.deaggregator(c.initialValue, v)
              }
            }
          case _ => ???
        }
    }
  }

  def removeAll(values: Iterable[(T, Columns)]) = ???

  def flush() {
    flush(null, false)
  }

  def flush(resolver: Resolver, recovery: Boolean) {
    val futures = Buffer[Future[Any]]()
    for ((column, values) <- toPut) {
      futures += input.asyncPutAllIn(column, values)
    }

    toPut.clear()

    import scala.concurrent.ExecutionContext.Implicits.global
    Await.result(Future.sequence(futures), DURATION)
  }
}
