package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import scala.annotation.tailrec

import cats.Monad
import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.gossip.{CanonicalRejection, ChainId}

/** Represents a failure when verifying a finalized anchor suggestion. */
final case class FinalizedAnchorVerificationFailure(
    reason: String,
    detail: Option[String],
)

/** Companion for `FinalizedAnchorVerificationFailure`. */
object FinalizedAnchorVerificationFailure:
  /** Creates from a validation failure. */
  def fromValidation(
      error: HotStuffValidationFailure,
  ): FinalizedAnchorVerificationFailure =
    FinalizedAnchorVerificationFailure(
      reason = error.reason,
      detail = error.detail,
    )

/** Records a safety fault where conflicting blocks were finalized at the same height. */
final case class FinalizedAnchorSafetyFault(
    chainId: ChainId,
    height: BlockHeight,
    conflictingAnchors: Vector[SnapshotAnchor],
):
  /** The reason identifier for this safety fault. */
  def reason: String =
    "conflictingFinalizedSuggestion"

  /** Detail string listing the conflicting block IDs. */
  def detail: Option[String] =
    Some(
      conflictingAnchors
        .map(anchor => anchor.blockId.toHexLower)
        .mkString(","),
    )

/** Companion for `FinalizedAnchorSafetyFault`. */
object FinalizedAnchorSafetyFault:
  /** Creates a safety fault from conflicting finalized suggestions at the same height. */
  def fromSuggestions(
      chainId: ChainId,
      height: BlockHeight,
      suggestions: Iterable[FinalizedAnchorSuggestion],
  ): FinalizedAnchorSafetyFault =
    FinalizedAnchorSafetyFault(
      chainId = chainId,
      height = height,
      conflictingAnchors = suggestions.iterator
        .map(_.snapshotAnchor)
        .toVector
        .sortBy(anchor =>
          (anchor.blockId.toHexLower, anchor.proposalId.toHexLower),
        )
        .distinctBy(_.blockId.toHexLower),
    )

/** A snapshot of finalization tracking state, including the best finalized and any safety faults. */
final case class FinalizationTrackerSnapshot(
    bestFinalized: Option[FinalizedAnchorSuggestion],
    safetyFaults: Vector[FinalizedAnchorSafetyFault],
)

/** Companion for `FinalizationTrackerSnapshot`. */
object FinalizationTrackerSnapshot:
  /** An empty finalization snapshot with no finalized anchor and no faults. */
  val empty: FinalizationTrackerSnapshot =
    FinalizationTrackerSnapshot(
      bestFinalized = None,
      safetyFaults = Vector.empty[FinalizedAnchorSafetyFault],
    )

