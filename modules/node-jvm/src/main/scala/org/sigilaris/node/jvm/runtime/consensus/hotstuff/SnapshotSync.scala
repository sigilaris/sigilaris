package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import scala.util.control.NonFatal

import cats.{Applicative, Functor}
import cats.effect.kernel.{Clock, Concurrent, Ref, Sync}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.failure.{
  FailureDiagnosticFamily,
  StructuredFailureDiagnostic,
}
import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.jvm.storage.KeyValueStore

private final class SnapshotStoreFailureException(
    val failure: SnapshotSyncFailure,
) extends RuntimeException:
  override def getMessage: String =
    failure.detail match
      case Some(detail) => failure.reason + ": " + detail
      case None         => failure.reason

/** Persistent store for snapshot synchronization metadata, keyed by chain ID. */
trait SnapshotMetadataStore[F[_]]:
  def get(
      chainId: org.sigilaris.node.gossip.ChainId,
  ): F[Option[SnapshotMetadata]]

  def getForAnchor(
      anchor: SnapshotAnchor,
  ): F[Option[SnapshotMetadata]]

  def list(
      chainId: org.sigilaris.node.gossip.ChainId,
  ): F[Vector[SnapshotMetadata]]

  def put(
      metadata: SnapshotMetadata,
  ): F[Unit]

  def remove(
      chainId: org.sigilaris.node.gossip.ChainId,
  ): F[Unit]

