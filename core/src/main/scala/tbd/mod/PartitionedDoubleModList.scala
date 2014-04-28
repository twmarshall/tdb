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
package tbd.mod

import akka.actor.ActorRef
import scala.collection.mutable.{ArrayBuffer, Buffer, Set}

import tbd.{Changeable, TBD}
import tbd.datastore.Datastore

class PartitionedDoubleModList[T](
    aLists: Mod[DoubleModList[Mod[DoubleModList[T]]]]) extends ModList[T] {
  val lists = aLists

  def map[U](tbd: TBD, func: T => U): ModList[U] = {
    new PartitionedDoubleModList(tbd.mod((dest: Dest[DoubleModList[Mod[DoubleModList[U]]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        lsts.map(tbd, dest, (list: Mod[DoubleModList[T]]) => {
          tbd.mod((dest: Dest[DoubleModList[U]]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              if (lst != null) {
                lst.map(tbd, dest, func)
              } else {
                tbd.write(dest, null)
              }
            })
          })
        })
      })
    }))
  }

  def memoMap[U](tbd: TBD, func: T => U): ModList[U] = {
    new PartitionedDoubleModList(tbd.mod((dest: Dest[DoubleModList[Mod[DoubleModList[U]]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        lsts.map(tbd, dest, (list: Mod[DoubleModList[T]]) => {
          val lift = tbd.makeLift[DoubleModList[T], Mod[DoubleModList[U]]]()

          tbd.mod((dest: Dest[DoubleModList[U]]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              if (lst != null) {
                lst.memoMap(tbd, dest, func, lift)
              } else {
                tbd.write(dest, null)
              }
            })
          })
        })
      })
    }))
  }

  def parMap[U](tbd: TBD, func: T => U): ModList[U] = {
    new PartitionedDoubleModList(tbd.mod((dest: Dest[DoubleModList[Mod[DoubleModList[U]]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        lsts.parMap(tbd, dest, (tbd: TBD, list: Mod[DoubleModList[T]]) => {
          tbd.mod((dest: Dest[DoubleModList[U]]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              if (lst != null) {
                lst.map(tbd, dest, (value: T) => func(value))
              } else {
                tbd.write(dest, null)
              }
            })
          })
        })
      })
    }))
  }

  def memoParMap[U](tbd: TBD, func: T => U): ModList[U] = {
    new PartitionedDoubleModList(tbd.mod((dest: Dest[DoubleModList[Mod[DoubleModList[U]]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        lsts.parMap(tbd, dest, (tbd: TBD, list: Mod[DoubleModList[T]]) => {
          val lift = tbd.makeLift[DoubleModList[T], Mod[DoubleModList[U]]]()

          tbd.mod((dest: Dest[DoubleModList[U]]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              if (lst != null) {
                lst.memoMap(tbd, dest, func, lift)
              } else {
                tbd.write(dest, null)
              }
            })
          })
        })
      })
    }))
  }

  def reduce(tbd: TBD, func: (T, T) => T): Mod[T] = {
    def innerReduce[U](dest: Dest[U], lst: DoubleModList[U], f: (U, U) => U): Changeable[U] = {
      if (lst != null) {
        tbd.read(lst.next, (next: DoubleModList[U]) => {
          if (next != null) {
            val newList = tbd.mod((dest: Dest[DoubleModList[U]]) => {
              lst.reduce(tbd, dest, f)
            })

            tbd.read(newList, (lst: DoubleModList[U]) => innerReduce(dest, lst, f))
          } else {
            tbd.read(lst.value, (value: U) => tbd.write(dest, value))
          }
        })
      } else {
        tbd.write(dest, null.asInstanceOf[U])
      }
    }

    val reducedLists: Mod[DoubleModList[Mod[T]]] = tbd.mod((dest: Dest[DoubleModList[Mod[T]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        lsts.map(tbd, dest, (list: Mod[DoubleModList[T]]) => {
          tbd.mod((dest: Dest[T]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              innerReduce(dest, lst, func)
            })
          })
        })
      })
    })

    val reducedMod = tbd.mod((dest: Dest[Mod[T]]) => {
      tbd.read(reducedLists, (reducedLsts: DoubleModList[Mod[T]]) => {
        innerReduce[Mod[T]](dest, reducedLsts, (mod1: Mod[T], mod2: Mod[T]) => {
          tbd.mod((dest: Dest[T]) => {
            tbd.read(mod1, (value1: T) => {
              tbd.read(mod2, (value2: T) => {
                tbd.write(dest, func(value1, value2))
              })
            })
          })
        })
      })
    })

    tbd.mod((dest: Dest[T]) => {
      tbd.read(reducedMod, (mod: Mod[T]) => {
        tbd.read(mod, (value: T) => {
          tbd.write(dest, value)
        })
      })
    })
  }

  def parReduce(tbd: TBD, func: (T, T) => T): Mod[T] = {
    def innerReduce[U](
        tbd: TBD,
        dest: Dest[U],
        lst: DoubleModList[U],
        f: (U, U) => U): Changeable[U] = {
      if (lst != null) {
        tbd.read(lst.next, (next: DoubleModList[U]) => {
          if (next != null) {
            val newList = tbd.mod((dest: Dest[DoubleModList[U]]) => {
	            lst.reduce(tbd, dest, f)
	          })
            tbd.read(newList, (lst: DoubleModList[U]) => {
              innerReduce(tbd, dest, lst, f)
            })
          } else {
            tbd.read(lst.value, (value: U) => tbd.write(dest, value))
          }
        })
      } else {
        tbd.write(dest, null.asInstanceOf[U])
      }
    }

    val reducedLists = tbd.mod((dest: Dest[DoubleModList[Mod[T]]]) => {
      tbd.read(lists, (lsts: DoubleModList[Mod[DoubleModList[T]]]) => {
        // Do a parallel map over the partitions, where the mapping function is
        // reduce.
        lsts.parMap(tbd, dest, (tbd: TBD, list: Mod[DoubleModList[T]]) => {
          tbd.mod((dest: Dest[T]) => {
            tbd.read(list, (lst: DoubleModList[T]) => {
              innerReduce(tbd, dest, lst, (value1: T, value2: T) => {
                func(value1, value2)
              })
            })
          })
        })
      })
    })

    val reducedMod = tbd.mod((dest: Dest[Mod[T]]) => {
      tbd.read(reducedLists, (reducedLsts: DoubleModList[Mod[T]]) => {
        innerReduce(tbd, dest, reducedLsts, (mod1: Mod[T], mod2: Mod[T]) => {
          tbd.mod((dest: Dest[T]) => {
            tbd.read(mod1, (value1: T) => {
              tbd.read(mod2, (value2: T) => {
                tbd.write(dest, func(value1, value2))
              })
            })
          })
        })
      })
    })

    tbd.mod((dest: Dest[T]) => {
      tbd.read(reducedMod, (mod: Mod[T]) => {
        tbd.read(mod, (value: T) => {
          tbd.write(dest, value)
        })
      })
    })
  }

  /* Meta Operations */
  def toSet(): Set[T] = {
    val set = Set[T]()
    var outerNode = lists.read()
    while (outerNode != null) {
      var innerNode = outerNode.value.read().read()
      while (innerNode != null) {
        set += innerNode.value.read()
        innerNode = innerNode.next.read()
      }
      outerNode = outerNode.next.read()
    }

    set
  }

  def toBuffer(): Buffer[T] = {
    val buf = ArrayBuffer[T]()
    var outerNode = lists.read()
    while (outerNode != null) {
      var innerNode = outerNode.value.read().read()
      while (innerNode != null) {
        buf += innerNode.value.read()
        innerNode = innerNode.next.read()
      }
      outerNode = outerNode.next.read()
    }

    buf
  }
}