/** Tracks finalization by detecting three-chain commit proofs from observed proposals. */
object HotStuffFinalizationTracker:
  private val candidateOrdering: Ordering[FinalizedAnchorSuggestion] =
    Ordering.by: suggestion =>
      (
        suggestion.anchorHeight,
        suggestion.anchorBlockId.toHexLower,
        suggestion.finalizedProof.child.proposalId.toHexLower,
        suggestion.finalizedProof.grandchild.proposalId.toHexLower,
      )

  /** Tracks finalization across all chains from the given proposals. */
  def trackAll(
      proposals: Iterable[Proposal],
  ): Map[ChainId, FinalizationTrackerSnapshot] =
    proposals.iterator.toVector
      .groupBy(_.window.chainId)
      .view
      .mapValues(track)
      .toMap

  /** Tracks finalization for a single chain from the given proposals. */
  def track(
      proposals: Iterable[Proposal],
  ): FinalizationTrackerSnapshot =
    val proposalVector = proposals.iterator.toVector
    proposalVector.headOption match
      case None =>
        FinalizationTrackerSnapshot.empty
      case Some(firstProposal) =>
        val byProposalId =
          proposalVector.iterator.map(p => p.proposalId -> p).toMap
        val candidates = proposalVector.flatMap: grandchild =>
          for
            child <- byProposalId.get(grandchild.justify.subject.proposalId)
            if proposalMatchesSubject(child, grandchild.justify.subject)
            anchor <- byProposalId.get(child.justify.subject.proposalId)
            if proposalMatchesSubject(anchor, child.justify.subject)
            if heightsStrictlyIncrease(anchor, child, grandchild)
          yield FinalizedAnchorSuggestion(
            proposal = anchor,
            finalizedProof = FinalizedProof(
              child = child,
              grandchild = grandchild,
            ),
          )
        val canonicalCandidates =
          candidates
            .groupBy(_.anchorBlockId)
            .valuesIterator
            .flatMap(
              _.reduceLeftOption: (current, next) =>
                if candidateOrdering.lteq(current, next) then current
                else next,
            )
            .toVector
        val safetyFaults =
          canonicalCandidates
            .groupBy(_.anchorHeight)
            .valuesIterator
            .flatMap: sameHeight =>
              val uniqueBlockIds =
                sameHeight.iterator.map(_.anchorBlockId).toSet
              if uniqueBlockIds.sizeCompare(1) > 0 then
                sameHeight.headOption.map: suggestion =>
                  FinalizedAnchorSafetyFault.fromSuggestions(
                    chainId = suggestion.proposal.window.chainId,
                    height = suggestion.anchorHeight,
                    suggestions = sameHeight,
                  )
              else None
            .toVector
            .sortBy(fault => (fault.height, fault.chainId.value))
        val faultHeights = safetyFaults.iterator.map(_.height).toSet
        val bestFinalized =
          canonicalCandidates
            .filterNot(candidate =>
              faultHeights.contains(candidate.anchorHeight),
            )
            .maxOption(using candidateOrdering)
        FinalizationTrackerSnapshot(
          bestFinalized = bestFinalized,
          safetyFaults = safetyFaults,
        )

  private def proposalMatchesSubject(
      proposal: Proposal,
      subject: QuorumCertificateSubject,
  ): Boolean =
    proposal.window === subject.window &&
      proposal.proposalId === subject.proposalId &&
      proposal.targetBlockId === subject.blockId

  private def heightsStrictlyIncrease(
      anchor: Proposal,
      child: Proposal,
      grandchild: Proposal,
  ): Boolean =
    Ordering[BlockHeight].lt(anchor.block.height, child.block.height) &&
      Ordering[BlockHeight].lt(child.block.height, grandchild.block.height)

