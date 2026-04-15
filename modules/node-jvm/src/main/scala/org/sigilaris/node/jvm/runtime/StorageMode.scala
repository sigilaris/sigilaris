package org.sigilaris.node.jvm.runtime

/** Discriminates between in-memory and persistent storage backends.
  *
  * @tparam Layout
  *   the persistent storage layout type (covariant)
  */
enum StorageMode[+Layout]:

  /** Purely in-memory storage; data is lost on shutdown. */
  case InMemory

  /** Persistent storage backed by the given layout descriptor.
    *
    * @param layout
    *   the storage layout configuration
    */
  case Persistent(layout: Layout)

/** Companion for `StorageMode` providing CLI argument parsing. */
object StorageMode:

  /** Default CLI flag used to select in-memory mode. */
  val DefaultInMemoryFlag: String = "--in-memory"

  /** Determines the storage mode from CLI arguments.
    *
    * If `args` contains `inMemoryFlag`, returns `StorageMode.InMemory`;
    * otherwise returns `StorageMode.Persistent` with the provided layout.
    *
    * @tparam Layout
    *   the persistent storage layout type
    * @param args
    *   CLI arguments to inspect
    * @param persistentLayout
    *   lazily evaluated layout used when persistent mode is selected
    * @param inMemoryFlag
    *   the flag string that triggers in-memory mode
    * @return
    *   the resolved storage mode
    */
  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromArgs[Layout](
      args: IterableOnce[String],
      persistentLayout: => Layout,
      inMemoryFlag: String = DefaultInMemoryFlag,
  ): StorageMode[Layout] =
    if args.iterator.contains(inMemoryFlag) then StorageMode.InMemory
    else StorageMode.Persistent(persistentLayout)
