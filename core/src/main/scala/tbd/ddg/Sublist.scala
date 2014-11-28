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

import tbd.MemoryAllocator
import tbd.Constants._

object Sublist {
  private val sublists = scala.collection.mutable.Map[Pointer, Sublist]()

  private val lock = new java.util.concurrent.locks.ReentrantLock()

  def getSublist(ptr: Pointer): Sublist = {
    lock.lock()
    val t = sublists(ptr)
    lock.unlock()
    t
  }

  def addSublist(ptr: Pointer, sublist: Sublist) {
    lock.lock()
    sublists(ptr) = sublist
    lock.unlock()
  }

  val idOffset = 0
  val sizeOffset = idOffset + 4
  val nextSubOffset = sizeOffset + 4
  val previousSubOffset = nextSubOffset + pointerSize
  val baseOffset = previousSubOffset + pointerSize

  def create(id: Int, nextSub: Pointer): Pointer = {
    val size = 4 * 2 + pointerSize * 3
    val ptr = MemoryAllocator.allocate(size)

    MemoryAllocator.putInt(ptr + idOffset, id)
    MemoryAllocator.putInt(ptr + sizeOffset, 0)
    MemoryAllocator.putPointer(ptr + nextSubOffset, nextSub)
    MemoryAllocator.putPointer(ptr + previousSubOffset, -1)
    MemoryAllocator.putPointer(ptr + baseOffset, -1)

    ptr
  }

  def getId(ptr: Pointer): Int = {
    MemoryAllocator.getInt(ptr + idOffset)
  }

  def setId(ptr: Pointer, newId: Int) {
    MemoryAllocator.putInt(ptr + idOffset, newId)
  }

  def getSize(ptr: Pointer): Int = {
    MemoryAllocator.getInt(ptr + sizeOffset)
  }

  def setSize(ptr: Pointer, newSize: Int) {
    MemoryAllocator.putInt(ptr + sizeOffset, newSize)
  }

  def getNextSub(ptr: Pointer): Pointer = {
    MemoryAllocator.getPointer(ptr + nextSubOffset)
  }

  def setNextSub(ptr: Pointer, newNextSub: Pointer) {
    MemoryAllocator.putPointer(ptr + nextSubOffset, newNextSub)
  }

  def getPreviousSub(ptr: Pointer): Pointer = {
    MemoryAllocator.getPointer(ptr + previousSubOffset)
  }

  def getBasePtr(thisPtr: Pointer): Pointer = {
    MemoryAllocator.getPointer(thisPtr + baseOffset)
  }

  def setBasePtr(thisPtr: Pointer, newBasePtr: Pointer) {
    MemoryAllocator.putPointer(thisPtr + baseOffset, newBasePtr)
  }

  def after(thisPtr: Pointer, t: Pointer, nodePtr: Pointer): Pointer = {
    val thisList = getSublist(thisPtr)

    val basePtr = getBasePtr(thisPtr)
    val previousPtr =
      if (t == -1) {
        basePtr
      } else {
        t
      }

    val newTimePtr =
      if (Timestamp.getNextTime(previousPtr) == basePtr) {
        Timestamp.create(thisPtr, Timestamp.getTime(previousPtr) + 1,
                         basePtr, previousPtr, nodePtr)
      } else {
        val nextTimePtr = Timestamp.getNextTime(previousPtr)
        val newTime = (Timestamp.getTime(previousPtr) +
                       Timestamp.getTime(nextTimePtr)) / 2

        Timestamp.create(thisPtr, newTime, nextTimePtr, previousPtr, nodePtr)
      }

    Timestamp.setNextTime(previousPtr, newTimePtr)
    Timestamp.setPreviousTime(Timestamp.getNextTime(newTimePtr), newTimePtr)
    Sublist.setSize(thisPtr, Sublist.getSize(thisPtr) + 1)

    newTimePtr
  }

  def append(thisPtr: Pointer, nodePtr: Pointer): Pointer = {
    val thisList = getSublist(thisPtr)
    val basePtr = getBasePtr(thisPtr)

    val previousPtr = Timestamp.getPreviousTime(basePtr)
    val newTimePtr = Timestamp.create(
      thisPtr,
      Timestamp.getTime(previousPtr) + 1,
      basePtr,
      previousPtr,
      nodePtr)

    Timestamp.setNextTime(previousPtr, newTimePtr)
    Timestamp.setPreviousTime(Timestamp.getNextTime(newTimePtr), newTimePtr)
    Sublist.setSize(thisPtr, Sublist.getSize(thisPtr) + 1)

    newTimePtr
  }

  def remove(thisPtr: Pointer, toRemove: Pointer) {
    val thisList = getSublist(thisPtr)

    val previousPtr = Timestamp.getPreviousTime(toRemove)
    val nextPtr = Timestamp.getNextTime(toRemove)

    Timestamp.setNextTime(previousPtr, nextPtr)
    Timestamp.setPreviousTime(nextPtr, previousPtr)
    Sublist.setSize(thisPtr, Sublist.getSize(thisPtr) - 1)
  }

  def split(thisPtr: Pointer, newSublistPtr: Pointer) {
    val thisList = getSublist(thisPtr)
    val newSublist = getSublist(newSublistPtr)
    val basePtr = getBasePtr(thisPtr)

    var nodePtr = basePtr
    var i = 0
    while (i < 32) {
      nodePtr = Timestamp.getNextTime(nodePtr)
      Timestamp.setTime(nodePtr, i + 1)
      i += 1
    }
    val newStartPtr = Timestamp.getNextTime(nodePtr)
    val newBasePtr = getBasePtr(newSublist.ptr)
    Timestamp.setNextTime(newBasePtr, newStartPtr)
    Timestamp.setPreviousTime(newStartPtr, newBasePtr)

    Timestamp.setNextTime(nodePtr, basePtr)
    Timestamp.setPreviousTime(basePtr, nodePtr)
    Sublist.setSize(thisPtr, i)

    nodePtr = newBasePtr
    i = 0
    while (Timestamp.getNextTime(nodePtr) != basePtr) {
      nodePtr = Timestamp.getNextTime(nodePtr)
      Timestamp.setTime(nodePtr, i + 1)

      Timestamp.setSublistPtr(nodePtr, newSublist.ptr)

      i += 1
    }

    Timestamp.setPreviousTime(newBasePtr, nodePtr)
    Timestamp.setNextTime(nodePtr, newBasePtr)
    Sublist.setSize(newSublist.ptr, i)
  }

  def calculateSize(ptr: Pointer): Int = {
    var timePtr = Timestamp.getNextTime(ptr)

    var size = 0
    while (timePtr != ptr) {
      size += 1
      timePtr = Timestamp.getNextTime(timePtr)
    }

    size
  }
}

class Sublist
    (val ptr: Long,
     var nextSub: Sublist,
     basePointer: Pointer = -1) {
  Sublist.addSublist(ptr, this)

  var previousSub: Sublist = null

  val basePtr = Timestamp.create(ptr, 0, -1, -1, basePointer)
  Sublist.setBasePtr(ptr, basePtr)

  Timestamp.setNextTime(basePtr, basePtr)
  Timestamp.setPreviousTime(basePtr, basePtr)

  override def toString = {
    var nodePtr = Timestamp.getNextTime(basePtr)
    var ret = "(" + Sublist.getSize(ptr) + ") {"

    while (nodePtr != basePtr) {
      ret += Timestamp.toString(nodePtr) + ", "
      nodePtr = Timestamp.getNextTime(nodePtr)
    }
    ret + "}"
  }
}
