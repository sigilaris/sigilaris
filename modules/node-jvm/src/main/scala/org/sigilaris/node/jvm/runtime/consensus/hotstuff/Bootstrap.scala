package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.Applicative
import cats.syntax.all.*

import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.gossip.{
  CanonicalRejection,
  ChainId,
  DirectionalSessionId,
  PeerIdentity,
}

/** The trust root used to bootstrap a node into the consensus protocol. */
sealed trait BootstrapTrustRoot:
  /** The validator set that this trust root guarantees. */
  def validatorSet: ValidatorSet
  /** The optional consensus window anchoring this trust root. */
  def anchorWindow: Option[HotStuffWindow]
  /** The optional expiry instant for weak subjectivity freshness. */
  def weakSubjectivityFreshUntil: Option[Instant]

  /** The hash of the validator set. */
  final def validatorSetHash: ValidatorSetHash =
    validatorSet.hash

/** Companion for `BootstrapTrustRoot`, providing factory methods for different trust root kinds. */
object BootstrapTrustRoot:
  /** A trust root based on a static validator set with no anchor window. */
  final case class StaticValidatorSet(
      validatorSet: ValidatorSet,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow]        = None
    override val weakSubjectivityFreshUntil: Option[Instant] = None

  /** A trust root anchored at a specific consensus window with a trusted checkpoint. */
  final case class TrustedCheckpoint(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow]        = Some(window)
    override val weakSubjectivityFreshUntil: Option[Instant] = None

  /** A trust root with a weak subjectivity anchor that expires at a given instant. */
  final case class WeakSubjectivityAnchor(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
      freshUntil: Instant,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow]        = Some(window)
    override val weakSubjectivityFreshUntil: Option[Instant] = Some(freshUntil)

  private def validateRootWindow(
      label: String,
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ): Either[String, Unit] =
    val windowHash   = window.validatorSetHash.toHexLower
    val materialHash = validatorSet.hash.toHexLower
    Either.cond(
      window.validatorSetHash === validatorSet.hash,
      (),
      label + " validatorSetHash mismatch: window=" + windowHash + ", material=" + materialHash,
    )

  /** Creates a static validator set trust root. */
  def staticValidatorSet(
      validatorSet: ValidatorSet,
  ): BootstrapTrustRoot =
    StaticValidatorSet(validatorSet)

  /** Creates a trusted checkpoint trust root, validating the window against the validator set. */
  def trustedCheckpoint(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ): Either[String, BootstrapTrustRoot] =
    validateRootWindow("trustedCheckpoint", window, validatorSet)
      .map(_ => new TrustedCheckpoint(window, validatorSet))

  /** Creates a weak subjectivity anchor trust root with a freshness deadline. */
  def weakSubjectivityAnchor(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
      freshUntil: Instant,
  ): Either[String, BootstrapTrustRoot] =
    validateRootWindow("weakSubjectivityAnchor", window, validatorSet)
      .map(_ => new WeakSubjectivityAnchor(window, validatorSet, freshUntil))

/** Resolves validator sets for consensus windows, rooted in a bootstrap trust root. */
trait ValidatorSetLookup[F[_]]:
  /** The trust root anchoring validator set resolution. */
  def trustRoot: BootstrapTrustRoot

  /** Looks up the validator set for the given consensus window. */
  def validatorSetFor(
      window: HotStuffWindow,
  ): F[Either[HotStuffValidationFailure, ValidatorSet]]