/** Verifies finalized anchor suggestions against the validator set and selects the highest verified. */
object HotStuffFinalizedAnchorVerifier:
  /** Verifies a finalized anchor suggestion by checking all three proposals and their QCs. */
  def verify[F[_]: Monad](
      suggestion: FinalizedAnchorSuggestion,
      validatorSetLookup: ValidatorSetLookup[F],
  ): F[Either[FinalizedAnchorVerificationFailure, FinalizedAnchorSuggestion]] =
    val anchor     = suggestion.proposal
    val child      = suggestion.finalizedProof.child
    val grandchild = suggestion.finalizedProof.grandchild

    for
      anchorProposalSetEither <- validatorSetLookup.validatorSetFor:
        anchor.window
      childProposalSetEither <- validatorSetLookup.validatorSetFor(child.window)
      grandchildProposalSetEither <- validatorSetLookup.validatorSetFor:
        grandchild.window
      anchorQcSetEither <- validatorSetLookup.validatorSetFor:
        anchor.justify.subject.window
      childQcSetEither <- validatorSetLookup.validatorSetFor:
        child.justify.subject.window
      grandchildQcSetEither <- validatorSetLookup.validatorSetFor:
        grandchild.justify.subject.window
    yield for
      anchorProposalSet <- anchorProposalSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      childProposalSet <- childProposalSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      grandchildProposalSet <- grandchildProposalSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      anchorQcSet <- anchorQcSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      childQcSet <- childQcSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      grandchildQcSet <- grandchildQcSetEither.leftMap(
        FinalizedAnchorVerificationFailure.fromValidation,
      )
      _ <- HotStuffValidator
        .validateQuorumCertificate(anchor.justify, anchorQcSet)
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- HotStuffValidator
        .validateQuorumCertificate(child.justify, childQcSet)
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- HotStuffValidator
        .validateQuorumCertificate(grandchild.justify, grandchildQcSet)
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- HotStuffValidator
        .validateProposal(
          anchor,
          anchorProposalSet,
          justifyValidatorSet = Some(anchorQcSet),
        )
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- HotStuffValidator
        .validateProposal(
          child,
          childProposalSet,
          justifyValidatorSet = Some(childQcSet),
        )
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- HotStuffValidator
        .validateProposal(
          grandchild,
          grandchildProposalSet,
          justifyValidatorSet = Some(grandchildQcSet),
        )
        .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
      _ <- ensure(
        proposalMatchesSubject(child.justify.subject, anchor),
        "finalizedProofChildMismatch",
        Some(anchor.proposalId.toHexLower),
      )
      _ <- ensure(
        proposalMatchesSubject(grandchild.justify.subject, child),
        "finalizedProofGrandchildMismatch",
        Some(child.proposalId.toHexLower),
      )
      _ <- ensure(
        Ordering[BlockHeight].lt(anchor.block.height, child.block.height) &&
          Ordering[BlockHeight].lt(child.block.height, grandchild.block.height),
        "finalizedProofHeightOrderMismatch",
        Some(
          ss"${anchor.block.height.render}:${child.block.height.render}:${grandchild.block.height.render}",
        ),
      )
    yield suggestion

  /** Selects the highest verified finalized anchor from multiple suggestions, detecting safety faults. */
  def selectHighestVerified[F[_]: Monad](
      suggestions: Iterable[FinalizedAnchorSuggestion],
      validatorSetLookup: ValidatorSetLookup[F],
  ): F[Either[FinalizedAnchorSafetyFault, Option[FinalizedAnchorSuggestion]]] =
    suggestions.iterator.toVector
      .traverse(verify(_, validatorSetLookup))
      .map: verified =>
        val validSuggestions =
          verified.collect { case Right(suggestion) => suggestion }
        val highestHeight =
          validSuggestions.iterator.map(_.anchorHeight).maxOption
        highestHeight match
          case None =>
            Option
              .empty[FinalizedAnchorSuggestion]
              .asRight[FinalizedAnchorSafetyFault]
          case Some(height) =>
            val highest = validSuggestions.filter(_.anchorHeight === height)
            highest.iterator.map(_.anchorBlockId).toSet.sizeCompare(1) match
              case size if size > 0 =>
                highest.headOption match
                  case Some(first) =>
                    FinalizedAnchorSafetyFault
                      .fromSuggestions(
                        chainId = first.proposal.window.chainId,
                        height = height,
                        suggestions = highest,
                      )
                      .asLeft[Option[FinalizedAnchorSuggestion]]
                  case None =>
                    Option
                      .empty[FinalizedAnchorSuggestion]
                      .asRight[FinalizedAnchorSafetyFault]
              case _ =>
                // Once all highest verified suggestions point at the same anchor
                // height and block, any deterministic pick is equivalent for the
                // current static-trust-root bootstrap session. Use a canonical
                // lexicographic order to keep selection stable across peers/tests.
                val highestOrdering =
                  Ordering.by[FinalizedAnchorSuggestion, (String, String)]:
                    suggestion =>
                      (
                        suggestion.anchorBlockId.toHexLower,
                        suggestion.finalizedProof.child.proposalId.toHexLower,
                      )
                highest
                  .minOption(using highestOrdering)
                  .asRight[FinalizedAnchorSafetyFault]

  private def ensure(
      condition: Boolean,
      reason: String,
      detail: Option[String],
  ): Either[FinalizedAnchorVerificationFailure, Unit] =
    Either.cond(
      condition,
      (),
      FinalizedAnchorVerificationFailure(
        reason = reason,
        detail = detail,
      ),
    )

  private def proposalMatchesSubject(
      subject: QuorumCertificateSubject,
      proposal: Proposal,
  ): Boolean =
    subject.window === proposal.window &&
      subject.proposalId === proposal.proposalId &&
      subject.blockId === proposal.targetBlockId

