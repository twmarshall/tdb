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

import scala.collection.mutable

import tdb.Constants.TaskId

class Scheduler {

  private val workers = mutable.Buffer[TaskId]()

  def addWorker(workerId: TaskId) {
    workers += workerId
  }

  def removeWorker(workerId: TaskId) {
    workers -= workerId
  }

  def nextWorker(): TaskId = {
    val w = workers.head
    workers -= w
    workers += w
    w
  }
}
