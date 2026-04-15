package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Path, Paths}

/** Defines the filesystem directory layout for all SwayDB-backed persistent stores.
  *
  * @param root        the root directory for all storage
  * @param block       paths for block-related stores
  * @param transaction paths for transaction-related stores
  * @param batch       paths for batch-related stores
  * @param state       paths for consensus state stores
  * @param event       paths for event-related stores
  */
final case class StorageLayout(
    root: Path,
    block: StorageLayout.Block,
    transaction: StorageLayout.Transaction,
    batch: StorageLayout.Batch,
    state: StorageLayout.State,
    event: StorageLayout.Event,
)

/** Companion providing nested path group types and factory methods for `StorageLayout`. */
object StorageLayout:

  /** Directory paths for block storage.
    *
    * @param bestHeader path to the best block header store
    * @param bodies     path to the block bodies store
    */
  final case class Block(bestHeader: Path, bodies: Path)

  /** Directory paths for transaction storage.
    *
    * @param immutable path to the immutable (finalized) transaction store
    * @param meta      path to the transaction metadata store
    */
  final case class Transaction(immutable: Path, meta: Path)

  /** Directory paths for batch storage.
    *
    * @param immutable path to the immutable batch store
    * @param pending   path to the pending batch store
    * @param committed path to the committed batch store
    */
  final case class Batch(immutable: Path, pending: Path, committed: Path)

  /** Directory paths for consensus state storage.
    *
    * @param snapshot          path to the snapshot metadata store
    * @param nodes             path to the Merkle trie node store
    * @param historicalArchive path to the historical archive store
    */
  final case class State(snapshot: Path, nodes: Path, historicalArchive: Path)

  /** Directory paths for event storage.
    *
    * @param index path to the event index store
    */
  final case class Event(index: Path)

  /** Constructs a `StorageLayout` by resolving conventional subdirectory names under the given root.
    *
    * @param root the root directory
    * @return a fully populated storage layout
    */
  def fromRoot(root: Path): StorageLayout =
    val blockRoot = root.resolve("block")
    val txRoot    = root.resolve("transaction")
    val batchRoot = root.resolve("batch")
    val stateRoot = root.resolve("state")
    val eventRoot = root.resolve("event")

    StorageLayout(
      root = root,
      block = Block(blockRoot.resolve("best"), blockRoot.resolve("body")),
      transaction =
        Transaction(txRoot.resolve("immutable"), txRoot.resolve("meta")),
      batch = Batch(
        batchRoot.resolve("immutable"),
        batchRoot.resolve("pending"),
        batchRoot.resolve("committed"),
      ),
      state = State(
        stateRoot.resolve("snapshot"),
        stateRoot.resolve("nodes"),
        stateRoot.resolve("historical-archive"),
      ),
      event = Event(eventRoot.resolve("index")),
    )

  /** The default storage layout, rooted at `data/sway`. */
  val default: StorageLayout = fromRoot(Paths.get("data", "sway"))