private final class InMemoryFinalizedAnchorSuggestionService[F[_]: Sync](
    sink: InMemoryHotStuffArtifactSink[F],
) extends FinalizedAnchorSuggestionService[F]:
  override def bestFinalized(
      session: BootstrapSessionBinding,
      chainId: ChainId,
  ): F[Either[CanonicalRejection, Option[FinalizedAnchorSuggestion]]] =
    sink.snapshot.map: snapshot =>
      snapshot.finalization.getOrElse(
        chainId,
        FinalizationTrackerSnapshot.empty,
      ) match
        case FinalizationTrackerSnapshot(bestFinalized @ Some(_), _) =>
          bestFinalized.asRight[CanonicalRejection]
        case FinalizationTrackerSnapshot(_, fault +: _) =>
          CanonicalRejection
            .BackfillUnavailable(
              reason = fault.reason,
              detail = fault.detail,
            )
            .asLeft[Option[FinalizedAnchorSuggestion]]
        case FinalizationTrackerSnapshot(None, _) =>
          Option
            .empty[FinalizedAnchorSuggestion]
            .asRight[CanonicalRejection]

private final class InMemoryBootstrapDiagnosticsSource[F[_]: Sync](
    sink: InMemoryHotStuffArtifactSink[F],
) extends BootstrapDiagnosticsSource[F]:
  override def current: F[BootstrapDiagnostics] =
    sink.snapshot.map: snapshot =>
      BootstrapDiagnostics(
        phase = BootstrapPhase.Discovery,
        chains = snapshot.finalization.view
          .mapValues: finalization =>
            BootstrapChainDiagnostics(
              bestFinalized = finalization.bestFinalized.map(_.snapshotAnchor),
              selectedAnchor = None,
              pinnedAnchor = None,
              voteReadiness = BootstrapVoteReadiness.Held("snapshotPending"),
              finalizationSafetyFaults = finalization.safetyFaults,
            )
          .toMap,
        retryAttempts = 0,
        nextRetryAt = None,
        lastFailure = None,
        historicalBackfill = HistoricalBackfillStatus.Idle,
      )

private final class InMemoryProposalReplayService[F[_]: Sync](
    sink: InMemoryHotStuffArtifactSink[F],
) extends ProposalReplayService[F]:
  override def readNext(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
      nextHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]] =
    sink.snapshot.map: snapshot =>
      val proposalsByChainAndBlockId =
        snapshot.proposals.valuesIterator
          .map(proposal =>
            (proposal.window.chainId, proposal.targetBlockId) -> proposal,
          )
          .toMap
      if !proposalsByChainAndBlockId.contains(chainId -> anchorBlockId) then
        CanonicalRejection
          .BackfillUnavailable(
            reason = "proposalReplayAnchorUnknown",
            detail = Some(anchorBlockId.toHexLower),
          )
          .asLeft[Vector[Proposal]]
      else
        snapshot.proposals.valuesIterator
          .filter(_.window.chainId === chainId)
          .filter(proposal =>
            Ordering[BlockHeight].gteq(proposal.block.height, nextHeight),
          )
          .filter(proposal =>
            descendsFromAnchor(
              proposal = proposal,
              anchorBlockId = anchorBlockId,
              proposalsByChainAndBlockId = proposalsByChainAndBlockId,
            ),
          )
          .toVector
          .sortBy(proposal =>
            (proposal.block.height, proposal.proposalId.toHexLower),
          )
          .take(limit.max(0))
          .asRight[CanonicalRejection]

