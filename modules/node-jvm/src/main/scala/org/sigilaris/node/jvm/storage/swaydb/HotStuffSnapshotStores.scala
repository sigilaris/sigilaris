package org.sigilaris.node.jvm.storage.swaydb

import cats.effect.{IO, Resource}

import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  SnapshotMetadata,
  SnapshotMetadataStore,
  SnapshotNodeStore,
  given,
}
import org.sigilaris.node.gossip.ChainId

/** Factory for creating SwayDB-backed HotStuff snapshot stores. */
object HotStuffSnapshotStores:

  /** Creates a SwayDB-backed store for snapshot metadata, keyed by chain ID.
    *
    * @param layout the storage layout defining directory paths
    * @return a resource that yields a `SnapshotMetadataStore`
    */
  def metadata(
      layout: StorageLayout,
  )(using Bag.Async[IO]): Resource[IO, SnapshotMetadataStore[IO]] =
    SwayStores
      .keyValue[ChainId, Vector[SnapshotMetadata]](layout.state.snapshot)
      .evalMap(SnapshotMetadataStore.fromKeyValueStore[IO])

  /** Creates a SwayDB-backed store for Merkle trie nodes used in snapshots.
    *
    * @param layout the storage layout defining directory paths
    * @return a resource that yields a `SnapshotNodeStore`
    */
  def nodes(
      layout: StorageLayout,
  )(using Bag.Async[IO]): Resource[IO, SnapshotNodeStore[IO]] =
    SwayStores
      .keyValue[MerkleTrieNode.MerkleHash, MerkleTrieNode](layout.state.nodes)
      .map(SnapshotNodeStore.fromKeyValueStore[IO])
