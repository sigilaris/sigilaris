package org.sigilaris.node.jvm.storage.swaydb

import cats.effect.{IO, Resource}

import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  SnapshotMetadata,
  SnapshotMetadataStore,
  SnapshotNodeStore,
  given,
}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

object HotStuffSnapshotStores:
  def metadata(
      layout: StorageLayout,
  )(using Bag.Async[IO]): Resource[IO, SnapshotMetadataStore[IO]] =
    SwayStores
      .keyValue[ChainId, SnapshotMetadata](layout.state.snapshot)
      .map(SnapshotMetadataStore.fromKeyValueStore[IO])

  def nodes(
      layout: StorageLayout,
  )(using Bag.Async[IO]): Resource[IO, SnapshotNodeStore[IO]] =
    SwayStores
      .keyValue[MerkleTrieNode.MerkleHash, MerkleTrieNode](layout.state.nodes)
      .map(SnapshotNodeStore.fromKeyValueStore[IO])
