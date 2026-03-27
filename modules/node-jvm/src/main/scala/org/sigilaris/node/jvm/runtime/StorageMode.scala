package org.sigilaris.node.jvm.runtime

enum StorageMode[+Layout]:
  case InMemory
  case Persistent(layout: Layout)

object StorageMode:
  val DefaultInMemoryFlag: String = "--in-memory"

  def fromArgs[Layout](
      args: IterableOnce[String],
      persistentLayout: => Layout,
      inMemoryFlag: String = DefaultInMemoryFlag,
  ): StorageMode[Layout] =
    if args.iterator.contains(inMemoryFlag) then StorageMode.InMemory
    else StorageMode.Persistent(persistentLayout)