/** Companion for `ValidatorSetLookup`. */
object ValidatorSetLookup:
  /** Creates a lookup that only knows the trust root's validator set. */
  def static[F[_]: Applicative](
      root: BootstrapTrustRoot,
  ): ValidatorSetLookup[F] =
    fromInventory(root, Vector.empty)

  /** Creates a lookup from a trust root and an inventory of known validator sets. */
  def fromInventory[F[_]: Applicative](
      root: BootstrapTrustRoot,
      validatorSets: Iterable[ValidatorSet],
  ): ValidatorSetLookup[F] =
    val availableValidatorSets =
      (Iterator.single(root.validatorSet) ++ validatorSets.iterator)
        .foldLeft(Map.empty[ValidatorSetHash, ValidatorSet]):
          case (acc, validatorSet) if acc.contains(validatorSet.hash) =>
            acc
          case (acc, validatorSet) =>
            acc.updated(validatorSet.hash, validatorSet)
    new ValidatorSetLookup[F]:
      override val trustRoot: BootstrapTrustRoot = root

      override def validatorSetFor(
          window: HotStuffWindow,
      ): F[Either[HotStuffValidationFailure, ValidatorSet]] =
        Either
          .cond(
            availableValidatorSets.contains(window.validatorSetHash),
            availableValidatorSets(window.validatorSetHash),
            HotStuffValidationFailure(
              reason = "validatorSetUnavailable",
              detail = Some(window.validatorSetHash.toHexLower),
            ),
          )
          .pure[F]

/** A proof of finalization consisting of a child and grandchild proposal that extend an anchor.
  *
  * @param child the child proposal in the finalization chain
  * @param grandchild the grandchild proposal completing the three-chain proof
  */
final case class FinalizedProof(
    child: Proposal,
    grandchild: Proposal,
)

/** A suggestion for a finalized anchor, consisting of a proposal and its three-chain proof.
  *
  * @param proposal the anchor proposal that has been finalized
  * @param finalizedProof the child and grandchild proposals proving finality
  */
final case class FinalizedAnchorSuggestion(
    proposal: Proposal,
    finalizedProof: FinalizedProof,
):
  /** The block ID of the finalized anchor. */
  def anchorBlockId: BlockId =
    proposal.targetBlockId

  /** The block height of the finalized anchor. */
  def anchorHeight: BlockHeight =
    proposal.block.height

  /** The state root of the finalized anchor block. */
  def stateRoot: StateRoot =
    proposal.block.stateRoot

  /** Converts to a snapshot anchor for snapshot sync coordination. */
  def snapshotAnchor: SnapshotAnchor =
    SnapshotAnchor(
      chainId = proposal.window.chainId,
      proposalId = proposal.proposalId,
      blockId = proposal.targetBlockId,
      height = proposal.block.height,
      stateRoot = proposal.block.stateRoot,
    )

/** Identifies a finalized block as an anchor point for state snapshot synchronization.
  *
  * @param chainId the chain this anchor belongs to
  * @param proposalId the proposal that produced this block
  * @param blockId the block identifier
  * @param height the block height
  * @param stateRoot the state root at this block
  */
final case class SnapshotAnchor(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
    height: BlockHeight,
    stateRoot: StateRoot,
)

/** The lifecycle status of a snapshot synchronization operation. */
enum SnapshotStatus:
  /** Snapshot sync has been requested but not yet started. */
  case Pending
  /** Snapshot nodes are actively being fetched. */
  case Syncing
  /** Snapshot sync completed successfully. */
  case Complete
  /** Snapshot sync failed. */
  case Failed

/** Metadata tracking the progress of a snapshot synchronization.
  *
  * @param anchor the snapshot anchor being synced
  * @param status the current sync status
  * @param verifiedNodeCount the number of verified trie nodes
  * @param pendingNodeCount the number of pending trie nodes
  * @param lastUpdatedAt the time of the last status update
  */
final case class SnapshotMetadata(
    anchor: SnapshotAnchor,
    status: SnapshotStatus,
    verifiedNodeCount: Long,
    pendingNodeCount: Long,
    lastUpdatedAt: Instant,
)

/** A Merkle trie node paired with its expected hash, used during snapshot sync.
  *
  * @param hash the expected Merkle hash of the node
  * @param node the trie node data
  */
final case class SnapshotTrieNode(
    hash: MerkleTrieNode.MerkleHash,
    node: MerkleTrieNode,
)

/** Binds a bootstrap session to a peer identity for authenticated communication.
  *
  * @param peer the target peer identity
  * @param sessionId the directional session identifier
  * @param authenticatedPeer the authenticated peer identity
  */
final case class BootstrapSessionBinding(
    peer: PeerIdentity,
    sessionId: DirectionalSessionId,
    authenticatedPeer: PeerIdentity,
)