/** Companion for `SnapshotMetadataStore`, providing in-memory and key-value-backed implementations. */
object SnapshotMetadataStore:
  private val latestOrdering: Ordering[SnapshotMetadata] =
    Ordering.by[SnapshotMetadata, (Instant, BlockHeight, String, String)]:
      metadata =>
        (
          metadata.lastUpdatedAt,
          metadata.anchor.height,
          metadata.anchor.blockId.toHexLower,
          metadata.anchor.proposalId.toHexLower,
        )

  private val historyOrdering: Ordering[SnapshotMetadata] =
    latestOrdering.reverse

  /** Creates an in-memory metadata store backed by a Ref. */
  def inMemory[F[_]: Sync]: F[SnapshotMetadataStore[F]] =
    Ref
      .of[F, Map[org.sigilaris.node.gossip.ChainId, Vector[
        SnapshotMetadata,
      ]]](
        Map.empty,
      )
      .map(new InMemorySnapshotMetadataStore[F](_))

  /** Creates a metadata store backed by a key-value store. */
  def fromKeyValueStore[F[_]: Concurrent](
      keyValueStore: KeyValueStore[
        F,
        org.sigilaris.node.gossip.ChainId,
        Vector[SnapshotMetadata],
      ],
  ): F[SnapshotMetadataStore[F]] =
    Semaphore[F](1).map: writeLock =>
      new SnapshotMetadataStore[F]:
        override def get(
            chainId: org.sigilaris.node.gossip.ChainId,
        ): F[Option[SnapshotMetadata]] =
          keyValueStore
            .get(chainId)
            .value
            .handleErrorWith:
              case error: SnapshotStoreFailureException =>
                Concurrent[F].raiseError(error)
              case NonFatal(error) =>
                Concurrent[F].raiseError:
                  SnapshotSyncFailure.raiseStorageFailure(
                    reason = "snapshotMetadataReadFailed",
                    detail = Option(error.getMessage),
                  )
              case error =>
                Concurrent[F].raiseError(error)
            .flatMap:
              case Right(metadata) =>
                latestMetadata(metadata.getOrElse(Vector.empty)).pure[F]
              case Left(error) =>
                Concurrent[F].raiseError:
                  SnapshotSyncFailure.raiseStorageFailure(
                    reason = "snapshotMetadataReadFailed",
                    detail = Some(error.msg),
                  )

        override def getForAnchor(
            anchor: SnapshotAnchor,
        ): F[Option[SnapshotMetadata]] =
          list(anchor.chainId).map(
            _.find(metadata => sameAnchor(metadata.anchor, anchor)),
          )

        override def list(
            chainId: org.sigilaris.node.gossip.ChainId,
        ): F[Vector[SnapshotMetadata]] =
          keyValueStore
            .get(chainId)
            .value
            .handleErrorWith:
              case error: SnapshotStoreFailureException =>
                Concurrent[F].raiseError(error)
              case NonFatal(error) =>
                Concurrent[F].raiseError:
                  SnapshotSyncFailure.raiseStorageFailure(
                    reason = "snapshotMetadataReadFailed",
                    detail = Option(error.getMessage),
                  )
              case error =>
                Concurrent[F].raiseError(error)
            .flatMap:
              case Right(metadata) =>
                sortHistory(metadata.getOrElse(Vector.empty)).pure[F]
              case Left(error) =>
                Concurrent[F].raiseError:
                  SnapshotSyncFailure.raiseStorageFailure(
                    reason = "snapshotMetadataReadFailed",
                    detail = Some(error.msg),
                  )

        override def put(
            metadata: SnapshotMetadata,
        ): F[Unit] =
          writeLock.permit.use: _ =>
            list(metadata.anchor.chainId).flatMap: history =>
              keyValueStore.put(
                metadata.anchor.chainId,
                upsertHistory(history, metadata),
              ).handleErrorWith:
                case error: SnapshotStoreFailureException =>
                  Concurrent[F].raiseError(error)
                case NonFatal(error) =>
                  Concurrent[F].raiseError:
                    SnapshotSyncFailure.raiseStorageFailure(
                      reason = "snapshotMetadataWriteFailed",
                      detail = Option(error.getMessage),
                    )
                case error =>
                  Concurrent[F].raiseError(error)

        override def remove(
            chainId: org.sigilaris.node.gossip.ChainId,
        ): F[Unit] =
          keyValueStore.remove(chainId).handleErrorWith:
            case error: SnapshotStoreFailureException =>
              Concurrent[F].raiseError(error)
            case NonFatal(error) =>
              Concurrent[F].raiseError:
                SnapshotSyncFailure.raiseStorageFailure(
                  reason = "snapshotMetadataWriteFailed",
                  detail = Option(error.getMessage),
                )
            case error =>
              Concurrent[F].raiseError(error)

  private final class InMemorySnapshotMetadataStore[F[_]: Sync](
      ref: Ref[F, Map[org.sigilaris.node.gossip.ChainId, Vector[
        SnapshotMetadata,
      ]]],
  ) extends SnapshotMetadataStore[F]:
    override def get(
        chainId: org.sigilaris.node.gossip.ChainId,
    ): F[Option[SnapshotMetadata]] =
      ref.get.map: historyByChain =>
        latestMetadata(historyByChain.getOrElse(chainId, Vector.empty))

    override def getForAnchor(
        anchor: SnapshotAnchor,
    ): F[Option[SnapshotMetadata]] =
      list(anchor.chainId).map(
        _.find(metadata => sameAnchor(metadata.anchor, anchor)),
      )

    override def list(
        chainId: org.sigilaris.node.gossip.ChainId,
    ): F[Vector[SnapshotMetadata]] =
      ref.get.map: historyByChain =>
        sortHistory(historyByChain.getOrElse(chainId, Vector.empty))

    override def put(
        metadata: SnapshotMetadata,
    ): F[Unit] =
      ref.update: historyByChain =>
        historyByChain.updated(
          metadata.anchor.chainId,
          upsertHistory(
            historyByChain.getOrElse(metadata.anchor.chainId, Vector.empty),
            metadata,
          ),
        )

    override def remove(
        chainId: org.sigilaris.node.gossip.ChainId,
    ): F[Unit] =
      ref.update(_ - chainId)

  private def sameAnchor(
      left: SnapshotAnchor,
      right: SnapshotAnchor,
  ): Boolean =
    left.chainId === right.chainId &&
      left.proposalId === right.proposalId &&
      left.blockId === right.blockId &&
      left.height === right.height &&
      left.stateRoot === right.stateRoot

  private def latestMetadata(
      history: Vector[SnapshotMetadata],
  ): Option[SnapshotMetadata] =
    history.maxOption(using latestOrdering)

  private def sortHistory(
      history: Vector[SnapshotMetadata],
  ): Vector[SnapshotMetadata] =
    history.sorted(using historyOrdering)

  private def upsertHistory(
      history: Vector[SnapshotMetadata],
      metadata: SnapshotMetadata,
  ): Vector[SnapshotMetadata] =
    sortHistory(
      history.filterNot(existing =>
        sameAnchor(existing.anchor, metadata.anchor),
      ) :+ metadata,
    )

