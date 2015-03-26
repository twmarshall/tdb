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
package tdb

import akka.actor.ActorRef
import akka.pattern.ask
import java.io.Serializable
import scala.concurrent.Await

import tdb.Constants._
import tdb.messages._
import tdb.worker.Task

class Parizer[T](one: Context => T) extends Serializable {
  def and[U](two: Context => U)(implicit c: Context): (T, U) = {
    TDB.innerPar(one)(two)(c)
  }
}