/** Companion for `BootstrapSessionBinding`. */
object BootstrapSessionBinding:
  def apply(
      peer: PeerIdentity,
      sessionId: DirectionalSessionId,
  ): BootstrapSessionBinding =
    new BootstrapSessionBinding(peer, sessionId, peer)

/** Service for querying the best finalized anchor suggestion from peers. */
trait FinalizedAnchorSuggestionService[F[_]]:
  def bestFinalized(
      session: BootstrapSessionBinding,
      chainId: ChainId,
  ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]]

/** Service for fetching Merkle trie nodes during snapshot synchronization. */
trait SnapshotNodeFetchService[F[_]]:
  def fetchNodes(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      stateRoot: StateRoot,
      hashes: Vector[MerkleTrieNode.MerkleHash],
  ): F[Either[CanonicalRejection, Vector[SnapshotTrieNode]]]

/** Service for replaying proposals after a snapshot anchor during forward catch-up. */
trait ProposalReplayService[F[_]]:
  def readNext(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      anchorBlockId: BlockId,
      nextHeight: BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]]

/** Service for fetching historical proposals before a given block for backfill. */
trait HistoricalBackfillService[F[_]]:
  def readPrevious(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      beforeBlockId: BlockId,
      beforeHeight: BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]]

/** The current phase of the bootstrap process. */
enum BootstrapPhase:
  /** Discovering finalized anchor suggestions from peers. */
  case Discovery
  /** Synchronizing the state snapshot from peers. */
  case SnapshotSync
  /** Replaying proposals to catch up to the chain tip. */
  case ForwardCatchUp
  /** Bootstrap is complete; the node is ready for consensus participation. */
  case Ready

/** Indicates whether a validator node is ready to vote on proposals. */
enum BootstrapVoteReadiness:
  /** Voting is held back for the specified reason (e.g., pending bootstrap). */
  case Held(reason: String)
  /** The node is ready to vote. */
  case Ready

/** Priority level for historical backfill operations. */
enum HistoricalBackfillPriority:
  /** Low-priority background backfill. */
  case Background
  /** High-priority archive backfill. */
  case Archive

/** Tracks the progress of a historical backfill operation.
  *
  * @param anchor the snapshot anchor being backfilled from
  * @param nextBeforeBlockId the next block ID to fetch before
  * @param nextBeforeHeight the next height to fetch before
  * @param fetchedProposalCount total number of proposals fetched so far
  * @param lastUpdatedAt the time of the last progress update
  */
final case class HistoricalBackfillProgress(
    anchor: SnapshotAnchor,
    nextBeforeBlockId: BlockId,
    nextBeforeHeight: BlockHeight,
    fetchedProposalCount: Long,
    lastUpdatedAt: Instant,
)

/** The lifecycle status of a historical backfill operation. */
enum HistoricalBackfillStatus:
  case Idle
  case Disabled(reason: String)
  case Running(
      progress: HistoricalBackfillProgress,
      priority: HistoricalBackfillPriority,
  )
  case Paused(
      reason: String,
      progress: HistoricalBackfillProgress,
      priority: HistoricalBackfillPriority,
  )
  case Completed(
      reason: String,
      progress: HistoricalBackfillProgress,
  )
  case Failed(
      reason: String,
      detail: Option[String],
      progress: HistoricalBackfillProgress,
  )

/** Diagnostics for a single chain's bootstrap progress. */
final case class BootstrapChainDiagnostics(
    bestFinalized: Option[SnapshotAnchor],
    selectedAnchor: Option[SnapshotAnchor],
    pinnedAnchor: Option[SnapshotAnchor],
    voteReadiness: BootstrapVoteReadiness,
    finalizationSafetyFaults: Vector[FinalizedAnchorSafetyFault],
)

/** Aggregate diagnostics for the entire bootstrap process across all chains. */
final case class BootstrapDiagnostics(
    phase: BootstrapPhase,
    chains: Map[ChainId, BootstrapChainDiagnostics],
    retryAttempts: Int,
    nextRetryAt: Option[Instant],
    lastFailure: Option[String],
    historicalBackfill: HistoricalBackfillStatus,
)