/** Store for Merkle trie nodes used during snapshot synchronization. */
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

/** Companion for `SnapshotNodeStore`, providing in-memory and key-value-backed implementations. */
object SnapshotNodeStore:
  /** Creates an in-memory node store backed by a Ref. */
  def inMemory[F[_]: Sync]: F[SnapshotNodeStore[F]] =
    Ref
      .of[F, Map[MerkleTrieNode.MerkleHash, MerkleTrieNode]](Map.empty)
      .map(new InMemorySnapshotNodeStore[F](_))

  /** Creates a node store backed by a key-value store. */
  def fromKeyValueStore[F[_]: Sync](
      keyValueStore: KeyValueStore[F, MerkleTrieNode.MerkleHash, MerkleTrieNode],
  ): SnapshotNodeStore[F] =
    new SnapshotNodeStore[F]:
      override def get(
          hash: MerkleTrieNode.MerkleHash,
      ): F[Option[MerkleTrieNode]] =
        keyValueStore
          .get(hash)
          .value
          .handleErrorWith:
            case error: SnapshotStoreFailureException =>
              Sync[F].raiseError(error)
            case NonFatal(error) =>
              Sync[F].raiseError:
                SnapshotSyncFailure.raiseStorageFailure(
                  reason = "snapshotNodeReadFailed",
                  detail = Option(error.getMessage),
                )
            case error =>
              Sync[F].raiseError(error)
          .flatMap:
            case Right(node) =>
              node.pure[F]
            case Left(error) =>
              Sync[F].raiseError:
                SnapshotSyncFailure.raiseStorageFailure(
                  reason = "snapshotNodeReadFailed",
                  detail = Some(error.msg),
                )

      override def put(
          node: SnapshotTrieNode,
      ): F[Unit] =
        keyValueStore.put(node.hash, node.node).handleErrorWith:
          case error: SnapshotStoreFailureException =>
            Sync[F].raiseError(error)
          case NonFatal(error) =>
            Sync[F].raiseError:
              SnapshotSyncFailure.raiseStorageFailure(
                reason = "snapshotNodeWriteFailed",
                detail = Option(error.getMessage),
              )
          case error =>
            Sync[F].raiseError(error)

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

/** Represents a failure during snapshot synchronization. */
final case class SnapshotSyncFailure(
    reason: String,
    detail: Option[String],
) extends StructuredFailureDiagnostic:
  override val diagnosticFamily: FailureDiagnosticFamily =
    FailureDiagnosticFamily.SnapshotSync

object SnapshotSyncFailure:
  def raiseStorageFailure(
      reason: String,
      detail: Option[String],
  ): SnapshotStoreFailureException =
    new SnapshotStoreFailureException(
      SnapshotSyncFailure(
        reason = reason,
        detail = detail,
      ),
    )

/** The result of a successful snapshot synchronization. */
final case class SnapshotSyncResult(
    metadata: SnapshotMetadata,
    fetchedNodeCount: Long,
)

/** Policy controlling how many rounds of peer fetching to attempt during snapshot sync. */
final case class SnapshotFetchPolicy private (
    maxPeerRounds: Int,
)

/** Companion for `SnapshotFetchPolicy`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object SnapshotFetchPolicy:
  def apply(
      maxPeerRounds: Int,
  ): Either[String, SnapshotFetchPolicy] =
    Either.cond(
      maxPeerRounds > 0,
      new SnapshotFetchPolicy(maxPeerRounds = maxPeerRounds),
      "maxPeerRounds must be positive",
    )

  def unsafe(
      maxPeerRounds: Int,
  ): SnapshotFetchPolicy =
    apply(maxPeerRounds = maxPeerRounds) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

  /** The default fetch policy (3 peer rounds). */
  val default: SnapshotFetchPolicy =
    unsafe(maxPeerRounds = 3)

/** Coordinates snapshot synchronization by fetching trie nodes from peers and persisting them locally. */
trait SnapshotCoordinator[F[_]]:
  def sync(
      anchor: FinalizedAnchorSuggestion,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
  ): F[Either[SnapshotSyncFailure, SnapshotSyncResult]]

