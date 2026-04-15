package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Instant

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, KeyPair}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.{
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

private object HotStuffPacemakerEntrySnapshot:
  val empty: HotStuffPacemakerEntrySnapshot =
    HotStuffPacemakerEntrySnapshot(
      state = None,
      diagnostics = Vector.empty[HotStuffPacemakerDiagnostic],
      proposalEligibility = None,
      emittedProposalWindow = None,
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
    automaticConsensus: Boolean,
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
          activateFromQc(vote, now)
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
  ): F[Unit] =
    sink.snapshot.flatMap: snapshot =>
      snapshot.proposals.get(vote.targetProposalId) match
        case None =>
          Sync[F].unit
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
          highestKnownQc.fold(Sync[F].unit): qc =>
            val nextWindow =
              proposal.window.copy(
                height = proposal.window.height.next,
                view = proposal.window.view.next,
              )
            bootstrapHoldReason(nextWindow.chainId).flatMap: holdReason =>
              localValidators.traverse_ { validatorId =>
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
              }

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
  ): F[Unit] =
    stateRef
      .modify: entries =>
        val existing  = entries.get(key)
        val current   = existing.getOrElse(HotStuffPacemakerEntrySnapshot.empty)
        val maybeStep = stepFor(current)
        val nextEntries =
          maybeStep match
            case Some(step) =>
              val emittedProposalWindow =
                current.state match
                  case Some(state)
                      if state.activeWindow =!= step.state.activeWindow =>
                    None
                  case _ =>
                    current.emittedProposalWindow
              entries.updated(
                key,
                HotStuffPacemakerEntrySnapshot(
                  state = Some(step.state),
                  diagnostics = step.diagnostics,
                  proposalEligibility = Some(step.proposalEligibility),
                  emittedProposalWindow = emittedProposalWindow,
                ),
              )
            case None =>
              entries
        nextEntries -> maybeStep.toList.flatMap(_.commands)
      .flatMap(commands => executeCommands(commands))

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
    entryFor(HotStuffPacemakerKey(window.chainId, leader)).flatMap:
      case Some(entry) if entry.emittedProposalWindow.contains(window) =>
        Sync[F].unit
      case Some(entry)
          if entry.state.exists(state =>
            state.activeWindow === window &&
              state.currentLeader === leader &&
              state.bootstrapHoldReason.isEmpty,
          ) =>
        entry.state.fold(Sync[F].unit): state =>
          automaticProposalStateRoot(window, leader, state.highestKnownQc)
            .flatMap: stateRoot =>
              withLocalSigner(leader): keyPair =>
                BlockTimestamp.fromInstant(now) match
                  case Left(_) =>
                    Sync[F].unit
                  case Right(timestamp) =>
                    val block =
                      BlockHeader(
                        parent =
                          if window.height === HotStuffHeight.Genesis then None
                          else Some(state.highestKnownQc.subject.blockId),
                        height = BlockHeight(window.height.toBigNat),
                        stateRoot = stateRoot,
                        bodyRoot = automaticBodyRoot(
                          window,
                          leader,
                          state.highestKnownQc,
                        ),
                        timestamp = timestamp,
                      )
                    Proposal
                      .sign(
                        UnsignedProposal(
                          window = window,
                          proposer = leader,
                          targetBlockId = BlockHeader.computeId(block),
                          block = block,
                          txSet = ProposalTxSet.empty,
                          justify = state.highestKnownQc,
                        ),
                        keyPair,
                      )
                      .toOption
                      .traverse: proposal =>
                        applyLocalArtifact(
                          HotStuffGossipArtifact.ProposalArtifact(proposal),
                          now,
                        ) *> markProposalEmitted(
                          HotStuffPacemakerKey(window.chainId, leader),
                          window,
                        )
                      .void
      case _ =>
        Sync[F].unit

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
    stateFor(HotStuffPacemakerKey(proposal.window.chainId, voter)).flatMap:
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
              Vote
                .sign(
                  UnsignedVote(
                    window = proposal.window,
                    voter = voter,
                    targetProposalId = proposal.proposalId,
                  ),
                  keyPair,
                )
                .toOption
                .traverse: vote =>
                  applyLocalArtifact(
                    HotStuffGossipArtifact.VoteArtifact(vote),
                    now,
                  )
                .void
      case _ =>
        Sync[F].unit

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

  private def entryFor(
      key: HotStuffPacemakerKey,
  ): F[Option[HotStuffPacemakerEntrySnapshot]] =
    stateRef.get.map(_.get(key))

  private def markProposalEmitted(
      key: HotStuffPacemakerKey,
      window: HotStuffWindow,
  ): F[Unit] =
    stateRef.update(_.updatedWith(key):
      case Some(entry) =>
        Some(entry.copy(emittedProposalWindow = Some(window)))
      case None =>
        None,
    )

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
    HotStuffPacemakerRuntime.default(
      localValidator,
      bootstrapInput.validatorSet,
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
        ).mapN: (stateRef, observationRef) =>
          val driver =
            new InMemoryHotStuffPacemakerDriver[F](
              bootstrapInput = runtime.bootstrapInput,
              publisher = runtime.services.publisher,
              sink = inMemoryDiagnostics.sink,
              bootstrapLifecycle = runtime.bootstrapLifecycle,
              stateRef = stateRef,
              observationRef = observationRef,
              automaticConsensus = automaticConsensus,
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
          )
      case _ =>
        runtime.pure[F]
