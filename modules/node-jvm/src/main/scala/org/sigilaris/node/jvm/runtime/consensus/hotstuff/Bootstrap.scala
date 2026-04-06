package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.Applicative
import cats.syntax.all.*

import org.sigilaris.core.merkle.MerkleTrieNode
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.{CanonicalRejection, ChainId, DirectionalSessionId, PeerIdentity}

sealed trait BootstrapTrustRoot:
  def validatorSet: ValidatorSet
  def anchorWindow: Option[HotStuffWindow]
  def weakSubjectivityFreshUntil: Option[Instant]

  final def validatorSetHash: ValidatorSetHash =
    validatorSet.hash

object BootstrapTrustRoot:
  final case class StaticValidatorSet(
      validatorSet: ValidatorSet,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow] = None
    override val weakSubjectivityFreshUntil: Option[Instant] = None

  final case class TrustedCheckpoint(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow] = Some(window)
    override val weakSubjectivityFreshUntil: Option[Instant] = None

  final case class WeakSubjectivityAnchor(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
      freshUntil: Instant,
  ) extends BootstrapTrustRoot:
    override val anchorWindow: Option[HotStuffWindow] = Some(window)
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

  def staticValidatorSet(
      validatorSet: ValidatorSet,
  ): BootstrapTrustRoot =
    StaticValidatorSet(validatorSet)

  def trustedCheckpoint(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
  ): Either[String, BootstrapTrustRoot] =
    validateRootWindow("trustedCheckpoint", window, validatorSet)
      .map(_ => new TrustedCheckpoint(window, validatorSet))

  def weakSubjectivityAnchor(
      window: HotStuffWindow,
      validatorSet: ValidatorSet,
      freshUntil: Instant,
  ): Either[String, BootstrapTrustRoot] =
    validateRootWindow("weakSubjectivityAnchor", window, validatorSet)
      .map(_ => new WeakSubjectivityAnchor(window, validatorSet, freshUntil))

trait ValidatorSetLookup[F[_]]:
  def trustRoot: BootstrapTrustRoot

  def validatorSetFor(
      window: HotStuffWindow,
  ): F[Either[HotStuffValidationFailure, ValidatorSet]]

object ValidatorSetLookup:
  def static[F[_]: Applicative](
      root: BootstrapTrustRoot,
  ): ValidatorSetLookup[F] =
    fromInventory(root, Vector.empty)

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

final case class FinalizedProof(
    child: Proposal,
    grandchild: Proposal,
)

final case class FinalizedAnchorSuggestion(
    proposal: Proposal,
    finalizedProof: FinalizedProof,
):
  def anchorBlockId: BlockId =
    proposal.targetBlockId

  def anchorHeight: BlockHeight =
    proposal.block.height

  def stateRoot: StateRoot =
    proposal.block.stateRoot

  def snapshotAnchor: SnapshotAnchor =
    SnapshotAnchor(
      chainId = proposal.window.chainId,
      proposalId = proposal.proposalId,
      blockId = proposal.targetBlockId,
      height = proposal.block.height,
      stateRoot = proposal.block.stateRoot,
    )

final case class SnapshotAnchor(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
    height: BlockHeight,
    stateRoot: StateRoot,
)

enum SnapshotStatus:
  case Pending, Syncing, Complete, Failed

final case class SnapshotMetadata(
    anchor: SnapshotAnchor,
    status: SnapshotStatus,
    verifiedNodeCount: Long,
    pendingNodeCount: Long,
    lastUpdatedAt: Instant,
)

final case class SnapshotTrieNode(
    hash: MerkleTrieNode.MerkleHash,
    node: MerkleTrieNode,
)

final case class BootstrapSessionBinding(
    peer: PeerIdentity,
    sessionId: DirectionalSessionId,
    authenticatedPeer: PeerIdentity,
)

object BootstrapSessionBinding:
  def apply(
      peer: PeerIdentity,
      sessionId: DirectionalSessionId,
  ): BootstrapSessionBinding =
    new BootstrapSessionBinding(peer, sessionId, peer)

