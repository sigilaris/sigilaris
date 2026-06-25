package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.effect.kernel.syntax.all.*
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockId,
  BlockHeader,
  BlockHeight,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.gossip.*

/** Identifies a pacemaker entry by chain and local validator. */
final case class HotStuffPacemakerKey(
    chainId: ChainId,
    localValidator: ValidatorId,
)

/** A snapshot of a single pacemaker entry's state for diagnostics. */
final case class HotStuffPacemakerEntrySnapshot(
    state: Option[HotStuffPacemakerState],
    diagnostics: Vector[HotStuffPacemakerDiagnostic],
    proposalEligibility: Option[HotStuffPacemakerProposalEligibility],
    emittedProposalWindow: Option[HotStuffWindow],
    proposalEmissionReservationWindow: Option[HotStuffWindow],
)

/** A snapshot of all pacemaker entries for diagnostics and testing. */
final case class HotStuffPacemakerRuntimeSnapshot(
    entries: Map[HotStuffPacemakerKey, HotStuffPacemakerEntrySnapshot],
)

/** Companion for `HotStuffPacemakerRuntimeSnapshot`. */
object HotStuffPacemakerRuntimeSnapshot:
  /** An empty pacemaker runtime snapshot. */
  val empty: HotStuffPacemakerRuntimeSnapshot =
    HotStuffPacemakerRuntimeSnapshot(
      entries = Map.empty[HotStuffPacemakerKey, HotStuffPacemakerEntrySnapshot],
    )

private final case class AutomaticProposalSeed(
    domain: Utf8,
    chainId: ChainId,
    window: HotStuffWindow,
    leader: ValidatorId,
    highestKnownQc: QuorumCertificateSubject,
) derives ByteEncoder

private final case class ObservedEventQueue(
    processing: Boolean,
    pending: Vector[GossipEvent[HotStuffGossipArtifact]],
)

private final case class FinalityDriveAnchorKey(
    chainId: ChainId,
    proposalId: ProposalId,
    blockId: BlockId,
    height: BlockHeight,
)

private object FinalityDriveFinalization:
  def finalizedBlockId(
      anchor: HotStuffFinalityDriveAnchor,
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
  ): Option[BlockId] =
    // The proposal request branch context is canonical for the candidate
    // parent branch, so any best finalized height at or beyond the anchor
    // height stops drive for anchors on that branch.
    finalization
      .get(anchor.chainId)
      .flatMap(_.bestFinalized)
      .filter(finalized =>
        summon[Ordering[BlockHeight]].gteq(
          finalized.anchorHeight,
          anchor.height,
        ),
      )
      .map(_.anchorBlockId)

private final case class FinalityDriveAttemptState(
    anchor: HotStuffFinalityDriveAnchor,
    reportingKey: HotStuffPacemakerKey,
    firstRequestedAt: Instant,
    attempts: Int,
)

private final case class FinalityDriveSuppression(
    anchor: HotStuffFinalityDriveAnchor,
    reason: String,
    detail: Option[String],
)

private enum FinalityDriveRequestDecision:
  case Requested(hint: HotStuffProposalInputFinalityDrive)
  case Suppressed(suppression: FinalityDriveSuppression)

private enum FinalityDriveDiagnosticEvent:
  case Requested(hint: HotStuffProposalInputFinalityDrive)
  case Suppressed(suppression: FinalityDriveSuppression)
  case TargetFinalized(
      reportingKey: HotStuffPacemakerKey,
      anchor: HotStuffFinalityDriveAnchor,
      finalizedBlockId: BlockId,
  )

private final case class FinalityDriveRuntimeState(
    attemptsByAnchor: Map[FinalityDriveAnchorKey, FinalityDriveAttemptState],
):
  def pruned(
      finalization: Map[ChainId, FinalizationTrackerSnapshot],
  ): (
      FinalityDriveRuntimeState,
      Vector[
        (HotStuffPacemakerKey, HotStuffFinalityDriveAnchor, BlockId),
      ],
  ) =
    val (finalized, active) =
      attemptsByAnchor.partition { case (_, state) =>
        FinalityDriveFinalization
          .finalizedBlockId(state.anchor, finalization)
          .isDefined
      }
    val finalizedAnchors =
      finalized.values.toVector.flatMap(state =>
        FinalityDriveFinalization
          .finalizedBlockId(state.anchor, finalization)
          .map(finalizedBlockId =>
            (state.reportingKey, state.anchor, finalizedBlockId),
          ),
      )
    copy(attemptsByAnchor = active) -> finalizedAnchors

  def requestDrive(
      policy: HotStuffFinalityDrivePolicy,
      candidate: HotStuffFinalityDriveCandidate,
      reportingKey: HotStuffPacemakerKey,
      now: Instant,
  ): (FinalityDriveRuntimeState, FinalityDriveRequestDecision) =
    val key     = FinalityDriveAnchorKey.fromAnchor(candidate.anchor)
    val current = attemptsByAnchor.get(key)
    val firstRequestedAt =
      current.fold(now)(_.firstRequestedAt)
    val rawElapsed =
      Duration.between(firstRequestedAt, now)
    val elapsed =
      if rawElapsed.isNegative then Duration.ZERO else rawElapsed
    if current.exists(_.attempts >= policy.maxAttemptsPerAnchor) ||
      elapsed.compareTo(policy.maxElapsed) >= 0
    then
      // Exhausted entries stay recorded until finalization so later retries for
      // the same anchor continue to suppress drive hints.
      val suppression =
        if current.exists(_.attempts >= policy.maxAttemptsPerAnchor) then
          FinalityDriveSuppression(
            anchor = candidate.anchor,
            reason = "finalityDriveAttemptBoundExhausted",
            detail = Some(
              ss"attempts=${current.fold(0)(_.attempts).toString};max=${policy.maxAttemptsPerAnchor.toString}",
            ),
          )
        else
          FinalityDriveSuppression(
            anchor = candidate.anchor,
            reason = "finalityDriveElapsedBoundExhausted",
            detail = Some(
              ss"elapsedMillis=${elapsed.toMillis.toString};maxElapsedMillis=${policy.maxElapsed.toMillis.toString}",
            ),
          )
      this -> FinalityDriveRequestDecision.Suppressed(suppression)
    else
      val attempt = current.fold(1)(_.attempts + 1)
      copy(
        attemptsByAnchor = attemptsByAnchor.updated(
          key,
          FinalityDriveAttemptState(
            anchor = candidate.anchor,
            reportingKey = reportingKey,
            firstRequestedAt = firstRequestedAt,
            attempts = attempt,
          ),
        ),
      ) ->
        FinalityDriveRequestDecision.Requested(
          HotStuffProposalInputFinalityDrive(
            anchor = candidate.anchor,
            descendantDepthAfterProposal =
              candidate.descendantDepthAfterProposal,
            maxDescendantDepth = policy.maxDescendantDepth,
            attempt = attempt,
            maxAttemptsPerAnchor = policy.maxAttemptsPerAnchor,
            elapsed = elapsed,
          ),
        )

private object FinalityDriveAnchorKey:
  def fromAnchor(
      anchor: HotStuffFinalityDriveAnchor,
  ): FinalityDriveAnchorKey =
    FinalityDriveAnchorKey(
      chainId = anchor.chainId,
      proposalId = anchor.proposalId,
      blockId = anchor.blockId,
      height = anchor.height,
    )

