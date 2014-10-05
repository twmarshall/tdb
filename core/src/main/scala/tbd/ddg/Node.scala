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
package tbd.ddg

import akka.actor.ActorRef
import akka.pattern.ask
import scala.collection.mutable.MutableList
import scala.concurrent.Await

import tbd._
import tbd.Constants._
import tbd.master.Main
import tbd.messages._

object Node {
  var id = 0

  def getId(): Integer = {
    id = id + 1
    id
  }
}

abstract class Node {
  var timestamp: Timestamp = _
  var endTime: Timestamp = _

  var stacktrace =
    if (Main.debug)
      Thread.currentThread().getStackTrace()
    else
      null

  var updated = false

  val internalId = Node.getId()

  // The earliest epoch in which this node may be matched, if it is a MemoNode.
  // This is increased above the current epoch whenever the node is matched, so
  // that it won't be matched again in this round of change propagation.
  var matchableInEpoch = 0

  var currentMod: Mod[Any] = null

  var currentMod2: Mod[Any] = null

  override def equals(obj: Any): Boolean = {
    obj.isInstanceOf[Node] && obj.asInstanceOf[Node].timestamp == timestamp
  }
}

class MemoNode
    (val signature: Seq[Any],
     val memoizer: Memoizer[_]) extends Node {

  var value: Any = null
}

class ModNode(val modizer: Modizer[Any], val key: Any) extends Node

class ParNode
    (val workerRef1: ActorRef,
     val workerRef2: ActorRef) extends Node {

  var pebble1 = false
  var pebble2 = false

  def getFirstSubtree() = {
    Await.result(workerRef1 ? GetDDGMessage, DURATION).asInstanceOf[DDG]
  }

  def getSecondSubtree() = {
    Await.result(workerRef2 ? GetDDGMessage, DURATION).asInstanceOf[DDG]
  }
}

class ReadNode(val mod: Mod[Any], val reader: Any => Changeable[Any])
  extends Node

class RootNode(val id: String) extends Node

class WriteNode(val mod: Mod[Any], val mod2: Mod[Any]) extends Node
