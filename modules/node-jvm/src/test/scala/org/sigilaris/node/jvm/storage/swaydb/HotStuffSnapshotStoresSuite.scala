package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Resource}
import cats.syntax.apply.*
import munit.CatsEffectSuite
import scodec.bits.ByteVector

import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.core.merkle.Nibbles.*
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{SnapshotAnchor, SnapshotMetadata, SnapshotStatus}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

final class HotStuffSnapshotStoresSuite extends CatsEffectSuite:
  private given Bag.Async[IO] = Bag.global

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("sigilaris-snapshot-sway"))) { dir =>
      IO.blocking:
        Using.resource(Files.walk(dir)): stream =>
          stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)
        ()
    }

  test("snapshot metadata and trie nodes round-trip through the dedicated SwayDB layout paths"):
    val chainId = ChainId.unsafe("chain-main")
    val rootNode =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        ByteVector.fromHexDescriptive("aa").toOption.get,
      )
    val childNode =
      MerkleTrieNode.leaf(
        ByteVector.empty.toNibbles,
        ByteVector.fromHexDescriptive("bb").toOption.get,
      )
    val rootHash = rootNode.toHash
    val childHash = childNode.toHash
    val metadata =
      SnapshotMetadata(
        anchor = SnapshotAnchor(
          chainId = chainId,
          proposalId = org.sigilaris.node.jvm.runtime.consensus.hotstuff.ProposalId(UInt256.fromHex("01").toOption.get),
          blockId = BlockId(UInt256.fromHex("02").toOption.get),
          height = BlockHeight.unsafeFromLong(7L),
          stateRoot = StateRoot(rootHash.toUInt256),
        ),
        status = SnapshotStatus.Syncing,
        verifiedNodeCount = 1L,
        pendingNodeCount = 2L,
        lastUpdatedAt = Instant.parse("2026-04-05T03:00:00Z"),
      )

    tempDirResource.use: root =>
      val layout = StorageLayout.fromRoot(root)
      (HotStuffSnapshotStores.metadata(layout), HotStuffSnapshotStores.nodes(layout)).tupled.use:
        (metadataStore, nodeStore) =>
          for
            _ <- metadataStore.put(metadata)
            _ <- nodeStore.putAll(
              Vector(
                org.sigilaris.node.jvm.runtime.consensus.hotstuff.SnapshotTrieNode(rootHash, rootNode),
                org.sigilaris.node.jvm.runtime.consensus.hotstuff.SnapshotTrieNode(childHash, childNode),
              ),
            )
            loadedMetadata <- metadataStore.get(chainId)
            loadedNode <- nodeStore.get(rootHash)
            loadedChild <- nodeStore.get(childHash)
            containsRoot <- nodeStore.contains(rootHash)
            containsChild <- nodeStore.contains(childHash)
            _ <- metadataStore.remove(chainId)
            removedMetadata <- metadataStore.get(chainId)
          yield
            assertEquals(layout.state.snapshot, root.resolve("state").resolve("snapshot"))
            assertEquals(layout.state.nodes, root.resolve("state").resolve("nodes"))
            assertEquals(loadedMetadata, Some(metadata))
            assertEquals(loadedNode, Some(rootNode))
            assertEquals(loadedChild, Some(childNode))
            assertEquals(containsRoot, true)
            assertEquals(containsChild, true)
            assertEquals(removedMetadata, None)
