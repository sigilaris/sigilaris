package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.Monad
import cats.effect.kernel.Sync
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockHeight
import org.sigilaris.node.jvm.runtime.gossip.{CanonicalRejection, ChainId}

final case class FinalizedAnchorVerificationFailure(
    reason: String,
    detail: Option[String],
)

object FinalizedAnchorVerificationFailure:
  def fromValidation(
      error: HotStuffValidationFailure,
  ): FinalizedAnchorVerificationFailure =
    FinalizedAnchorVerificationFailure(
      reason = error.reason,
      detail = error.detail,
    )

final case class FinalizedAnchorSafetyFault(
    chainId: ChainId,
    height: BlockHeight,
    conflictingAnchors: Vector[SnapshotAnchor],
):
  def reason: String =
    "conflictingFinalizedSuggestion"

  def detail: Option[String] =
    Some(
      conflictingAnchors
        .map(anchor => anchor.blockId.toHexLower)
        .mkString(","),
    )

object FinalizedAnchorSafetyFault:
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
        .sortBy(anchor => (anchor.blockId.toHexLower, anchor.proposalId.toHexLower))
        .distinctBy(_.blockId.toHexLower),
    )

final case class FinalizationTrackerSnapshot(
    bestFinalized: Option[FinalizedAnchorSuggestion],
    safetyFaults: Vector[FinalizedAnchorSafetyFault],
)

object FinalizationTrackerSnapshot:
  val empty: FinalizationTrackerSnapshot =
    FinalizationTrackerSnapshot(
      bestFinalized = None,
      safetyFaults = Vector.empty[FinalizedAnchorSafetyFault],
    )

object HotStuffFinalizationTracker:
  private val candidateOrdering: Ordering[FinalizedAnchorSuggestion] =
    Ordering.by: suggestion =>
      (
        suggestion.anchorHeight,
        suggestion.anchorBlockId.toHexLower,
        suggestion.finalizedProof.child.proposalId.toHexLower,
        suggestion.finalizedProof.grandchild.proposalId.toHexLower,
      )

  def trackAll(
      proposals: Iterable[Proposal],
  ): Map[ChainId, FinalizationTrackerSnapshot] =
    proposals.iterator
      .toVector
      .groupBy(_.window.chainId)
      .view
      .mapValues(track)
      .toMap

  def track(
      proposals: Iterable[Proposal],
  ): FinalizationTrackerSnapshot =
    val proposalVector = proposals.iterator.toVector
    proposalVector.headOption match
      case None =>
        FinalizationTrackerSnapshot.empty
      case Some(firstProposal) =>
        val byProposalId = proposalVector.iterator.map(p => p.proposalId -> p).toMap
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
                else next
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
            .filterNot(candidate => faultHeights.contains(candidate.anchorHeight))
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

object HotStuffFinalizedAnchorVerifier:
  def verify[F[_]: Monad](
      suggestion: FinalizedAnchorSuggestion,
      validatorSetLookup: ValidatorSetLookup[F],
  ): F[Either[FinalizedAnchorVerificationFailure, FinalizedAnchorSuggestion]] =
    val anchor     = suggestion.proposal
    val child      = suggestion.finalizedProof.child
    val grandchild = suggestion.finalizedProof.grandchild

    for
      anchorSetEither <- validatorSetLookup.validatorSetFor(anchor.window)
      childSetEither <- validatorSetLookup.validatorSetFor(child.window)
      grandchildSetEither <- validatorSetLookup.validatorSetFor(grandchild.window)
    yield
      for
        anchorSet <- anchorSetEither.leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        childSet <- childSetEither.leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        grandchildSet <- grandchildSetEither.leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateQuorumCertificate(anchor.justify, anchorSet)
          .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateQuorumCertificate(child.justify, childSet)
          .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateQuorumCertificate(grandchild.justify, grandchildSet)
          .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateProposal(anchor, anchorSet)
          .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateProposal(child, childSet)
          .leftMap(FinalizedAnchorVerificationFailure.fromValidation)
        _ <- HotStuffValidator
          .validateProposal(grandchild, grandchildSet)
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

  def selectHighestVerified[F[_]: Monad](
      suggestions: Iterable[FinalizedAnchorSuggestion],
      validatorSetLookup: ValidatorSetLookup[F],
  ): F[Either[FinalizedAnchorSafetyFault, Option[FinalizedAnchorSuggestion]]] =
    suggestions.iterator.toVector
      .traverse(verify(_, validatorSetLookup))
      .map: verified =>
        val validSuggestions =
          verified.collect { case Right(suggestion) => suggestion }
        val highestHeight = validSuggestions.iterator.map(_.anchorHeight).maxOption
        highestHeight match
          case None =>
            Option.empty[FinalizedAnchorSuggestion]
              .asRight[FinalizedAnchorSafetyFault]
          case Some(height) =>
            val highest = validSuggestions.filter(_.anchorHeight === height)
            highest.iterator.map(_.anchorBlockId).toSet.sizeCompare(1) match
              case size if size > 0 =>
                highest.headOption match
                  case Some(first) =>
                    FinalizedAnchorSafetyFault.fromSuggestions(
                      chainId = first.proposal.window.chainId,
                      height = height,
                      suggestions = highest,
                    ).asLeft[Option[FinalizedAnchorSuggestion]]
                  case None =>
                    Option.empty[FinalizedAnchorSuggestion]
                      .asRight[FinalizedAnchorSafetyFault]
              case _ =>
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
      snapshot.finalization.getOrElse(chainId, FinalizationTrackerSnapshot.empty) match
        case FinalizationTrackerSnapshot(bestFinalized @ Some(_), _) =>
          bestFinalized.asRight[CanonicalRejection]
        case FinalizationTrackerSnapshot(_, fault +: _) =>
          CanonicalRejection.BackfillUnavailable(
            reason = fault.reason,
            detail = fault.detail,
          ).asLeft[Option[FinalizedAnchorSuggestion]]
        case FinalizationTrackerSnapshot(None, _) =>
          Option.empty[FinalizedAnchorSuggestion]
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

object HotStuffBootstrapServicesRuntime:
  def inMemory[F[_]: Sync](
      validatorSet: ValidatorSet,
      sink: InMemoryHotStuffArtifactSink[F],
  ): HotStuffBootstrapServices[F] =
    val trustRoot = BootstrapTrustRoot.staticValidatorSet(validatorSet)
    HotStuffBootstrapServices(
      trustRoot = trustRoot,
      validatorSetLookup = ValidatorSetLookup.static[F](trustRoot),
      finalizedAnchorSuggestions = new InMemoryFinalizedAnchorSuggestionService(sink),
      snapshotNodeFetch = new SnapshotNodeFetchService[F]:
        override def fetchNodes(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            stateRoot: org.sigilaris.node.jvm.runtime.block.StateRoot,
            hashes: Vector[org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash],
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
          Vector.empty[Proposal].asRight[CanonicalRejection].pure[F]
      ,
      historicalBackfill = new HistoricalBackfillService[F]:
        override def readPrevious(
            session: BootstrapSessionBinding,
            chainId: ChainId,
            beforeBlockId: org.sigilaris.node.jvm.runtime.block.BlockId,
            beforeHeight: org.sigilaris.node.jvm.runtime.block.BlockHeight,
            limit: Int,
        ): F[Either[CanonicalRejection, Vector[Proposal]]] =
          Vector.empty[Proposal].asRight[CanonicalRejection].pure[F]
      ,
      diagnostics = new InMemoryBootstrapDiagnosticsSource(sink),
    )