/** Companion for `BootstrapDiagnostics`. */
object BootstrapDiagnostics:
  /** An empty diagnostics snapshot with all defaults. */
  val empty: BootstrapDiagnostics =
    BootstrapDiagnostics(
      phase = BootstrapPhase.Discovery,
      chains = Map.empty[ChainId, BootstrapChainDiagnostics],
      retryAttempts = 0,
      nextRetryAt = None,
      lastFailure = None,
      historicalBackfill = HistoricalBackfillStatus.Idle,
    )

/** Provides access to the current bootstrap diagnostics. */
trait BootstrapDiagnosticsSource[F[_]]:
  /** Returns the current bootstrap diagnostics. */
  def current: F[BootstrapDiagnostics]

/** Companion for `BootstrapDiagnosticsSource`. */
object BootstrapDiagnosticsSource:
  /** Creates a diagnostics source that always returns the given constant diagnostics. */
  def const[F[_]: Applicative](
      diagnostics: BootstrapDiagnostics,
  ): BootstrapDiagnosticsSource[F] =
    new BootstrapDiagnosticsSource[F]:
      override def current: F[BootstrapDiagnostics] =
        diagnostics.pure[F]

/** Aggregates all services required for HotStuff consensus bootstrap. */
final case class HotStuffBootstrapServices[F[_]](
    trustRoot: BootstrapTrustRoot,
    validatorSetLookup: ValidatorSetLookup[F],
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotNodeFetch: SnapshotNodeFetchService[F],
    proposalReplay: ProposalReplayService[F],
    historicalBackfill: HistoricalBackfillService[F],
    diagnostics: BootstrapDiagnosticsSource[F],
)

/** Transport-layer bootstrap services, optionally overriding catch-up readiness. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffBootstrapTransportServices[F[_]](
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotNodeFetch: SnapshotNodeFetchService[F],
    proposalReplay: ProposalReplayService[F],
    historicalBackfill: HistoricalBackfillService[F],
    proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
)

/** Companion for `HotStuffBootstrapTransportServices`. */
object HotStuffBootstrapTransportServices:
  /** Creates transport services from full bootstrap services, with no custom readiness. */
  def fromBootstrapServices[F[_]](
      services: HotStuffBootstrapServices[F],
  ): HotStuffBootstrapTransportServices[F] =
    HotStuffBootstrapTransportServices(
      finalizedAnchorSuggestions = services.finalizedAnchorSuggestions,
      snapshotNodeFetch = services.snapshotNodeFetch,
      proposalReplay = services.proposalReplay,
      historicalBackfill = services.historicalBackfill,
      proposalCatchUpReadiness = None,
    )

/** Companion for `HotStuffBootstrapServices`. */
object HotStuffBootstrapServices:
  /** Creates a static bootstrap services instance with no-op transport services. */
  def static[F[_]: Applicative](
      validatorSet: ValidatorSet,
  ): HotStuffBootstrapServices[F] =
    val trustRoot = BootstrapTrustRoot.staticValidatorSet(validatorSet)
    val lookup    = ValidatorSetLookup.static[F](trustRoot)
    HotStuffBootstrapServices(
      trustRoot = trustRoot,
      validatorSetLookup = lookup,
      finalizedAnchorSuggestions = new FinalizedAnchorSuggestionService[F]:
        override def bestFinalized(
            session: BootstrapSessionBinding,
            chainId: ChainId,
        ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
          Option
            .empty[FinalizedAnchorSuggestion]
            .asRight[CanonicalRejection]
            .pure[F]
      ,
      snapshotNodeFetch = new SnapshotNodeFetchService[F]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: StateRoot,
            hashes: Vector[MerkleTrieNode.MerkleHash],
        ): F[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
          Vector.empty[SnapshotTrieNode].asRight[CanonicalRejection].pure[F]
      ,
      proposalReplay = new ProposalReplayService[F]:
        override def readNext(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            anchorBlockId: BlockId,
            nextHeight: BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          Vector.empty[Proposal].asRight[CanonicalRejection].pure[F]
      ,
      historicalBackfill = new HistoricalBackfillService[F]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: BlockId,
            beforeHeight: BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          Vector.empty[Proposal].asRight[CanonicalRejection].pure[F]
      ,
      diagnostics =
        BootstrapDiagnosticsSource.const[F](BootstrapDiagnostics.empty),
    )