/** Verifies the integrity of fetched snapshot trie nodes by checking their hashes. */
object SnapshotNodeVerifier:
  /** Verifies a batch of snapshot trie nodes, checking each hash matches the node content. */
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

  /** Converts a state root to its equivalent Merkle hash for snapshot traversal. */
  def rootHash(
      stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
  ): MerkleTrieNode.MerkleHash =
    Hash.Value[MerkleTrieNode](stateRoot.toUInt256)

/** Runtime implementation of `SnapshotNodeFetchService` backed by a local node store. */
object SnapshotNodeFetchServiceRuntime:
  /** Creates a fetch service that serves nodes from a local store. */
  def fromNodeStore[F[_]: Sync](
      nodeStore: SnapshotNodeStore[F],
  ): SnapshotNodeFetchService[F] =
    new SnapshotNodeFetchService[F]:
      override def fetchNodes(
          session: BootstrapSessionBinding,
          chainId: org.sigilaris.node.gossip.ChainId,
          stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
          hashes: Vector[MerkleTrieNode.MerkleHash],
      ): F[
        Either[org.sigilaris.node.gossip.CanonicalRejection, Vector[
          SnapshotTrieNode,
        ]],
      ] =
        hashes.distinct
          .traverse: hash =>
            nodeStore.get(hash).map(_.map(node => SnapshotTrieNode(hash, node)))
          .map: loaded =>
            loaded.flatten
              .asRight[org.sigilaris.node.gossip.CanonicalRejection]