private final class InMemoryHistoricalBackfillService[F[_]: Sync](
    sink: InMemoryHotStuffArtifactSink[F],
) extends HistoricalBackfillService[F]:
  override def readPrevious(
      session: BootstrapSessionBinding,
      chainId: ChainId,
      beforeBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
      beforeHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
      limit: Int,
  ): F[Either[CanonicalRejection, Vector[Proposal]]] =
    sink.snapshot.map: snapshot =>
      val proposalsByBlockId =
        snapshot.proposals.valuesIterator.toVector.groupBy(_.targetBlockId)
      proposalsByBlockId.get(beforeBlockId) match
        case None =>
          CanonicalRejection
            .BackfillUnavailable(
              reason = "historicalBackfillAnchorUnknown",
              detail = Some(beforeBlockId.toHexLower),
            )
            .asLeft[Vector[Proposal]]
        case Some(candidates)
            if !candidates.exists(_.window.chainId === chainId) =>
          CanonicalRejection
            .BackfillUnavailable(
              reason = "historicalBackfillAnchorChainMismatch",
              detail = Some(beforeBlockId.toHexLower),
            )
            .asLeft[Vector[Proposal]]
        case Some(candidates) =>
          val proposalsByChainAndBlockId =
            snapshot.proposals.valuesIterator
              .map(proposal =>
                (proposal.window.chainId, proposal.targetBlockId) -> proposal,
              )
              .toMap
          candidates.find(_.window.chainId === chainId) match
            case Some(beforeProposal) =>
              if beforeProposal.block.height =!= beforeHeight then
                CanonicalRejection
                  .BackfillUnavailable(
                    reason = "historicalBackfillHeightMismatch",
                    detail = Some(
                      ss"expected=${beforeHeight.render} actual=${beforeProposal.block.height.render}",
                    ),
                  )
                  .asLeft[Vector[Proposal]]
              else
                ancestorChain(
                  start = beforeProposal,
                  chainId = chainId,
                  proposalsByChainAndBlockId = proposalsByChainAndBlockId,
                )
                  .take(limit.max(0))
                  .asRight[CanonicalRejection]
            case None =>
              CanonicalRejection
                .BackfillUnavailable(
                  reason = "historicalBackfillAnchorChainMismatch",
                  detail = Some(beforeBlockId.toHexLower),
                )
                .asLeft[Vector[Proposal]]

private def descendsFromAnchor(
    proposal: Proposal,
    anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
    proposalsByChainAndBlockId: Map[
      (ChainId, org.sigilaris.node.jvm.runtime.block.BlockId),
      Proposal,
    ],
): Boolean =
  @tailrec
  def loop(
      currentBlockId: Option[org.sigilaris.node.jvm.runtime.block.BlockId],
  ): Boolean =
    currentBlockId match
      case Some(blockId) if blockId === anchorBlockId =>
        true
      case Some(blockId) =>
        proposalsByChainAndBlockId.get(proposal.window.chainId -> blockId) match
          case Some(currentProposal) =>
            loop(currentProposal.block.parent)
          case None =>
            false
      case None =>
        false

  loop(proposal.block.parent)

private def ancestorChain(
    start: Proposal,
    chainId: ChainId,
    proposalsByChainAndBlockId: Map[
      (ChainId, org.sigilaris.node.jvm.runtime.block.BlockId),
      Proposal,
    ],
): Vector[Proposal] =
  @tailrec
  def loop(
      current: Proposal,
      acc: List[Proposal],
  ): List[Proposal] =
    current.block.parent
      .flatMap(parentBlockId =>
        proposalsByChainAndBlockId.get(chainId -> parentBlockId),
      ) match
      case Some(parentProposal) =>
        loop(parentProposal, parentProposal :: acc)
      case None =>
        acc

  loop(start, Nil).reverse.toVector

