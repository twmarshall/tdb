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
import tdb.Constants._
import tdb.TDB._

class DoubleChunkList[T, U]
    (val head: Mod[DoubleChunkListNode[T, U]],
     conf: ListConf,
     val sorted: Boolean = false,
     val datastoreId: TaskId = -1)
  extends AdjustableList[T, U] with Serializable {

  override def chunkMap[V, W](f: Iterable[(T, U)] => (V, W))
      (implicit c: Context): DoubleList[V, W] = {
    val memo = new Memoizer[Mod[DoubleListNode[V, W]]]()

    new DoubleList(
      mod {
        read(head) {
          case null => write[DoubleListNode[V, W]](null)
          case node => node.chunkMap(f, memo)
        }
      }, false, datastoreId
    )
  }

  def filter(pred: ((T, U)) => Boolean)
      (implicit c: Context): DoubleChunkList[T, U] = ???

  def flatMap[V, W](f: ((T, U)) => Iterable[(V, W)])
      (implicit c: Context): DoubleChunkList[V, W] = {
    val memo = new Memoizer[Mod[DoubleChunkListNode[V, W]]]()

    new DoubleChunkList(
      mod {
        read(head) {
          case null => write[DoubleChunkListNode[V, W]](null)
          case node => node.flatMap(f, memo)
        }
      }, conf, false, datastoreId
    )
  }

  override def foreach(f: ((T, U), Context) => Unit)
      (implicit c: Context): Unit = {
    val memo = new Memoizer[Unit]()

    readAny(head) {
      case null =>
      case node =>
        memo(node) {
          node.foreach(f, memo)
        }
    }
  }

  override def foreachChunk(f: (Iterable[(T, U)], Context) => Unit)
      (implicit c: Context): Unit = {
    val memo = new Memoizer[Unit]()

    readAny(head) {
      case null =>
      case node =>
        memo(node) {
          node.foreachChunk(f, memo)
        }
    }
  }

  def join[V](_that: AdjustableList[T, V], condition: ((T, V), (T, U)) => Boolean)
      (implicit c: Context): DoubleChunkList[T, (U, V)] = ???

  def map[V, W](f: ((T, U)) => (V, W))
      (implicit c: Context): DoubleChunkList[V, W] = {
    val memo = new Memoizer[Mod[DoubleChunkListNode[V, W]]]()

    new DoubleChunkList(
      mod {
        read(head) {
          case null => write[DoubleChunkListNode[V, W]](null)
          case node => node.map(f, memo)
        }
      }, conf, false, datastoreId
    )
  }

  override def mapValues[V](f: U => V)
      (implicit c: Context): DoubleChunkList[T, V] = {
    val memo = new Memoizer[Changeable[DoubleChunkListNode[T, V]]]()

    new DoubleChunkList(
      mod {
        read(head) {
          case null => write[DoubleChunkListNode[T, V]](null)
          case node => node.mapValues(f, memo)
        }
      }, conf, sorted, datastoreId
    )
  }

  def merge(that: DoubleChunkList[T, U])
      (implicit c: Context,
       ordering: Ordering[T]): DoubleChunkList[T, U] = ???

  def reduce(f: ((T, U), (T, U)) => (T, U))
      (implicit c: Context): Mod[(T, U)] = {
    chunkMap(_.reduce(f)).reduce(f)
  }

  def toBuffer(mutator: Mutator): Buffer[(T, U)] = {
    val buf = Buffer[(T, U)]()
    var node = mutator.read(head)
    while (node != null) {
      buf ++= mutator.read(node.chunkMod)
      node = mutator.read(node.nextMod)
    }

    buf
  }

  override def equals(that: Any): Boolean = {
    that match {
      case thatList: DoubleChunkList[T, U] => head == thatList.head
      case _ => false
    }
  }

  override def hashCode() = head.hashCode()

  override def toString: String = {
    head.toString
  }
}
