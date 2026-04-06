package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Path, Paths}

final case class StorageLayout(
    root: Path,
    block: StorageLayout.Block,
    transaction: StorageLayout.Transaction,
    batch: StorageLayout.Batch,
    state: StorageLayout.State,
    event: StorageLayout.Event,
)

object StorageLayout:
  final case class Block(bestHeader: Path, bodies: Path)
  final case class Transaction(immutable: Path, meta: Path)
  final case class Batch(immutable: Path, pending: Path, committed: Path)
  final case class State(snapshot: Path, nodes: Path, historicalArchive: Path)
  final case class Event(index: Path)

  def fromRoot(root: Path): StorageLayout =
    val blockRoot = root.resolve("block")
    val txRoot    = root.resolve("transaction")
    val batchRoot = root.resolve("batch")
    val stateRoot = root.resolve("state")
    val eventRoot = root.resolve("event")

    StorageLayout(
      root = root,
      block = Block(blockRoot.resolve("best"), blockRoot.resolve("body")),
      transaction = Transaction(txRoot.resolve("immutable"), txRoot.resolve("meta")),
      batch = Batch(batchRoot.resolve("immutable"), batchRoot.resolve("pending"), batchRoot.resolve("committed")),
      state = State(
        stateRoot.resolve("snapshot"),
        stateRoot.resolve("nodes"),
        stateRoot.resolve("historical-archive"),
      ),
      event = Event(eventRoot.resolve("index")),
    )

  val default: StorageLayout = fromRoot(Paths.get("data", "sway"))