/** Factory for constructing bootstrap services backed by in-memory artifact sinks. */
object HotStuffBootstrapServicesRuntime:
  /** Creates in-memory bootstrap services from a static validator set. */
  def inMemory[F[_]: Sync](
      validatorSet: ValidatorSet,
      sink: InMemoryHotStuffArtifactSink[F],
  ): HotStuffBootstrapServices[F] =
    fromTrustRootWithNodeStore(
      trustRoot = BootstrapTrustRoot.staticValidatorSet(validatorSet),
      validatorSetInventory = Vector.empty[ValidatorSet],
      sink = sink,
      snapshotNodeStore = none[SnapshotNodeStore[F]],
      diagnostics = new InMemoryBootstrapDiagnosticsSource(sink),
    )

  /** Creates bootstrap services from a trust root with historical validator sets. */
  def fromTrustRoot[F[_]: Sync](
      trustRoot: BootstrapTrustRoot,
      validatorSetInventory: Vector[ValidatorSet],
      sink: InMemoryHotStuffArtifactSink[F],
  ): HotStuffBootstrapServices[F] =
    fromTrustRootWithNodeStore(
      trustRoot = trustRoot,
      validatorSetInventory = validatorSetInventory,
      sink = sink,
      snapshotNodeStore = none[SnapshotNodeStore[F]],
      diagnostics = new InMemoryBootstrapDiagnosticsSource(sink),
    )

  /** Creates in-memory bootstrap services with an optional snapshot node store. */
  def inMemoryWithNodeStore[F[_]: Sync](
      validatorSet: ValidatorSet,
      sink: InMemoryHotStuffArtifactSink[F],
      snapshotNodeStore: Option[SnapshotNodeStore[F]],
      diagnostics: BootstrapDiagnosticsSource[F],
  ): HotStuffBootstrapServices[F] =
    fromTrustRootWithNodeStore(
      trustRoot = BootstrapTrustRoot.staticValidatorSet(validatorSet),
      validatorSetInventory = Vector.empty[ValidatorSet],
      sink = sink,
      snapshotNodeStore = snapshotNodeStore,
      diagnostics = diagnostics,
    )

  /** Creates bootstrap services from a trust root with full configuration options. */
  def fromTrustRootWithNodeStore[F[_]: Sync](
      trustRoot: BootstrapTrustRoot,
      validatorSetInventory: Vector[ValidatorSet],
      sink: InMemoryHotStuffArtifactSink[F],
      snapshotNodeStore: Option[SnapshotNodeStore[F]],
      diagnostics: BootstrapDiagnosticsSource[F],
  ): HotStuffBootstrapServices[F] =
    HotStuffBootstrapServices(
      trustRoot = trustRoot,
      validatorSetLookup =
        ValidatorSetLookup.fromInventory[F](trustRoot, validatorSetInventory),
      finalizedAnchorSuggestions =
        new InMemoryFinalizedAnchorSuggestionService(sink),
      snapshotNodeFetch = snapshotNodeStore match
        case Some(nodeStore) =>
          SnapshotNodeFetchServiceRuntime.fromNodeStore(nodeStore)
        case None =>
          new SnapshotNodeFetchService[F]:
            override def fetchNodes(
                session: BootstrapSessionBinding,
                chainId: ChainId,
                stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
                hashes: Vector[
                  org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash,
                ],
            ): F[Either[CanonicalRejection, Vector[SnapshotTrieNode]]] =
              Vector.empty[SnapshotTrieNode].asRight[CanonicalRejection].pure[F]
      ,
      proposalReplay = new ProposalReplayService[F]:
        override def readNext(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            anchorBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
            nextHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          new InMemoryProposalReplayService(sink)
            .readNext(session, chainId, anchorBlockId, nextHeight, limit)
      ,
      historicalBackfill = new HistoricalBackfillService[F]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
            beforeHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          new InMemoryHistoricalBackfillService(sink)
            .readPrevious(session, chainId, beforeBlockId, beforeHeight, limit)
      ,
      diagnostics = diagnostics,
    )
