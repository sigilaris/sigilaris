package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.{Applicative, Functor}
import cats.effect.kernel.{Clock, Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.storage.KeyValueStore

trait SnapshotMetadataStore[F[_]]:
  def get(
      chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
  ): F[Option[SnapshotMetadata]]

  def put(
      metadata: SnapshotMetadata,
  ): F[Unit]

  def remove(
      chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
  ): F[Unit]

object SnapshotMetadataStore:
  def inMemory[F[_]: Sync]: F[SnapshotMetadataStore[F]] =
    Ref
      .of[F, Map[org.sigilaris.node.jvm.runtime.gossip.ChainId, SnapshotMetadata]](
        Map.empty,
      )
      .map(new InMemorySnapshotMetadataStore[F](_))

  def fromKeyValueStore[F[_]: Sync](
      keyValueStore: KeyValueStore[F, org.sigilaris.node.jvm.runtime.gossip.ChainId, SnapshotMetadata],
  ): SnapshotMetadataStore[F] =
    new SnapshotMetadataStore[F]:
      override def get(
          chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
      ): F[Option[SnapshotMetadata]] =
        keyValueStore.get(chainId).value.flatMap:
          case Right(metadata) =>
            metadata.pure[F]
          case Left(error) =>
            Sync[F].raiseError(new IllegalStateException(error.msg))

      override def put(
          metadata: SnapshotMetadata,
      ): F[Unit] =
        keyValueStore.put(metadata.anchor.chainId, metadata)

      override def remove(
          chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
      ): F[Unit] =
        keyValueStore.remove(chainId)

  private final class InMemorySnapshotMetadataStore[F[_]: Sync](
      ref: Ref[F, Map[org.sigilaris.node.jvm.runtime.gossip.ChainId, SnapshotMetadata]],
  ) extends SnapshotMetadataStore[F]:
    override def get(
        chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
    ): F[Option[SnapshotMetadata]] =
      ref.get.map(_.get(chainId))

    override def put(
        metadata: SnapshotMetadata,
    ): F[Unit] =
      ref.update(_.updated(metadata.anchor.chainId, metadata))

    override def remove(
        chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
    ): F[Unit] =
      ref.update(_ - chainId)

trait SnapshotNodeStore[F[_]]:
  def get(
      hash: MerkleTrieNode.MerkleHash,
  ): F[Option[MerkleTrieNode]]

  def put(
      node: SnapshotTrieNode,
  ): F[Unit]

  def putAll(
      nodes: Vector[SnapshotTrieNode],
  )(using Applicative[F]): F[Unit] =
    nodes.traverse_(put)

  def contains(
      hash: MerkleTrieNode.MerkleHash,
  )(using Functor[F]): F[Boolean] =
    get(hash).map(_.nonEmpty)

object SnapshotNodeStore:
  def inMemory[F[_]: Sync]: F[SnapshotNodeStore[F]] =
    Ref
      .of[F, Map[MerkleTrieNode.MerkleHash, MerkleTrieNode]](Map.empty)
      .map(new InMemorySnapshotNodeStore[F](_))

  def fromKeyValueStore[F[_]: Sync](
      keyValueStore: KeyValueStore[F, MerkleTrieNode.MerkleHash, MerkleTrieNode],
  ): SnapshotNodeStore[F] =
    new SnapshotNodeStore[F]:
      override def get(
          hash: MerkleTrieNode.MerkleHash,
      ): F[Option[MerkleTrieNode]] =
        keyValueStore.get(hash).value.flatMap:
          case Right(node) =>
            node.pure[F]
          case Left(error) =>
            Sync[F].raiseError(new IllegalStateException(error.msg))

      override def put(
          node: SnapshotTrieNode,
      ): F[Unit] =
        keyValueStore.put(node.hash, node.node)

  private final class InMemorySnapshotNodeStore[F[_]: Sync](
      ref: Ref[F, Map[MerkleTrieNode.MerkleHash, MerkleTrieNode]],
  ) extends SnapshotNodeStore[F]:
    override def get(
        hash: MerkleTrieNode.MerkleHash,
    ): F[Option[MerkleTrieNode]] =
      ref.get.map(_.get(hash))

    override def put(
        node: SnapshotTrieNode,
    ): F[Unit] =
      ref.update(_.updated(node.hash, node.node))

final case class SnapshotSyncFailure(
    reason: String,
    detail: Option[String],
)

final case class SnapshotSyncResult(
    metadata: SnapshotMetadata,
    fetchedNodeCount: Long,
)

trait SnapshotCoordinator[F[_]]:
  def sync(
      anchor: FinalizedAnchorSuggestion,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
  ): F[Either[SnapshotSyncFailure, SnapshotSyncResult]]

object SnapshotNodeVerifier:
  def verifyBatch(
      nodes: Vector[SnapshotTrieNode],
  ): Either[SnapshotSyncFailure, Vector[SnapshotTrieNode]] =
    nodes.foldLeft(
      Vector.empty[SnapshotTrieNode].asRight[SnapshotSyncFailure],
    ):
      case (left @ Left(_), _) =>
        left
      case (Right(acc), snapshotNode) =>
        val expected = snapshotNode.node.toHash
        Either
          .cond(
            expected === snapshotNode.hash,
            acc :+ snapshotNode,
            SnapshotSyncFailure(
              reason = "invalidSnapshotNodeHash",
              detail = Some(snapshotNode.hash.hex),
            ),
          )

  def rootHash(
      stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
  ): MerkleTrieNode.MerkleHash =
    Hash.Value[MerkleTrieNode](stateRoot.toUInt256)

object SnapshotNodeFetchServiceRuntime:
  def fromNodeStore[F[_]: Sync](
      nodeStore: SnapshotNodeStore[F],
  ): SnapshotNodeFetchService[F] =
    new SnapshotNodeFetchService[F]:
      override def fetchNodes(
          session: BootstrapSessionBinding,
          chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
          stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
          hashes: Vector[MerkleTrieNode.MerkleHash],
      ): F[Either[org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection, Vector[
        SnapshotTrieNode,
      ]]] =
        hashes.distinct
          .traverse: hash =>
            nodeStore.get(hash).map(_.map(node => SnapshotTrieNode(hash, node)))
          .map: loaded =>
            loaded.flatten.asRight[org.sigilaris.node.jvm.runtime.gossip.CanonicalRejection]

object SnapshotCoordinator:
  def create[F[_]: Sync: Clock](
      chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
  ): SnapshotCoordinator[F] =
    createWithNow(
      chainId = chainId,
      metadataStore = metadataStore,
      nodeStore = nodeStore,
      fetchService = fetchService,
      currentInstant = Clock[F].realTimeInstant,
    )

  def createWithNow[F[_]: Sync](
      chainId: org.sigilaris.node.jvm.runtime.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
      currentInstant: F[Instant],
  ): SnapshotCoordinator[F] =
    new SnapshotCoordinator[F]:
      override def sync(
          anchor: FinalizedAnchorSuggestion,
          sessions: Vector[BootstrapSessionBinding],
          startedAt: Instant,
      ): F[Either[SnapshotSyncFailure, SnapshotSyncResult]] =
        val rootHash = SnapshotNodeVerifier.rootHash(anchor.stateRoot)
        val initialMetadata =
          SnapshotMetadata(
            anchor = anchor.snapshotAnchor,
            status = SnapshotStatus.Syncing,
            verifiedNodeCount = 0L,
            pendingNodeCount = 1L,
            lastUpdatedAt = startedAt,
          )

        def fail(
            reason: String,
            detail: Option[String],
            verifiedNodeCount: Long,
            pendingNodeCount: Long,
        ): F[Either[SnapshotSyncFailure, SnapshotSyncResult]] =
          currentInstant.flatMap: updatedAt =>
            val failedMetadata =
              initialMetadata.copy(
                status = SnapshotStatus.Failed,
                verifiedNodeCount = verifiedNodeCount,
                pendingNodeCount = pendingNodeCount,
                lastUpdatedAt = updatedAt,
              )
            metadataStore.put(failedMetadata).as(
              SnapshotSyncFailure(reason, detail).asLeft[SnapshotSyncResult],
            )

        def updateProgress(
            verifiedNodeCount: Long,
            pendingNodeCount: Long,
            status: SnapshotStatus,
        ): F[SnapshotMetadata] =
          currentInstant.flatMap: updatedAt =>
            val metadata =
              initialMetadata.copy(
                status = status,
                verifiedNodeCount = verifiedNodeCount,
                pendingNodeCount = pendingNodeCount,
                lastUpdatedAt = updatedAt,
              )
            metadataStore.put(metadata).as(metadata)

        def loadNodes(
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): F[Map[MerkleTrieNode.MerkleHash, MerkleTrieNode]] =
          hashes.distinct.traverse: hash =>
            nodeStore.get(hash).map(hash -> _)
          .map(
            _.collect { case (hash, Some(node)) => hash -> node }.toMap,
          )

        def fetchMissing(
            missing: Vector[MerkleTrieNode.MerkleHash],
        ): F[Either[SnapshotSyncFailure, Long]] =
          if sessions.isEmpty then
            SnapshotSyncFailure(
              reason = "snapshotNoPeersAvailable",
              detail = Some(anchor.snapshotAnchor.chainId.value),
            ).asLeft[Long].pure[F]
          else
            sessions.foldLeft(
              (missing.distinct, 0L).asRight[SnapshotSyncFailure].pure[F],
            ):
              case (effect, session) =>
                effect.flatMap:
                  case left @ Left(_) =>
                    left.pure[F]
                  case Right((Vector(), storedCount)) =>
                    (Vector.empty[MerkleTrieNode.MerkleHash], storedCount)
                      .asRight[SnapshotSyncFailure]
                      .pure[F]
                  case Right((remaining, storedCount)) =>
                    fetchService
                      .fetchNodes(
                        session = session,
                        chainId = chainId,
                        stateRoot = anchor.stateRoot,
                        hashes = remaining,
                      )
                      .flatMap:
                        case Left(rejection) =>
                          SnapshotSyncFailure(
                            reason = "snapshotFetchRejected",
                            detail = Some(rejection.reason),
                          ).asLeft[(Vector[MerkleTrieNode.MerkleHash], Long)]
                            .pure[F]
                        case Right(nodes) =>
                          SnapshotNodeVerifier.verifyBatch(nodes) match
                            case Left(error) =>
                              error
                                .asLeft[(Vector[MerkleTrieNode.MerkleHash], Long)]
                                .pure[F]
                            case Right(verifiedNodes) =>
                              nodeStore.putAll(verifiedNodes).as:
                                val fetchedHashes =
                                  verifiedNodes.iterator.map(_.hash).toSet
                                (
                                  remaining.filterNot(fetchedHashes.contains),
                                  storedCount + verifiedNodes.size.toLong,
                                ).asRight[SnapshotSyncFailure]
            .map:
              case Left(error) =>
                error.asLeft[Long]
              case Right((remaining, storedCount)) if remaining.nonEmpty =>
                SnapshotSyncFailure(
                  reason = "snapshotClosureIncomplete",
                  detail = Some(remaining.map(_.hex).mkString(",")),
                ).asLeft[Long]
              case Right((_, storedCount)) =>
                storedCount.asRight[SnapshotSyncFailure]

        def childHashes(
            nodes: Iterable[MerkleTrieNode],
        ): Vector[MerkleTrieNode.MerkleHash] =
          nodes.iterator
            .flatMap: node =>
              node.getChildren.iterator.flatMap: children =>
                children.iterator.flatMap(_.iterator)
            .toVector

        @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
        def loop(
            frontier: Vector[MerkleTrieNode.MerkleHash],
            visited: Set[MerkleTrieNode.MerkleHash],
            fetchedNodeCount: Long,
        ): F[Either[SnapshotSyncFailure, SnapshotSyncResult]] =
          val pending = frontier.distinct.filterNot(visited.contains)
          if pending.isEmpty then
            updateProgress(
              verifiedNodeCount = visited.size.toLong,
              pendingNodeCount = 0L,
              status = SnapshotStatus.Complete,
            ).map: metadata =>
              SnapshotSyncResult(metadata = metadata, fetchedNodeCount = fetchedNodeCount)
                .asRight[SnapshotSyncFailure]
          else
            for
              loadedBefore <- loadNodes(pending)
              fetchResult <-
                if pending.forall(loadedBefore.contains) then
                  0L.asRight[SnapshotSyncFailure].pure[F]
                else
                  fetchMissing(pending.filterNot(loadedBefore.contains))
              result <- fetchResult match
                case Left(error) =>
                  loadNodes(pending).flatMap: loadedAfter =>
                    fail(
                      reason = error.reason,
                      detail = error.detail,
                      verifiedNodeCount = (visited ++ loadedAfter.keySet).size.toLong,
                      pendingNodeCount = pending.count(hash => !loadedAfter.contains(hash)).toLong,
                    )
                case Right(fetchedNow) =>
                  loadNodes(pending).flatMap: loadedAfter =>
                    if pending.exists(hash => !loadedAfter.contains(hash)) then
                      fail(
                        reason = "snapshotClosureIncomplete",
                        detail = Some(pending.filterNot(loadedAfter.contains).map(_.hex).mkString(",")),
                        verifiedNodeCount = (visited ++ loadedAfter.keySet).size.toLong,
                        pendingNodeCount = pending.count(hash => !loadedAfter.contains(hash)).toLong,
                      )
                    else
                      val visitedNow = visited ++ pending
                      val nextFrontier =
                        childHashes(loadedAfter.values)
                          .filterNot(visitedNow.contains)
                      updateProgress(
                        verifiedNodeCount = visitedNow.size.toLong,
                        pendingNodeCount = nextFrontier.distinct.size.toLong,
                        status = SnapshotStatus.Syncing,
                      ) *> loop(
                        frontier = nextFrontier,
                        visited = visitedNow,
                        fetchedNodeCount = fetchedNodeCount + fetchedNow,
                      )
            yield result

        metadataStore.put(initialMetadata) *> loop(
          frontier = Vector(rootHash),
          visited = Set.empty[MerkleTrieNode.MerkleHash],
          fetchedNodeCount = 0L,
        )