/** Companion for `SnapshotCoordinator`, providing factory methods with varying configuration. */
object SnapshotCoordinator:
  def create[F[_]: Sync: Clock](
      chainId: org.sigilaris.node.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
  ): SnapshotCoordinator[F] =
    createWithPolicyAndNow(
      chainId = chainId,
      metadataStore = metadataStore,
      nodeStore = nodeStore,
      fetchService = fetchService,
      fetchPolicy = SnapshotFetchPolicy.default,
      currentInstant = Clock[F].realTimeInstant,
    )

  def createWithNow[F[_]: Sync](
      chainId: org.sigilaris.node.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
      currentInstant: F[Instant],
  ): SnapshotCoordinator[F] =
    createWithPolicyAndNow(
      chainId = chainId,
      metadataStore = metadataStore,
      nodeStore = nodeStore,
      fetchService = fetchService,
      fetchPolicy = SnapshotFetchPolicy.default,
      currentInstant = currentInstant,
    )

  def createWithPolicy[F[_]: Sync: Clock](
      chainId: org.sigilaris.node.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
      fetchPolicy: SnapshotFetchPolicy,
  ): SnapshotCoordinator[F] =
    createWithPolicyAndNow(
      chainId = chainId,
      metadataStore = metadataStore,
      nodeStore = nodeStore,
      fetchService = fetchService,
      fetchPolicy = fetchPolicy,
      currentInstant = Clock[F].realTimeInstant,
    )

  def createWithPolicyAndNow[F[_]: Sync](
      chainId: org.sigilaris.node.gossip.ChainId,
      metadataStore: SnapshotMetadataStore[F],
      nodeStore: SnapshotNodeStore[F],
      fetchService: SnapshotNodeFetchService[F],
      fetchPolicy: SnapshotFetchPolicy,
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
            metadataStore
              .put(failedMetadata)
              .as(
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
          hashes.distinct
            .traverse: hash =>
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
            @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
            def runRounds(
                remaining: Vector[MerkleTrieNode.MerkleHash],
                roundsLeft: Int,
                storedCount: Long,
                lastRejection: Option[SnapshotSyncFailure],
            ): F[Either[SnapshotSyncFailure, Long]] =
              sessions
                .foldLeft(
                  (remaining.distinct, storedCount, lastRejection)
                    .asRight[SnapshotSyncFailure]
                    .pure[F],
                ):
                  case (effect, session) =>
                    effect.flatMap:
                      case left @ Left(_) =>
                        left.pure[F]
                      case Right((Vector(), persistedCount, rejection)) =>
                        (
                          Vector.empty[MerkleTrieNode.MerkleHash],
                          persistedCount,
                          rejection,
                        )
                          .asRight[SnapshotSyncFailure]
                          .pure[F]
                      case Right((pending, persistedCount, rejection)) =>
                        fetchService
                          .fetchNodes(
                            session = session,
                            chainId = chainId,
                            stateRoot = anchor.stateRoot,
                            hashes = pending,
                          )
                          .flatMap:
                            case Left(fetchRejection) =>
                              (
                                pending,
                                persistedCount,
                                SnapshotSyncFailure(
                                  reason = "snapshotFetchRejected",
                                  detail = fetchRejection.detail.orElse(
                                    Some(fetchRejection.reason),
                                  ),
                                ).some,
                              ).asRight[SnapshotSyncFailure].pure[F]
                            case Right(nodes) =>
                              SnapshotNodeVerifier.verifyBatch(nodes) match
                                case Left(error) =>
                                  error
                                    .asLeft[
                                      (
                                          Vector[
                                            MerkleTrieNode.MerkleHash,
                                          ],
                                          Long,
                                          Option[SnapshotSyncFailure],
                                      ),
                                    ]
                                    .pure[F]
                                case Right(verifiedNodes) =>
                                  nodeStore
                                    .putAll(verifiedNodes)
                                    .as:
                                      val fetchedHashes =
                                        verifiedNodes.iterator.map(_.hash).toSet
                                      (
                                        pending.filterNot(
                                          fetchedHashes.contains,
                                        ),
                                        persistedCount + verifiedNodes.size.toLong,
                                        rejection,
                                      ).asRight[SnapshotSyncFailure]
                .flatMap:
                  case Left(error) =>
                    error.asLeft[Long].pure[F]
                  case Right((remainingAfterRound, persistedCount, rejection))
                      if remainingAfterRound.isEmpty =>
                    persistedCount.asRight[SnapshotSyncFailure].pure[F]
                  case Right((remainingAfterRound, persistedCount, rejection))
                      if roundsLeft > 1 =>
                    runRounds(
                      remaining = remainingAfterRound,
                      roundsLeft = roundsLeft - 1,
                      storedCount = persistedCount,
                      lastRejection = rejection,
                    )
                  case Right((remainingAfterRound, _, Some(rejection))) =>
                    rejection.asLeft[Long].pure[F]
                  case Right((remainingAfterRound, _, None)) =>
                    SnapshotSyncFailure(
                      reason = "snapshotClosureIncomplete",
                      detail =
                        Some(remainingAfterRound.map(_.hex).mkString(",")),
                    ).asLeft[Long].pure[F]

            runRounds(
              remaining = missing,
              roundsLeft = fetchPolicy.maxPeerRounds,
              storedCount = 0L,
              lastRejection = None,
            )

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
              SnapshotSyncResult(
                metadata = metadata,
                fetchedNodeCount = fetchedNodeCount,
              )
                .asRight[SnapshotSyncFailure]
          else
            for
              loadedBefore <- loadNodes(pending)
              fetchResult <-
                if pending.forall(loadedBefore.contains) then
                  0L.asRight[SnapshotSyncFailure].pure[F]
                else fetchMissing(pending.filterNot(loadedBefore.contains))
              result <- fetchResult match
                case Left(error) =>
                  loadNodes(pending).flatMap: loadedAfter =>
                    fail(
                      reason = error.reason,
                      detail = error.detail,
                      verifiedNodeCount =
                        (visited ++ loadedAfter.keySet).size.toLong,
                      pendingNodeCount = pending
                        .count(hash => !loadedAfter.contains(hash))
                        .toLong,
                    )
                case Right(fetchedNow) =>
                  loadNodes(pending).flatMap: loadedAfter =>
                    if pending.exists(hash => !loadedAfter.contains(hash)) then
                      fail(
                        reason = "snapshotClosureIncomplete",
                        detail = Some(
                          pending
                            .filterNot(loadedAfter.contains)
                            .map(_.hex)
                            .mkString(","),
                        ),
                        verifiedNodeCount =
                          (visited ++ loadedAfter.keySet).size.toLong,
                        pendingNodeCount = pending
                          .count(hash => !loadedAfter.contains(hash))
                          .toLong,
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

        (metadataStore.put(initialMetadata) *> loop(
          frontier = Vector(rootHash),
          visited = Set.empty[MerkleTrieNode.MerkleHash],
          fetchedNodeCount = 0L,
        )).handleErrorWith:
          case error: SnapshotStoreFailureException =>
            error.failure.asLeft[SnapshotSyncResult].pure[F]
          case error =>
            Sync[F].raiseError(error)