trait FinalizedAnchorSuggestionService[F[_]]:
  def bestFinalized(
      session: BootstrapSessionBinding,
      chainId: ChainId,
  ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]]

trait SnapshotNodeFetchService[F[_]]:
  def fetchNodes(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      stateRoot: StateRoot,
      hashes: Vector[MerkleTrieNode.MerkleHash],
  ): F[Either[CanonicalRejection, Vector[SnapshotTrieNode]]]

trait ProposalReplayService[F[_]]:
  def readNext(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      anchorBlockId: BlockId,
      nextHeight: BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]]

trait HistoricalBackfillService[F[_]]:
  def readPrevious(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      beforeBlockId: BlockId,
      beforeHeight: BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]]

enum BootstrapPhase:
  case Discovery, SnapshotSync, ForwardCatchUp, Ready

enum BootstrapVoteReadiness:
  case Held(reason: String)
  case Ready

enum HistoricalBackfillPriority:
  case Background, Archive

final case class HistoricalBackfillProgress(
    anchor: SnapshotAnchor,
    nextBeforeBlockId: BlockId,
    nextBeforeHeight: BlockHeight,
    fetchedProposalCount: Long,
    lastUpdatedAt: Instant,
)

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

final case class BootstrapChainDiagnostics(
    bestFinalized: Option[SnapshotAnchor],
    selectedAnchor: Option[SnapshotAnchor],
    pinnedAnchor: Option[SnapshotAnchor],
    voteReadiness: BootstrapVoteReadiness,
    finalizationSafetyFaults: Vector[FinalizedAnchorSafetyFault],
)

final case class BootstrapDiagnostics(
    phase: BootstrapPhase,
    chains: Map[ChainId, BootstrapChainDiagnostics],
    retryAttempts: Int,
    nextRetryAt: Option[Instant],
    lastFailure: Option[String],
    historicalBackfill: HistoricalBackfillStatus,
)

object BootstrapDiagnostics:
  val empty: BootstrapDiagnostics =
    BootstrapDiagnostics(
      phase = BootstrapPhase.Discovery,
      chains = Map.empty[ChainId, BootstrapChainDiagnostics],
      retryAttempts = 0,
      nextRetryAt = None,
      lastFailure = None,
      historicalBackfill = HistoricalBackfillStatus.Idle,
    )

trait BootstrapDiagnosticsSource[F[_]]:
  def current: F[BootstrapDiagnostics]

object BootstrapDiagnosticsSource:
  def const[F[_]: Applicative](
      diagnostics: BootstrapDiagnostics,
  ): BootstrapDiagnosticsSource[F] =
    new BootstrapDiagnosticsSource[F]:
      override def current: F[BootstrapDiagnostics] =
        diagnostics.pure[F]

final case class HotStuffBootstrapServices[F[_]](
    trustRoot: BootstrapTrustRoot,
    validatorSetLookup: ValidatorSetLookup[F],
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotNodeFetch: SnapshotNodeFetchService[F],
    proposalReplay: ProposalReplayService[F],
    historicalBackfill: HistoricalBackfillService[F],
    diagnostics: BootstrapDiagnosticsSource[F],
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffBootstrapTransportServices[F[_]](
    finalizedAnchorSuggestions: FinalizedAnchorSuggestionService[F],
    snapshotNodeFetch: SnapshotNodeFetchService[F],
    proposalReplay: ProposalReplayService[F],
    historicalBackfill: HistoricalBackfillService[F],
    proposalCatchUpReadiness: Option[ProposalCatchUpReadiness[F]] = None,
)

object HotStuffBootstrapTransportServices:
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

object HotStuffBootstrapServices:
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
          Option.empty[FinalizedAnchorSuggestion]
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
      diagnostics = BootstrapDiagnosticsSource.const[F](BootstrapDiagnostics.empty),
    )
