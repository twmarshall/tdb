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
package tdb.datastore.cassandra

import com.datastax.driver.core.Cluster
import scala.concurrent.ExecutionContext
import scala.reflect.runtime.universe._

import tdb.Constants._
import tdb.datastore._
import tdb.util._
import tdb.worker.WorkerInfo

object CassandraStore {
  def setup(ip: String) {
    val cluster = Cluster.builder()
      .addContactPoint(ip)
      .build()

    val session = cluster.connect()
    session.execute(
      """CREATE KEYSPACE IF NOT EXISTS tdb WITH replication =
      {'class':'SimpleStrategy', 'replication_factor':2};""")

    session.execute(
      """DROP TABLE IF EXISTS tdb.mods;""")
    session.execute(
      """CREATE TABLE tdb.mods (key bigint, value blob, PRIMARY KEY(key));""")

    session.execute(
      """DROP TABLE IF EXISTS tdb.meta;""")
    session.execute(
      """CREATE TABLE tdb.meta (key int, value blob, PRIMARY KEY(key))""")

    session.close()
    cluster.close()
  }
}

class CassandraStore(val workerInfo: WorkerInfo)
    (implicit val ec: ExecutionContext) extends CachedStore {
  private var nextStoreId = 0

  private val session = workerInfo.cluster.connect()

  def createTable
      (name: String,
       keyType: String,
       valueType: String,
       range: HashRange,
       dropIfExists: Boolean): Int = {
    val id = nextStoreId
    nextStoreId += 1

    keyType match {
      case "String" =>
        valueType match {
          case "Double" =>
            tables(id) = new CassandraStringDoubleTable(
              session, convertName(name), range, dropIfExists)
          case "Int" =>
            tables(id) = new CassandraStringIntTable(
              session, convertName(name), range, dropIfExists)
          case "Long" =>
            tables(id) = new CassandraStringLongTable(
              session, convertName(name), range, dropIfExists)
          case "String" =>
            tables(id) = new CassandraStringStringTable(
              session, convertName(name), range, dropIfExists)
        }
      case "ModId" =>
        tables(id) = new CassandraModIdAnyTable(
          session, convertName(name), range, dropIfExists)
      case "Int" =>
        tables(id) = new CassandraIntAnyTable(
          session, convertName(name), range, dropIfExists)
    }

    id
  }

  private def convertName(name: String) =
    "tdb." + name.replace("/", "_").replace(".", "_").replace("-", "_")

  override def close() {
    super.close()

    session.close()
  }
}
