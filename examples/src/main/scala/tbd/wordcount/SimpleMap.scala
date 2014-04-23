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
package tbd.examples.wordcount

import java.io.{BufferedInputStream, File, FileInputStream}

import scala.collection.mutable.ArrayBuffer

class ListNode[T](aValue: T, aNext: ListNode[T]) {
  val value = aValue
  val next = aNext
}

object SimpleMap {
  val chunks = ArrayBuffer[String]()
  def loadFile(chunkSize: Int) {
    val source = scala.io.Source.fromFile("input.txt")

    val bb = new Array[Byte](chunkSize)
    val bis = new BufferedInputStream(new FileInputStream(new File("input.txt")))
    var bytesRead = bis.read(bb, 0, chunkSize)

    while (bytesRead > 0) {
      chunks += new String(bb)
      bytesRead = bis.read(bb, 0, chunkSize)
    }
  }

  def run(count: Int, repeat: Int, chunkSize: Int): Long = {
    var i = 0

    loadFile(chunkSize)
    var tail: ListNode[String] = null
    for (i <- 0 to count) {
      tail = new ListNode(chunks.head, tail)
      chunks -= chunks.head
      if (chunks.size == 0) {
        loadFile(chunkSize)
      }
    }

    def map[T, U](lst: ListNode[T], f: T => U): ListNode[U] = {
      if (lst.next != null)
        new ListNode[U](f(lst.value), map(lst.next, f))
      else
        new ListNode[U](f(lst.value), null)
    }

    // Warmup run.
    val wc = new WC()
    val mapped = map(tail, MapAdjust.mapper((_: String)))

    var j = 0
    var total: Long = 0
    while (j < repeat) {
      val before = System.currentTimeMillis()

      val wc = new WC()
      val mapped = map(tail, MapAdjust.mapper((_: String)))

      total += System.currentTimeMillis() - before

      j += 1
    }

    println("smap\t" + count + "\t" + total / repeat)

    total / repeat
  }
}