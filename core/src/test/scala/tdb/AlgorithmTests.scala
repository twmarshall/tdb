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
package tdb.examples.test

import org.scalatest._

import tdb.examples._

class AlgorithmTests extends FlatSpec with Matchers {
  Experiment.verbosity = 0
  Experiment.check = true
  Experiment.port = 2553

  val defaults = Array("--verbosity", "0", "--envHomePath", "asdf")

  val intensity = sys.env.getOrElse("TDB_INTENSITY", 10)

  "MapTest" should "run map successfully." in {
    val conf = new ExperimentConf(
      Array("--algorithms", "map",
            "--chunkSizes", "1",
            "--counts", intensity.toString,
            "--files", "input.txt",
            "--partitions", "1", "4") ++ defaults)

    Experiment.run(conf)
  }

  "PageRankTest" should "run page rank successfully." in {
    val conf = new ExperimentConf(Array(
      "--algorithms", "pgrank",
      "--chunkSizes", "1",
      "--files", "graph100.txt",
      "--partitions", "1") ++ defaults)

    Experiment.run(conf)
  }

  "WordcountTest" should "run wordcount successfully." in {
    val conf = new ExperimentConf(Array(
      "--algorithms", "wc",
      "--chunkSizes", "1", "4",
      "--counts", intensity.toString,
      "--partitions", "1", "4") ++ defaults)

    Experiment.run(conf)
  }
}