private object FinalityDriveRuntimeState:
  val empty: FinalityDriveRuntimeState =
    FinalityDriveRuntimeState(
      attemptsByAnchor =
        Map.empty[FinalityDriveAnchorKey, FinalityDriveAttemptState],
    )

private object HotStuffPacemakerEntrySnapshot:
  val empty: HotStuffPacemakerEntrySnapshot =
    HotStuffPacemakerEntrySnapshot(
      state = None,
      diagnostics = Vector.empty[HotStuffPacemakerDiagnostic],
      proposalEligibility = None,
      emittedProposalWindow = None,
      proposalEmissionReservationWindow = None,
    )

private final class HotStuffPacemakerAwareSource[F[_]: Sync](
    underlying: GossipArtifactSource[F, HotStuffGossipArtifact],
    drivePending: F[Unit],
) extends GossipArtifactSource[F, HotStuffGossipArtifact]:

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[
    AvailableGossipEvent[HotStuffGossipArtifact],
  ]]] =
    drivePending *> underlying.readAfter(chainId, topic, cursor)

  override def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[HotStuffGossipArtifact]]] =
    drivePending *> underlying.readByIds(chainId, topic, ids)

private final class HotStuffPacemakerAwareSink[F[_]: Sync](
    underlying: GossipArtifactSink[F, HotStuffGossipArtifact],
    observeApplied: GossipEvent[HotStuffGossipArtifact] => F[Unit],
) extends GossipArtifactSink[F, HotStuffGossipArtifact]:

  override def applyEvent(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    underlying
      .applyEvent(event)
      .flatTap:
        case Right(result) if result.applied =>
          observeApplied(event)
        case _ =>
          Sync[F].unit

private final class InMemoryHotStuffPacemakerDriver[F[_]: Sync](
    bootstrapInput: HotStuffRuntimeBootstrapInput,
    publisher: HotStuffArtifactPublisher[F],
    sink: InMemoryHotStuffArtifactSink[F],
    bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]],
    stateRef: Ref[F, Map[HotStuffPacemakerKey, HotStuffPacemakerEntrySnapshot]],
    observationRef: Ref[F, ObservedEventQueue],
    txUniquenessCacheRef: Ref[F, HotStuffProposalTxUniquenessCache],
    finalityDriveStateRef: Ref[F, FinalityDriveRuntimeState],
    automaticConsensus: Boolean,
    proposalInputProviderOverride: Option[HotStuffProposalInputProvider[F]],
    proposalInputFallbackPolicy: HotStuffProposalInputFallbackPolicy,
    proposalValidationConfig: HotStuffProposalValidationRuntimeConfig[F],
    txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig,
    pacemakerPolicy: HotStuffPacemakerPolicy,
    finalityDrivePolicy: HotStuffFinalityDrivePolicy,
)(using
    clock: GossipClock[F],
):
  private val localValidators =
    bootstrapInput.localKeys.keys.toVector.sortBy(_.value)
  private val AutomaticProposalStateRootDomain =
    Utf8("sigilaris.hotstuff.auto-proposal.state-root.v1")
  private val AutomaticProposalBodyRootDomain =
    Utf8("sigilaris.hotstuff.auto-proposal.body-root.v1")

  def snapshot: F[HotStuffPacemakerRuntimeSnapshot] =
    stateRef.get.map(HotStuffPacemakerRuntimeSnapshot(_))

  def observeApplied(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[Unit] =
    observationRef
      .modify: queue =>
        val updated =
          queue.copy(pending = queue.pending :+ event)
        if queue.processing then updated     -> false
        else updated.copy(processing = true) -> true
      .flatMap:
        case true =>
          // Derived artifacts are queued and drained in FIFO order after the
          // current observation completes, which avoids re-entering the driver.
          drainObservedEvents
        case false =>
          Sync[F].unit

  private def drainObservedEvents: F[Unit] =
    Sync[F].tailRecM(()): _ =>
      observationRef
        .modify:
          case ObservedEventQueue(true, head +: tail) =>
            ObservedEventQueue(
              processing = true,
              pending = tail,
            ) -> head.some
          case ObservedEventQueue(true, Vector()) =>
            ObservedEventQueue(
              processing = false,
              pending = Vector.empty[GossipEvent[HotStuffGossipArtifact]],
            ) -> none[GossipEvent[HotStuffGossipArtifact]]
          case queue =>
            queue.copy(processing = false) -> none[GossipEvent[
              HotStuffGossipArtifact,
            ]]
        .flatMap:
          case Some(event) =>
            processObservedEvent(event).as(Left[Unit, Unit](()))
          case None =>
            Right[Unit, Unit](()).pure[F]

  private def processObservedEvent(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[Unit] =
    clock.now.flatMap: now =>
      event.payload match
        case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
          activateFromProposal(proposal, now) *>
            (if isExpectedLeaderProposal(proposal) then
               markObservedLeaderProposal(proposal) *>
                 (if automaticConsensus then autoVoteOnProposal(proposal, now)
                  else Sync[F].unit)
             else Sync[F].unit)
        case HotStuffGossipArtifact.VoteArtifact(vote) =>
          activateFromQc(vote, now).flatMap: advanced =>
            if advanced then retryEligibleLeaderProposals else Sync[F].unit
        case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
          observeTimeoutVote(timeoutVote)
        case HotStuffGossipArtifact.NewViewArtifact(newView) =>
          observeNewView(newView, now)

  def drivePending: F[Unit] =
    clock.now.flatMap: now =>
      stateRef.get
        .map(
          _.keys.toVector.sortBy(key =>
            (key.chainId.value, key.localValidator.value),
          ),
        )
        .flatMap(_.traverse_(driveEntry(_, now)))

  def wakeUp: F[Unit] =
    drivePending *> retryEligibleLeaderProposals

  private def retryEligibleLeaderProposals: F[Unit] =
    clock.now.flatMap: now =>
      stateRef.get.flatMap: entries =>
        entries.toVector
          .sortBy { case (key, _) =>
            (key.chainId.value, key.localValidator.value)
          }
          .traverse_ { case (key, entry) =>
            val alreadyClaimedCurrentWindow =
              entry.state.exists(state =>
                proposalEmissionClaimed(entry, state.activeWindow),
              )
            if alreadyClaimedCurrentWindow then Sync[F].unit
            else
              entry.state match
                case Some(state)
                    if state.activeWindow.chainId === key.chainId &&
                      state.currentLeader === key.localValidator &&
                      state.bootstrapHoldReason.isEmpty &&
                      !state.localTimeoutVoteRequested &&
                      state.localTimeoutVote.isEmpty =>
                  emitLeaderProposal(
                    state.activeWindow,
                    key.localValidator,
                    now,
                  )
                case _ =>
                  Sync[F].unit
          }

  private def driveEntry(
      key: HotStuffPacemakerKey,
      now: Instant,
  ): F[Unit] =
    for
      holdReason <- bootstrapHoldReason(key.chainId)
      _ <- updateEntry(key): snapshot =>
        snapshot.state.map: state =>
          runtimeFor(key.localValidator).updateBootstrapHold(state, holdReason)
      _ <- updateEntry(key): snapshot =>
        snapshot.state.map: state =>
          runtimeFor(key.localValidator).tick(state, now)
    yield ()

  private def activateFromProposal(
      proposal: Proposal,
      now: Instant,
  ): F[Unit] =
    bootstrapHoldReason(proposal.window.chainId).flatMap: holdReason =>
      localValidators.traverse_ { validatorId =>
        updateEntry(HotStuffPacemakerKey(proposal.window.chainId, validatorId)):
          snapshot =>
            snapshot.state match
              case Some(existing)
                  if compareWindow(
                    proposal.window,
                    existing.activeWindow,
                  ).isEmpty =>
                None
              case Some(existing)
                  if compareWindow(proposal.window, existing.activeWindow)
                    .exists(
                      _ <= 0,
                    ) =>
                None
              case _ =>
                Some(
                  suppressLeaderActivationIfObserved(
                    validatorId,
                    proposal.proposer,
                    runtimeFor(validatorId).start(
                      activeWindow = proposal.window,
                      highestKnownQc = proposal.justify,
                      now = now,
                      bootstrapHoldReason = holdReason,
                    ),
                  ),
                )
      }

  private def activateFromQc(
      vote: Vote,
      now: Instant,
  ): F[Boolean] =
    sink.snapshot.flatMap: snapshot =>
      snapshot.proposals.get(vote.targetProposalId) match
        case None =>
          Sync[F].pure(false)
        case Some(proposal) =>
          val highestKnownQc =
            snapshot.qcs
              .get(vote.targetProposalId)
              .orElse:
                QuorumCertificateAssembler
                  .assemble(
                    QuorumCertificateSubject(
                      window = proposal.window,
                      proposalId = proposal.proposalId,
                      blockId = proposal.targetBlockId,
                    ),
                    snapshot.accumulator.votesFor(
                      proposal.window,
                      proposal.proposalId,
                    ),
                    bootstrapInput.validatorSet,
                  )
                  .toOption
          highestKnownQc.fold(Sync[F].pure(false)): qc =>
            val nextWindow =
              proposal.window.copy(
                height = proposal.window.height.next,
                view = proposal.window.view.next,
              )
            bootstrapHoldReason(nextWindow.chainId).flatMap: holdReason =>
              localValidators.traverse { validatorId =>
                updateEntry(
                  HotStuffPacemakerKey(nextWindow.chainId, validatorId),
                ): snapshot =>
                  snapshot.state match
                    case Some(existing)
                        if compareWindow(
                          nextWindow,
                          existing.activeWindow,
                        ).contains(-1) =>
                      None
                    case Some(existing)
                        if existing.activeWindow === nextWindow &&
                          existing.highestKnownQc.subject === qc.subject =>
                      None
                    case Some(existing)
                        if compareWindow(
                          nextWindow,
                          existing.activeWindow,
                        ).isEmpty =>
                      None
                    case _ =>
                      Some(
                        runtimeFor(validatorId).start(
                          activeWindow = nextWindow,
                          highestKnownQc = qc,
                          now = now,
                          bootstrapHoldReason = holdReason,
                        ),
                      )
              }.map(_.exists(identity))

  private def observeTimeoutVote(
      timeoutVote: TimeoutVote,
  ): F[Unit] =
    localValidators.traverse_ { validatorId =>
      val key =
        HotStuffPacemakerKey(timeoutVote.subject.window.chainId, validatorId)
      updateEntry(key): snapshot =>
        snapshot.state.flatMap(state =>
          runtimeFor(validatorId)
            .observeTimeoutVote(state, timeoutVote)
            .toOption,
        )
    }

  private def observeNewView(
      newView: NewView,
      now: Instant,
  ): F[Unit] =
    bootstrapHoldReason(newView.window.chainId).flatMap: holdReason =>
      localValidators.traverse_ { validatorId =>
        val key = HotStuffPacemakerKey(newView.window.chainId, validatorId)
        updateEntry(key): snapshot =>
          snapshot.state match
            case None =>
              Some(
                runtimeFor(validatorId).start(
                  activeWindow = newView.window,
                  highestKnownQc = newView.highestKnownQc,
                  now = now,
                  bootstrapHoldReason = holdReason,
                ),
              )
            case Some(state) =>
              runtimeFor(validatorId)
                .observeNewView(state, newView, now)
                .toOption
                .orElse:
                  compareWindow(newView.window, state.activeWindow) match
                    case Some(1) =>
                      Some(
                        runtimeFor(validatorId).start(
                          activeWindow = newView.window,
                          highestKnownQc = newView.highestKnownQc,
                          now = now,
                          bootstrapHoldReason = holdReason,
                        ),
                      )
                    case _ =>
                      None
      }

  private def updateEntry(
      key: HotStuffPacemakerKey,
  )(
      stepFor: HotStuffPacemakerEntrySnapshot => Option[HotStuffPacemakerStep],
  ): F[Boolean] =
    stateRef
      .modify: entries =>
        val existing  = entries.get(key)
        val current   = existing.getOrElse(HotStuffPacemakerEntrySnapshot.empty)
        val maybeStep = stepFor(current)
        val nextEntries =
          maybeStep match
            case Some(step) =>
              val (emittedProposalWindow, proposalEmissionReservationWindow) =
                current.state match
                  case Some(state)
                      if state.activeWindow =!= step.state.activeWindow =>
                    None -> None
                  case _ =>
                    current.emittedProposalWindow ->
                      current.proposalEmissionReservationWindow
              entries.updated(
                key,
                HotStuffPacemakerEntrySnapshot(
                  state = Some(step.state),
                  diagnostics = current.diagnostics ++ step.diagnostics,
                  proposalEligibility = Some(step.proposalEligibility),
                  emittedProposalWindow = emittedProposalWindow,
                  proposalEmissionReservationWindow =
                    proposalEmissionReservationWindow,
                ),
              )
            case None =>
              entries
        nextEntries -> (
          maybeStep.isDefined,
          maybeStep.toList.flatMap(_.commands),
        )
      .flatMap: (advanced, commands) =>
        executeCommands(commands).as(advanced)

  private def executeCommands(
      commands: Iterable[HotStuffPacemakerCommand],
  ): F[Unit] =
    clock.now.flatMap: now =>
      commands.toList.traverse_(executeCommand(_, now))

  private def executeCommand(
      command: HotStuffPacemakerCommand,
      now: Instant,
  ): F[Unit] =
    command match
      case HotStuffPacemakerCommand.ActivateLeader(window, leader) =>
        if automaticConsensus then emitLeaderProposal(window, leader, now)
        else Sync[F].unit
      case HotStuffPacemakerCommand.EmitTimeoutVote(
            voter,
            window,
            highestKnownQc,
          ) =>
        emitTimeoutVote(voter, window, highestKnownQc, now)
      case HotStuffPacemakerCommand.EmitNewView(
            sender,
            highestKnownQc,
            timeoutCertificate,
          ) =>
        emitNewView(sender, highestKnownQc, timeoutCertificate, now)

  private def emitTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      now: Instant,
  ): F[Unit] =
    stateFor(HotStuffPacemakerKey(window.chainId, voter)).flatMap:
      case Some(state)
          if state.activeWindow === window &&
            state.bootstrapHoldReason.isEmpty =>
        withLocalSigner(voter): keyPair =>
          TimeoutVote
            .sign(
              UnsignedTimeoutVote(
                subject = TimeoutVoteSubject(window, highestKnownQc.subject),
                voter = voter,
              ),
              keyPair,
            )
            .toOption
            .traverse: timeoutVote =>
              applyLocalArtifact(
                HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote),
                now,
              )
            .void
      case _ =>
        Sync[F].unit

  private def emitLeaderProposal(
      window: HotStuffWindow,
      leader: ValidatorId,
      now: Instant,
  ): F[Unit] =
    val key = HotStuffPacemakerKey(window.chainId, leader)
    reserveProposalEmission(key, window, leader).flatMap:
      case None =>
        Sync[F].unit
      case Some(state) =>
        Ref.of[F, Boolean](false).flatMap: emittedRef =>
          attemptReservedProposalEmission(key, window, leader, state, now)
            .flatTap(emittedRef.set)
            .void
            .guarantee:
              emittedRef.get.flatMap: emitted =>
                releaseProposalEmissionReservation(key, window)
                  .unlessA(emitted)

  private def attemptReservedProposalEmission(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
      leader: ValidatorId,
      state: HotStuffPacemakerState,
      now: Instant,
  ): F[Boolean] =
    proposalInputRequest(window, leader, state, now) match
      case Left(error) =>
        recordProposalInputDiagnostic(
          key,
          window,
          leader,
          HotStuffProposalInputDiagnosticOutcome.Failed,
          "proposalInputTimestampInvalid",
          Some(error),
          fallbackUsed = false,
        ).as(false)
      case Right(request) =>
        proposalInputRequestWithTxExclusion(key, request).flatMap:
          case Some(requestWithExclusion) =>
            requestWithFinalityDrive(
              key,
              requestWithExclusion,
              now,
            ).flatMap(requestWithDrive =>
              resolveProposalInput(key, requestWithDrive, now),
            )
          case None =>
            Sync[F].pure(false)

  private def proposalInputRequest(
      window: HotStuffWindow,
      leader: ValidatorId,
      state: HotStuffPacemakerState,
      now: Instant,
  ): Either[String, HotStuffProposalInputRequest] =
    BlockTimestamp
      .fromInstant(now)
      .map: timestamp =>
        HotStuffProposalInputRequest(
          window = window,
          proposer = leader,
          parent =
            if window.height === HotStuffHeight.Genesis then None
            else Some(state.highestKnownQc.subject.blockId),
          height = BlockHeight(window.height.toBigNat),
          justify = state.highestKnownQc,
          now = now,
          timestamp = timestamp,
          bounds = HotStuffProposalInputBounds.unbounded,
        )

  private def proposalInputRequestWithTxExclusion(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
  ): F[Option[HotStuffProposalInputRequest]] =
    txUniquenessConfig.policy match
      case HotStuffProposalTxUniquenessPolicy.UnsafeAllowAncestorTxConflicts =>
        sink.snapshot.flatMap: snapshot =>
          recordUnsafeTxUniquenessDiagnostic(key, request.window).as:
            Some(requestWithBranchContext(request, snapshot))
      case HotStuffProposalTxUniquenessPolicy.EnforceUnfinalizedAncestors =>
        sink.snapshot.flatMap: snapshot =>
          val requestWithBranch =
            requestWithBranchContext(request, snapshot)
          txUniquenessCacheRef
            .modify: cache =>
              val (updatedCache, result) =
                HotStuffProposalTxUniqueness.exclusionForParent(
                  chainId = request.window.chainId,
                  parentBlockId = request.parent,
                  proposals = snapshot.proposals.values,
                  finalization = snapshot.finalization,
                  bounds = txUniquenessConfig.bounds,
                  cache = cache,
                )
              updatedCache -> result
            .flatMap:
              case HotStuffProposalTxUniquenessResult.Accepted(exclusion) =>
                recordProposalInputTxExclusionDiagnostic(
                  key,
                  requestWithBranch,
                  exclusion,
                ).as(Some(requestWithBranch.copy(txExclusion = exclusion)))
              case HotStuffProposalTxUniquenessResult.Conflict(
                    conflicts,
                    _,
                  ) =>
                // Exclusion building should not produce candidate conflicts. If
                // it ever does, suppress proposal input as unavailable rather
                // than reporting a provider-output violation.
                recordProposalInputDiagnostic(
                  key,
                  request.window,
                  request.proposer,
                  HotStuffProposalInputDiagnosticOutcome.Unavailable,
                  HotStuffProposalInputValidator.TxAncestorUnavailableReason,
                  HotStuffProposalTxUniqueness.diagnosticDetail(
                    "proposalTxAncestorConflict",
                    Some(
                      ss"conflictCount=${conflicts.txIds.length.toString}",
                    ),
                  ),
                  fallbackUsed = false,
                ).as(None)
              case HotStuffProposalTxUniquenessResult.Unavailable(
                    reason,
                    detail,
                    _,
                  ) =>
                recordProposalInputDiagnostic(
                  key,
                  request.window,
                  request.proposer,
                  HotStuffProposalInputDiagnosticOutcome.Unavailable,
                  HotStuffProposalInputValidator.TxAncestorUnavailableReason,
                  HotStuffProposalTxUniqueness.diagnosticDetail(reason, detail),
                  fallbackUsed = false,
                ).as(None)

  private def requestWithBranchContext(
      request: HotStuffProposalInputRequest,
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): HotStuffProposalInputRequest =
    val branchContext =
      HotStuffProposalInputBranchContext.fromParent(
        chainId = request.window.chainId,
        parentBlockId = request.parent,
        justify = request.justify,
        proposals = snapshot.proposals.values,
        finalization = snapshot.finalization,
        bounds = txUniquenessConfig.bounds,
      )
    request.copy(
      branchContext = branchContext,
      finalizationProgress =
        HotStuffProposalInputFinalizationProgress.fromBranch(
          branchContext,
          request.height,
        ),
    )

  private def requestWithFinalityDrive(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      now: Instant,
  ): F[HotStuffProposalInputRequest] =
    if !finalityDrivePolicy.enabled then request.pure[F]
    else
      sink.snapshot.flatMap: snapshot =>
        val rawCandidate =
          HotStuffFinalityDriveCandidate
            .fromRequest(
              request,
              finalityDrivePolicy.maxDescendantDepth,
            )
        val candidate =
          rawCandidate.filter(candidate =>
            FinalityDriveFinalization
              .finalizedBlockId(
                candidate.anchor,
                snapshot.finalization,
              )
              .isEmpty,
          )
        // Target-finalized diagnostics come from pruning active drive state.
        // A candidate already finalized before a drive attempt is simply
        // ineligible, which keeps repeated wake retries idempotent.
        finalityDriveStateRef
          .modify: state =>
            val (pruned, finalizedAnchors) =
              state.pruned(snapshot.finalization)
            val finalizedEvents =
              finalizedAnchors.distinct.map(
                (reportingKey, anchor, finalizedBlockId) =>
                  FinalityDriveDiagnosticEvent.TargetFinalized(
                    reportingKey,
                    anchor,
                    finalizedBlockId,
                  ),
              )
            candidate match
              case Some(candidate) =>
                val (updated, decision) =
                  pruned.requestDrive(
                    finalityDrivePolicy,
                    candidate,
                    key,
                    now,
                  )
                decision match
                  case FinalityDriveRequestDecision.Requested(hint) =>
                    updated -> (
                      request.copy(finalityDrive = Some(hint)),
                      finalizedEvents :+
                        FinalityDriveDiagnosticEvent.Requested(hint)
                    )
                  case FinalityDriveRequestDecision.Suppressed(
                        suppression,
                      ) =>
                    updated -> (
                      request,
                      finalizedEvents :+
                        FinalityDriveDiagnosticEvent.Suppressed(suppression)
                    )
              case None =>
                pruned -> (request, finalizedEvents)
          .flatTap: (_, events) =>
            events.traverse_(event =>
              recordFinalityDriveDiagnosticEvent(key, request, event),
            )
          .map((requestWithDrive, _) => requestWithDrive)

  private def resolveProposalInput(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      now: Instant,
  ): F[Boolean] =
    proposalInputProvider
      .nextProposalInput(request)
      .attempt
      .flatMap: attempted =>
        clock.now.map: completedAt =>
          val result =
            attempted match
              case Right(result) =>
                result
              case Left(error) =>
                HotStuffProposalInputProviderResult.Failed(
                  reason = "proposalInputProviderFailed",
                  detail = throwableDetail(error),
                )
          result -> completedAt
      .flatMap: (result, completedAt) =>
        val decision = HotStuffProposalInputDecision.fromProviderResult(
          result,
          proposalInputFallbackPolicy,
        )
        recordProposalInputAttemptTimingDiagnostic(
          key,
          request,
          result,
          completedAt,
        ) *> recordFinalityDriveProviderResultDiagnostic(
          key,
          request,
          result,
        ) *> (decision match
          case HotStuffProposalInputDecision.UseProviderInput(input) =>
            useProviderInput(key, request, input, now)
          case HotStuffProposalInputDecision.UseLegacyEmpty(reason, detail) =>
            recordProviderResultDiagnostic(
              key,
              request,
              result,
              fallbackUsed = true,
            ) *> useLegacyProposalInput(
              key,
              request,
              now,
              reason,
              detail,
            )
          case HotStuffProposalInputDecision.Suppress(_, _) =>
            recordProviderResultDiagnostic(
              key,
              request,
              result,
              fallbackUsed = false,
            ).as(false)
        )

  private def useProviderInput(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      input: HotStuffProposalInput,
      now: Instant,
  ): F[Boolean] =
    HotStuffProposalInputValidator.validate(request, input) match
      case Left(validation) =>
        handleInvalidProposalInput(key, request, validation, now)
      case Right(validated) =>
        recordProposalInputDiagnostic(
          key,
          request.window,
          request.proposer,
          HotStuffProposalInputDiagnosticOutcome.Supplied,
          "supplied",
          None,
          fallbackUsed = false,
        ) *> signAndApplyProposalInput(
          key,
          request,
          validated,
          now,
          fallbackUsed = false,
        )

  private def handleInvalidProposalInput(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      validation: HotStuffValidationFailure,
      now: Instant,
  ): F[Boolean] =
    if validation.reason === HotStuffProposalInputValidator.TxAncestorConflictReason
    then
      recordProposalInputDiagnostic(
        key,
        request.window,
        request.proposer,
        HotStuffProposalInputDiagnosticOutcome.Invalid,
        validation.reason,
        validation.detail,
        fallbackUsed = false,
      ).as(false)
    else
      proposalInputFallbackPolicy match
        case HotStuffProposalInputFallbackPolicy.AllowLegacyEmpty =>
          recordProposalInputDiagnostic(
            key,
            request.window,
            request.proposer,
            HotStuffProposalInputDiagnosticOutcome.Invalid,
            validation.reason,
            validation.detail,
            fallbackUsed = true,
          ) *> useLegacyProposalInput(
              key,
              request,
              now,
              validation.reason,
              validation.detail,
            )
        case HotStuffProposalInputFallbackPolicy.RequireProviderInput =>
          recordProposalInputDiagnostic(
            key,
            request.window,
            request.proposer,
            HotStuffProposalInputDiagnosticOutcome.Invalid,
            validation.reason,
            validation.detail,
            fallbackUsed = false,
          ).as(false)

  private def useLegacyProposalInput(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      now: Instant,
      reason: String,
      detail: Option[String],
  ): F[Boolean] =
    legacyProposalInputProvider
      .nextProposalInput(request)
      .attempt
      .flatMap:
        case Left(error) =>
          recordProposalInputDiagnostic(
            key,
            request.window,
            request.proposer,
            HotStuffProposalInputDiagnosticOutcome.Failed,
            "legacyProposalInputProviderFailed",
            throwableDetail(error),
            fallbackUsed = true,
          ).as(false)
        case Right(HotStuffProposalInputProviderResult.Supplied(input)) =>
          HotStuffProposalInputValidator.validate(request, input) match
            case Left(validation) =>
              recordProposalInputDiagnostic(
                key,
                request.window,
                request.proposer,
                HotStuffProposalInputDiagnosticOutcome.Invalid,
                validation.reason,
                validation.detail,
                fallbackUsed = true,
              ).as(false)
            case Right(validated) =>
              recordProposalInputDiagnostic(
                key,
                request.window,
                request.proposer,
                HotStuffProposalInputDiagnosticOutcome.Supplied,
                reason,
                detail,
                fallbackUsed = true,
              ) *> signAndApplyProposalInput(
                key,
                request,
                validated,
                now,
                fallbackUsed = true,
              )
        case Right(other) =>
          recordProviderResultDiagnostic(
            key,
            request,
            other,
            fallbackUsed = true,
          ).as(false)

  private def signAndApplyProposalInput(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      input: HotStuffProposalInput,
      now: Instant,
      fallbackUsed: Boolean,
  ): F[Boolean] =
    resolveSigner(request.proposer) match
      case Left(_) =>
        false.pure[F]
      case Right(keyPair) =>
        val block = input.blockHeader
        Proposal
          .sign(
            UnsignedProposal(
              window = request.window,
              proposer = request.proposer,
              targetBlockId = BlockHeader.computeId(block),
              block = block,
              txSet = input.txSet,
              justify = request.justify,
            ),
            keyPair,
          )
          .toOption match
          case None =>
            false.pure[F]
          case Some(proposal) =>
            HotStuffValidator.validateProposal(
              proposal,
              bootstrapInput.validatorSet,
            ) match
              case Left(validation) =>
                recordProposalInputDiagnostic(
                  key,
                  request.window,
                  request.proposer,
                  HotStuffProposalInputDiagnosticOutcome.Invalid,
                  validation.reason,
                  validation.detail,
                  fallbackUsed = fallbackUsed,
                ).as(false)
              case Right(_) =>
                applyLocalArtifact(
                  HotStuffGossipArtifact.ProposalArtifact(proposal),
                  now,
                ) *> markProposalEmitted(key, request.window) *>
                  recordLocalProposalEmittedDiagnostic(
                    key,
                    request.window,
                    request.proposer,
                    proposal,
                    now,
                  ).as(true)

  private def emitNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
      now: Instant,
  ): F[Unit] =
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val nextLeader =
      HotStuffPacemaker.deterministicLeader(
        nextWindow,
        bootstrapInput.validatorSet,
      )
    stateFor(HotStuffPacemakerKey(nextWindow.chainId, sender)).flatMap:
      case Some(state) if state.bootstrapHoldReason.isEmpty =>
        withLocalSigner(sender): keyPair =>
          NewView
            .sign(
              UnsignedNewView(
                window = nextWindow,
                sender = sender,
                nextLeader = nextLeader,
                highestKnownQc = highestKnownQc,
                timeoutCertificate = timeoutCertificate,
              ),
              keyPair,
            )
            .toOption
            .traverse: newView =>
              applyLocalArtifact(
                HotStuffGossipArtifact.NewViewArtifact(newView),
                now,
              )
            .void
      case _ =>
        Sync[F].unit

  private def autoVoteOnProposal(
      proposal: Proposal,
      now: Instant,
  ): F[Unit] =
    localValidators.traverse_(validatorId =>
      emitVote(validatorId, proposal, now),
    )

  private def emitVote(
      voter: ValidatorId,
      proposal: Proposal,
      now: Instant,
  ): F[Unit] =
    val key = HotStuffPacemakerKey(proposal.window.chainId, voter)
    stateFor(key).flatMap:
      case Some(state)
          if state.activeWindow === proposal.window &&
            !state.localTimeoutVoteRequested &&
            state.localTimeoutVote.isEmpty &&
            state.bootstrapHoldReason.isEmpty =>
        hasLocalVote(voter, proposal).flatMap:
          case true =>
            Sync[F].unit
          case false =>
            withLocalSigner(voter): keyPair =>
              validateProposalForLocalVote(key, voter, proposal, now).flatMap:
                case decision if decision.voteSuppressed =>
                  recordProposalValidationDiagnostic(
                    key,
                    proposal,
                    voter,
                    decision,
                  )
                case decision =>
                  Vote
                    .sign(
                      UnsignedVote(
                        window = proposal.window,
                        voter = voter,
                        targetProposalId = proposal.proposalId,
                      ),
                      keyPair,
                    ) match
                    case Left(_) =>
                      Sync[F].unit
                    case Right(vote) =>
                      applyLocalArtifact(
                        HotStuffGossipArtifact.VoteArtifact(vote),
                        now,
                      ) *>
                        recordLocalVoteEmittedDiagnostic(
                          key,
                          proposal,
                          voter,
                          now,
                        ) *>
                        recordProposalValidationDiagnostic(
                          key,
                          proposal,
                          voter,
                          decision,
                        )
      case _ =>
        Sync[F].unit

  private def validateProposalForLocalVote(
      key: HotStuffPacemakerKey,
      voter: ValidatorId,
      proposal: Proposal,
      now: Instant,
  ): F[HotStuffProposalValidationDecision] =
    proposalTxUniquenessDecision(key, proposal).flatMap:
      case Some(decision) =>
        decision.pure[F]
      case None =>
        sink.snapshot.flatMap: snapshot =>
          HotStuffProposalValidationDecision.evaluateForLocalVote(
            config = proposalValidationConfig,
            proposal = proposal,
            localVoter = voter,
            now = now,
            validatorSet = bootstrapInput.validatorSet,
            branchContext = proposalValidationBranchContext(
              proposal,
              snapshot,
            ),
          )

  private def proposalValidationBranchContext(
      proposal: Proposal,
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): HotStuffProposalInputBranchContext =
    HotStuffProposalValidationBranchContext.fromSnapshot(
      proposal = proposal,
      snapshot = snapshot,
      bounds = txUniquenessConfig.bounds,
    )

  private def proposalTxUniquenessDecision(
      key: HotStuffPacemakerKey,
      proposal: Proposal,
  ): F[Option[HotStuffProposalValidationDecision]] =
    txUniquenessConfig.policy match
      case HotStuffProposalTxUniquenessPolicy.UnsafeAllowAncestorTxConflicts =>
        recordUnsafeTxUniquenessDiagnostic(key, proposal.window).as(None)
      case HotStuffProposalTxUniquenessPolicy.EnforceUnfinalizedAncestors =>
        sink.snapshot.flatMap: snapshot =>
          txUniquenessCacheRef
            .modify: cache =>
              val (updatedCache, result) =
                HotStuffProposalTxUniqueness.checkProposal(
                  proposal = proposal,
                  proposals = snapshot.proposals.values,
                  finalization = snapshot.finalization,
                  bounds = txUniquenessConfig.bounds,
                  cache = cache,
                )
              updatedCache -> result
            .map(HotStuffProposalValidationDecision.fromTxUniquenessResult)

  private def applyLocalArtifact(
      artifact: HotStuffGossipArtifact,
      now: Instant,
  ): F[Unit] =
    publisher
      .append(artifact, now)
      .flatMap: event =>
        sink
          .applyEvent(event)
          .flatMap:
            case Right(result) if result.applied =>
              observeApplied(event)
            case _ =>
              Sync[F].unit

  private def withLocalSigner(
      validatorId: ValidatorId,
  )(
      f: KeyPair => F[Unit],
  ): F[Unit] =
    resolveSigner(validatorId) match
      case Left(_) =>
        Sync[F].unit
      case Right(keyPair) =>
        f(keyPair)

  private def stateFor(
      key: HotStuffPacemakerKey,
  ): F[Option[HotStuffPacemakerState]] =
    stateRef.get.map(_.get(key).flatMap(_.state))

  private def proposalEmissionClaimed(
      entry: HotStuffPacemakerEntrySnapshot,
      window: HotStuffWindow,
  ): Boolean =
    entry.emittedProposalWindow.contains(window) ||
      entry.proposalEmissionReservationWindow.contains(window)

  private def reserveProposalEmission(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
      leader: ValidatorId,
  ): F[Option[HotStuffPacemakerState]] =
    stateRef.modify: entries =>
      entries.get(key) match
        case Some(entry) if proposalEmissionClaimed(entry, window) =>
          entries -> None
        case Some(entry) =>
          entry.state match
            case Some(state)
                if state.activeWindow === window &&
                  state.currentLeader === leader &&
                  state.bootstrapHoldReason.isEmpty =>
              entries.updated(
                key,
                entry.copy(proposalEmissionReservationWindow = Some(window)),
              ) -> Some(state)
            case _ =>
              entries -> None
        case None =>
          entries -> None

  private def releaseProposalEmissionReservation(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry)
          if entry.proposalEmissionReservationWindow.contains(window) =>
        Some(entry.copy(proposalEmissionReservationWindow = None))
      case other =>
        other,
    )

  private def markProposalEmitted(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            emittedProposalWindow = Some(window),
            proposalEmissionReservationWindow = None,
          ),
        )
      case None =>
        None,
    )

  private def proposalInputProvider: HotStuffProposalInputProvider[F] =
    proposalInputProviderOverride.getOrElse(legacyProposalInputProvider)

  private def legacyProposalInputProvider: HotStuffProposalInputProvider[F] =
    new LegacyEmptyHotStuffProposalInputProvider[F](
      stateRootFor = request =>
        automaticProposalStateRoot(
          request.window,
          request.proposer,
          request.justify,
        ),
      bodyRootFor = request =>
        automaticBodyRoot(
          request.window,
          request.proposer,
          request.justify,
        ),
    )

  private def recordProviderResultDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      result: HotStuffProposalInputProviderResult,
      fallbackUsed: Boolean,
  ): F[Unit] =
    recordProposalInputDiagnostic(
      key,
      request.window,
      request.proposer,
      diagnosticOutcomeFor(result),
      result.reason,
      result.detail,
      fallbackUsed,
    )

  private def recordFinalityDriveDiagnosticEvent(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      event: FinalityDriveDiagnosticEvent,
  ): F[Unit] =
    event match
      case FinalityDriveDiagnosticEvent.Requested(hint) =>
        recordFinalityDriveRequestedDiagnostic(key, request, hint)
      case FinalityDriveDiagnosticEvent.Suppressed(suppression) =>
        recordFinalityDriveSuppressedDiagnostic(key, request, suppression)
      case FinalityDriveDiagnosticEvent.TargetFinalized(
            reportingKey,
            anchor,
            finalizedBlockId,
          ) =>
        recordFinalityDriveTargetFinalizedDiagnostic(
          reportingKey,
          anchor,
          finalizedBlockId,
        )

  private def recordFinalityDriveRequestedDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      hint: HotStuffProposalInputFinalityDrive,
  ): F[Unit] =
    val anchor = hint.anchor
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .FinalityDriveRequested(
                window = request.window,
                proposer = request.proposer,
                anchorProposalId = anchor.proposalId,
                anchorBlockId = anchor.blockId,
                descendantDepthAfterProposal =
                  hint.descendantDepthAfterProposal,
                attempt = hint.attempt,
                maxAttemptsPerAnchor = hint.maxAttemptsPerAnchor,
                maxDescendantDepth = hint.maxDescendantDepth,
                elapsed = hint.elapsed,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordFinalityDriveProviderResultDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      result: HotStuffProposalInputProviderResult,
  ): F[Unit] =
    request.finalityDrive.fold(Sync[F].unit): drive =>
      val anchor = drive.anchor
      stateRef.update(_.updatedWith(key):
        case Some(entry) =>
          Some(
            entry.copy(
              diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
                .FinalityDriveProviderResult(
                  window = request.window,
                  proposer = request.proposer,
                  anchorProposalId = anchor.proposalId,
                  anchorBlockId = anchor.blockId,
                  outcome = diagnosticOutcomeFor(result),
                  reason = result.reason,
                  detail = result.detail,
                ),
            ),
          )
        case None =>
          None,
      )

  private def recordFinalityDriveSuppressedDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      suppression: FinalityDriveSuppression,
  ): F[Unit] =
    val anchor = suppression.anchor
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .FinalityDriveSuppressed(
                window = request.window,
                proposer = request.proposer,
                anchorProposalId = anchor.proposalId,
                anchorBlockId = anchor.blockId,
                reason = suppression.reason,
                detail = suppression.detail,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordFinalityDriveTargetFinalizedDiagnostic(
      key: HotStuffPacemakerKey,
      anchor: HotStuffFinalityDriveAnchor,
      finalizedBlockId: BlockId,
  ): F[Unit] =
    val proposer =
      HotStuffPacemaker.deterministicLeader(
        anchor.window,
        bootstrapInput.validatorSet,
      )
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .FinalityDriveTargetFinalized(
                window = anchor.window,
                proposer = proposer,
                anchorProposalId = anchor.proposalId,
                anchorBlockId = anchor.blockId,
                finalizedBlockId = finalizedBlockId,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordProposalInputAttemptTimingDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      result: HotStuffProposalInputProviderResult,
      completedAt: Instant,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .ProposalInputAttemptTiming(
                window = request.window,
                proposer = request.proposer,
                outcome = diagnosticOutcomeFor(result),
                reason = result.reason,
                requestedAt = request.now,
                completedAt = completedAt,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordProposalInputDiagnostic(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
      proposer: ValidatorId,
      outcome: HotStuffProposalInputDiagnosticOutcome,
      reason: String,
      detail: Option[String],
      fallbackUsed: Boolean,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .ProposalInputResult(
                window = window,
                proposer = proposer,
                outcome = outcome,
                reason = reason,
                detail = detail,
                fallbackUsed = fallbackUsed,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordProposalInputTxExclusionDiagnostic(
      key: HotStuffPacemakerKey,
      request: HotStuffProposalInputRequest,
      exclusion: HotStuffProposalTxExclusion,
  ): F[Unit] =
    val metadata = exclusion.metadata
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .ProposalInputTxExclusion(
                window = request.window,
                proposer = request.proposer,
                parentBlockId = metadata.parentBlockId,
                bestFinalizedBlockId = metadata.bestFinalizedBlockId,
                traversedAncestorCount = metadata.traversedAncestorCount,
                excludedTxIdCount = metadata.excludedTxIdCount,
                fromCache = metadata.fromCache,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordLocalProposalEmittedDiagnostic(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
      proposer: ValidatorId,
      proposal: Proposal,
      emittedAt: Instant,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .LocalProposalEmitted(
                window = window,
                proposer = proposer,
                proposalId = proposal.proposalId,
                blockId = proposal.targetBlockId,
                emittedAt = emittedAt,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordLocalVoteEmittedDiagnostic(
      key: HotStuffPacemakerKey,
      proposal: Proposal,
      voter: ValidatorId,
      emittedAt: Instant,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics = entry.diagnostics :+ HotStuffPacemakerDiagnostic
              .LocalVoteEmitted(
                window = proposal.window,
                voter = voter,
                targetProposalId = proposal.proposalId,
                emittedAt = emittedAt,
              ),
          ),
        )
      case None =>
        None,
    )

  private def recordUnsafeTxUniquenessDiagnostic(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
  ): F[Unit] =
    val diagnostic =
      HotStuffPacemakerDiagnostic.ProposalTxUniquenessPolicy(
        window = window,
        validator = key.localValidator,
        policy = txUniquenessConfig.policy,
        reason =
          HotStuffProposalTxUniquenessRuntimeConfig.UnsafeAllowAncestorTxConflictsReason,
        detail = Some("ancestor tx conflicts are allowed by explicit config"),
      )
    stateRef.update(_.updatedWith(key):
      case Some(entry) if entry.diagnostics.contains(diagnostic) =>
        Some(entry)
      case Some(entry) =>
        Some(entry.copy(diagnostics = entry.diagnostics :+ diagnostic))
      case None =>
        None,
    )

  private def recordProposalValidationDiagnostic(
      key: HotStuffPacemakerKey,
      proposal: Proposal,
      voter: ValidatorId,
      decision: HotStuffProposalValidationDecision,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(
          entry.copy(
            diagnostics =
              HotStuffPacemakerDiagnostic.appendProposalValidationResult(
                entry.diagnostics,
                HotStuffPacemakerDiagnostic.ProposalValidationResult(
                  window = proposal.window,
                  proposalId = proposal.proposalId,
                  blockId = proposal.targetBlockId,
                  voter = voter,
                  outcome = decision.outcome,
                  reason = decision.reason,
                  detail = decision.detail,
                  voteSuppressed = decision.voteSuppressed,
                ),
              ),
          ),
        )
      case None =>
        None,
    )

  private def diagnosticOutcomeFor(
      result: HotStuffProposalInputProviderResult,
  ): HotStuffProposalInputDiagnosticOutcome =
    result match
      case HotStuffProposalInputProviderResult.Supplied(_) =>
        HotStuffProposalInputDiagnosticOutcome.Supplied
      case HotStuffProposalInputProviderResult.NoWork(_, _) =>
        HotStuffProposalInputDiagnosticOutcome.NoWork
      case HotStuffProposalInputProviderResult.Rejected(_, _) =>
        HotStuffProposalInputDiagnosticOutcome.Rejected
      case HotStuffProposalInputProviderResult.Failed(_, _) =>
        HotStuffProposalInputDiagnosticOutcome.Failed

  private def throwableDetail(
      error: Throwable,
  ): Option[String] =
    Some(error.getClass.getName)

  private def markObservedLeaderProposal(
      proposal: Proposal,
  ): F[Unit] =
    if bootstrapInput.localKeys.contains(proposal.proposer) then
      markProposalEmitted(
        HotStuffPacemakerKey(proposal.window.chainId, proposal.proposer),
        proposal.window,
      )
    else Sync[F].unit

  private def suppressLeaderActivationIfObserved(
      localValidator: ValidatorId,
      observedProposer: ValidatorId,
      step: HotStuffPacemakerStep,
  ): HotStuffPacemakerStep =
    if localValidator === observedProposer then
      step.copy(
        commands = step.commands.filter:
          case HotStuffPacemakerCommand.ActivateLeader(_, _) => false
          case _                                             => true,
      )
    else step

  private def hasLocalVote(
      voter: ValidatorId,
      proposal: Proposal,
  ): F[Boolean] =
    sink.snapshot.map: snapshot =>
      snapshot.votes.valuesIterator.exists(vote =>
        vote.voter === voter &&
          vote.window === proposal.window,
      )

  private def isExpectedLeaderProposal(
      proposal: Proposal,
  ): Boolean =
    proposal.proposer === HotStuffPacemaker.deterministicLeader(
      proposal.window,
      bootstrapInput.validatorSet,
    )

  private def automaticStateRoot(
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificate,
  ): StateRoot =
    StateRoot(
      hashAutomaticProposalSeed(
        AutomaticProposalStateRootDomain,
        window,
        leader,
        highestKnownQc,
      ),
    )

  private def automaticProposalStateRoot(
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificate,
  ): F[StateRoot] =
    sink.snapshot.map: snapshot =>
      snapshot.proposals
        .get(highestKnownQc.subject.proposalId)
        .map(_.block.stateRoot)
        .getOrElse(automaticStateRoot(window, leader, highestKnownQc))

  private def automaticBodyRoot(
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificate,
  ): BodyRoot =
    BodyRoot(
      hashAutomaticProposalSeed(
        AutomaticProposalBodyRootDomain,
        window,
        leader,
        highestKnownQc,
      ),
    )

  private def hashAutomaticProposalSeed(
      domain: Utf8,
      window: HotStuffWindow,
      leader: ValidatorId,
      highestKnownQc: QuorumCertificate,
  ): UInt256 =
    UInt256.unsafeFromBytesBE:
      ByteVector.view(
        CryptoOps.keccak256(
          AutomaticProposalSeed(
            domain = domain,
            chainId = window.chainId,
            window = window,
            leader = leader,
            highestKnownQc = highestKnownQc.subject,
          ).toBytes.toArray,
        ),
      )

  private def resolveSigner(
      validatorId: ValidatorId,
  ): Either[HotStuffPolicyViolation, KeyPair] =
    HotStuffPolicy
      .canEmitLocally(
        bootstrapInput.role,
        bootstrapInput.localPeer,
        validatorId,
        bootstrapInput.holders,
      )
      .flatMap(_ =>
        bootstrapInput.localKeys
          .get(validatorId)
          .toRight:
            HotStuffPolicyViolation(
              reason = "localValidatorKeyUnavailable",
              detail = Some(validatorId.value),
            ),
      )

  private def bootstrapHoldReason(
      chainId: ChainId,
  ): F[Option[String]] =
    bootstrapLifecycle match
      case Some(lifecycle) if bootstrapInput.role === LocalNodeRole.Validator =>
        lifecycle
          .voteReadiness(chainId)
          .map:
            case BootstrapVoteReadiness.Ready        => None
            case BootstrapVoteReadiness.Held(reason) => Some(reason)
      case _ =>
        None.pure[F]

  private def runtimeFor(
      localValidator: ValidatorId,
  ): HotStuffPacemakerRuntime =
    HotStuffPacemakerRuntime.withPolicy(
      localValidator = localValidator,
      validatorSet = bootstrapInput.validatorSet,
      policy = pacemakerPolicy,
    )

  private def compareWindow(
      left: HotStuffWindow,
      right: HotStuffWindow,
  ): Option[Int] =
    if left.chainId =!= right.chainId || left.validatorSetHash =!= right.validatorSetHash
    then None
    else
      val heightCompare =
        summon[Ordering[HotStuffHeight]].compare(left.height, right.height)
      if heightCompare =!= 0 then Some(java.lang.Integer.signum(heightCompare))
      else
        val viewCompare =
          summon[Ordering[HotStuffView]].compare(left.view, right.view)
        Some(java.lang.Integer.signum(viewCompare))

private object InMemoryHotStuffPacemakerDriver:
  def attach[F[_]: Sync](
      runtime: HotStuffNodeRuntime[F],
      automaticConsensus: Boolean,
      proposalInputConfig: HotStuffProposalInputRuntimeConfig[F],
      txUniquenessConfig: HotStuffProposalTxUniquenessRuntimeConfig,
      finalityDrivePolicy: HotStuffFinalityDrivePolicy,
  )(using
      clock: GossipClock[F],
  ): F[HotStuffNodeRuntime[F]] =
    runtime.diagnostics match
      case Some(inMemoryDiagnostics)
          if runtime.role === LocalNodeRole.Validator &&
            runtime.localKeys.nonEmpty =>
        (
          Ref.of[F, Map[HotStuffPacemakerKey, HotStuffPacemakerEntrySnapshot]](
            Map.empty,
          ),
          Ref.of[F, ObservedEventQueue](
            ObservedEventQueue(false, Vector.empty),
          ),
          Ref.of[F, HotStuffProposalTxUniquenessCache](
            HotStuffProposalTxUniquenessCache.empty,
          ),
          Ref.of[F, FinalityDriveRuntimeState](
            FinalityDriveRuntimeState.empty,
          ),
        ).mapN:
          (
              stateRef,
              observationRef,
              txUniquenessCacheRef,
              finalityDriveStateRef,
          ) =>
            val driver =
              new InMemoryHotStuffPacemakerDriver[F](
                bootstrapInput = runtime.bootstrapInput,
                publisher = runtime.services.publisher,
                sink = inMemoryDiagnostics.sink,
                bootstrapLifecycle = runtime.bootstrapLifecycle,
                stateRef = stateRef,
                observationRef = observationRef,
                txUniquenessCacheRef = txUniquenessCacheRef,
                finalityDriveStateRef = finalityDriveStateRef,
                automaticConsensus = automaticConsensus,
                proposalInputProviderOverride = proposalInputConfig.provider,
                proposalInputFallbackPolicy =
                  proposalInputConfig.fallbackPolicy,
                proposalValidationConfig = runtime.proposalValidationConfig,
                txUniquenessConfig = txUniquenessConfig,
                pacemakerPolicy = runtime.pacemakerPolicy,
                finalityDrivePolicy = finalityDrivePolicy,
              )
            val wrappedSource =
              new HotStuffPacemakerAwareSource[F](
                runtime.services.source,
                driver.drivePending,
              )
            val wrappedSink =
              new HotStuffPacemakerAwareSink[F](
                runtime.services.sink,
                driver.observeApplied,
              )
            runtime.copy(
              services = runtime.services.copy(
                source = wrappedSource,
                sink = wrappedSink,
              ),
              pacemakerSnapshot = driver.snapshot.some,
              pacemakerWakeUp = driver.wakeUp.some,
            )
      case _ =>
        runtime.pure[F]
