package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.Instant

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.BlockQuery
import org.sigilaris.node.gossip.*

final case class InMemoryHotStuffSourceSnapshot(
    eventsByTopic: Map[ChainTopic, Vector[GossipEvent[HotStuffGossipArtifact]]],
)

/** A snapshot of the in-memory gossip artifact sink state, including all stored artifacts and QCs. */
final case class InMemoryHotStuffSinkSnapshot(
    proposals: Map[ProposalId, Proposal],
    votes: Map[VoteId, Vote],
    accumulator: VoteAccumulator,
    timeoutVotes: Map[TimeoutVoteId, TimeoutVote],
    timeoutAccumulator: TimeoutVoteAccumulator,
    timeoutCertificates: Map[TimeoutVoteSubject, TimeoutCertificate],
    newViews: Map[NewViewId, NewView],
    newViewsBySenderWindow: Map[(HotStuffWindow, ValidatorId), NewView],
    qcs: Map[ProposalId, QuorumCertificate],
    finalization: Map[ChainId, FinalizationTrackerSnapshot],
    duplicates: Vector[GossipEvent[HotStuffGossipArtifact]],
)

/** Companion for `InMemoryHotStuffSinkSnapshot`. */
object InMemoryHotStuffSinkSnapshot:
  /** An empty sink snapshot. */
  val empty: InMemoryHotStuffSinkSnapshot =
    InMemoryHotStuffSinkSnapshot(
      proposals = Map.empty[ProposalId, Proposal],
      votes = Map.empty[VoteId, Vote],
      accumulator = VoteAccumulator.empty,
      timeoutVotes = Map.empty[TimeoutVoteId, TimeoutVote],
      timeoutAccumulator = TimeoutVoteAccumulator.empty,
      timeoutCertificates = Map.empty[TimeoutVoteSubject, TimeoutCertificate],
      newViews = Map.empty[NewViewId, NewView],
      newViewsBySenderWindow =
        Map.empty[(HotStuffWindow, ValidatorId), NewView],
      qcs = Map.empty[ProposalId, QuorumCertificate],
      finalization = Map.empty[ChainId, FinalizationTrackerSnapshot],
      duplicates = Vector.empty[GossipEvent[HotStuffGossipArtifact]],
    )

/** Publishes HotStuff gossip artifacts to the local gossip source. */
trait HotStuffArtifactPublisher[F[_]]:
  /** Appends an artifact to the gossip source, returning the created gossip event. */
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]]

/** In-memory implementation of a gossip artifact source and publisher for HotStuff artifacts. */
final class InMemoryHotStuffArtifactSource[F[_]: Sync] private (
    clock: GossipClock[F],
    ref: Ref[F, Map[ChainTopic, Vector[
      AvailableGossipEvent[HotStuffGossipArtifact],
    ]]],
) extends GossipArtifactSource[F, HotStuffGossipArtifact]
    with HotStuffArtifactPublisher[F]:
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]] =
    clock.now.flatMap: availableAt =>
      ref.modify: state =>
        val chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote) => vote.window.chainId
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            timeoutVote.subject.window.chainId
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            newView.window.chainId
        val topic      = HotStuffGossipArtifact.topicOf(artifact)
        val chainTopic = ChainTopic(chainId, topic)
        val topicEvents = state.getOrElse(
          chainTopic,
          Vector.empty[AvailableGossipEvent[HotStuffGossipArtifact]],
        )
        val nextSequence = topicEvents.size.toLong + 1L
        val event = GossipEvent(
          chainId = chainId,
          topic = topic,
          id = HotStuffGossipArtifact.stableIdOf(artifact),
          cursor = cursorFor(nextSequence),
          ts = ts,
          payload = artifact,
        )
        val available =
          AvailableGossipEvent(event = event, availableAt = availableAt)
        state.updated(chainTopic, topicEvents :+ available) -> event

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[
    AvailableGossipEvent[HotStuffGossipArtifact],
  ]]] =
    ref.get.map: state =>
      val chainTopic = ChainTopic(chainId, topic)
      val topicEvents = state.getOrElse(
        chainTopic,
        Vector.empty[AvailableGossipEvent[HotStuffGossipArtifact]],
      )
      cursor match
        case None =>
          topicEvents.asRight[CanonicalRejection]
        case Some(token) =>
          decodeSequence(token).flatMap: sequence =>
            val maxSequence = topicEvents.size.toLong
            Either.cond(
              sequence >= 1L && sequence <= maxSequence,
              topicEvents.drop(sequence.toInt),
              CanonicalRejection.StaleCursor(
                reason = "unknownCursor",
                detail = Some:
                  ss"sequence=${sequence.toString} max=${maxSequence.toString}",
              ),
            )

  override def readByIds(
      chainId: ChainId,
      topic: GossipTopic,
      ids: Vector[StableArtifactId],
  ): F[Vector[AvailableGossipEvent[HotStuffGossipArtifact]]] =
    ref.get.map: state =>
      val latestById =
        state
          .getOrElse(
            ChainTopic(chainId, topic),
            Vector.empty[AvailableGossipEvent[HotStuffGossipArtifact]],
          )
          .foldLeft(
            Map.empty[StableArtifactId, AvailableGossipEvent[
              HotStuffGossipArtifact,
            ]],
          ): (acc, available) =>
            acc.updated(available.event.id, available)
      ids.distinct.flatMap(latestById.get)

  private def cursorFor(
      sequence: Long,
  ): CursorToken =
    CursorToken.unsafeIssue:
      ByteVector.view:
        ByteBuffer.allocate(java.lang.Long.BYTES).putLong(sequence).array()

  private def decodeSequence(
      token: CursorToken,
  ): Either[CanonicalRejection.StaleCursor, Long] =
    token
      .validateVersion()
      .flatMap: validated =>
        Either.cond(
          validated.payload.size == java.lang.Long.BYTES.toLong,
          ByteBuffer.wrap(validated.payload.toArray).getLong(),
          CanonicalRejection.StaleCursor(
            reason = "invalidCursorPayload",
            detail = Some(ss"size=${validated.payload.size.toString}"),
          ),
        )

/** Companion for `InMemoryHotStuffArtifactSource`. */
object InMemoryHotStuffArtifactSource:
  /** Creates a new in-memory gossip artifact source. */
  def create[F[_]: Sync](using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    Ref
      .of[F, Map[ChainTopic, Vector[
        AvailableGossipEvent[HotStuffGossipArtifact],
      ]]](Map.empty)
      .map(new InMemoryHotStuffArtifactSource[F](clock, _))

/** In-memory implementation of a gossip artifact sink for HotStuff artifacts, handling validation, QC assembly, and finalization tracking. */
final class InMemoryHotStuffArtifactSink[F[_]: Sync] private (
    validatorSet: ValidatorSet,
    relayPolicy: HotStuffRelayPolicy,
    relayPublisher: HotStuffArtifactPublisher[F],
    proposalValidation: Proposal => F[Either[HotStuffValidationFailure, Unit]],
    ref: Ref[F, InMemoryHotStuffSinkSnapshot],
) extends GossipArtifactSink[F, HotStuffGossipArtifact]:
  private type RelayEnvelope = (HotStuffGossipArtifact, Instant)

  // This sink is intentionally optimized for deterministic in-memory tests.
  // It keeps QC assembly simple and atomic, but production-backed sinks should
  // replace the repeated full re-assembly path with an incremental cache/index.
  override def applyEvent(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    event.payload match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        applyProposalEvent(event, proposal)
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        applyVoteEvent(event, vote)
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        applyTimeoutVoteEvent(event, timeoutVote)
      case HotStuffGossipArtifact.NewViewArtifact(newView) =>
        applyNewViewEvent(event, newView)

  private def applyProposalEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      proposal: Proposal,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    HotStuffValidator.validateProposal(proposal, validatorSet) match
      case Left(error) =>
        artifactRejected(error).asLeft[ArtifactApplyResult].pure[F]
      case Right(_) =>
        proposalValidation(proposal).flatMap:
          case Left(error) =>
            artifactRejected(error).asLeft[ArtifactApplyResult].pure[F]
          case Right(_) =>
            ref
              .modify: snapshot =>
                if snapshot.proposals.contains(proposal.proposalId) then
                  snapshot.copy(duplicates = snapshot.duplicates :+ event) -> (
                    ArtifactApplyResult(
                      applied = false,
                      duplicate = true,
                    ) -> Option
                      .empty[RelayEnvelope]
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
                else
                  val updatedProposals =
                    snapshot.proposals.updated(proposal.proposalId, proposal)
                  val updatedQcs =
                    snapshot.qcs.updated(
                      proposal.justify.subject.proposalId,
                      proposal.justify,
                    )
                  val assembled =
                    assembleQuorumCertificate(
                      QuorumCertificateSubject(
                        window = proposal.window,
                        proposalId = proposal.proposalId,
                        blockId = proposal.targetBlockId,
                      ),
                      snapshot.accumulator
                        .votesFor(proposal.window, proposal.proposalId),
                    )
                  val finalQcs =
                    assembled.fold(updatedQcs)(qc =>
                      updatedQcs.updated(proposal.proposalId, qc),
                    )
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  withFinalization(
                    snapshot.copy(
                      proposals = updatedProposals,
                      qcs = finalQcs,
                    ),
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
              .flatMap(finalizeApply)

  private def applyVoteEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      vote: Vote,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: snapshot =>
        if snapshot.votes.contains(vote.voteId) then
          snapshot.copy(duplicates = snapshot.duplicates :+ event) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          HotStuffValidator.validateVote(vote, validatorSet) match
            case Left(error) =>
              snapshot -> artifactRejected(error)
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Right(_) =>
              snapshot.accumulator.record(vote) match
                case Left(error) =>
                  snapshot -> artifactRejected(error)
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right((updatedAccumulator, _)) =>
                  val updatedVotes =
                    snapshot.votes.updated(vote.voteId, vote)
                  val maybeProposal =
                    snapshot.proposals.get(vote.targetProposalId)
                  val maybeQc =
                    maybeProposal.flatMap: proposal =>
                      assembleQuorumCertificate(
                        QuorumCertificateSubject(
                          window = proposal.window,
                          proposalId = proposal.proposalId,
                          blockId = proposal.targetBlockId,
                        ),
                        updatedAccumulator
                          .votesFor(proposal.window, proposal.proposalId),
                      )
                  val updatedQcs =
                    maybeProposal
                      .flatMap(_ => maybeQc)
                      .fold(snapshot.qcs): qc =>
                        snapshot.qcs.updated(qc.subject.proposalId, qc)
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  // Finalization currently derives only from stored proposal
                  // justify chains, so vote-only updates intentionally retain
                  // the last computed finalization snapshot.
                  snapshot.copy(
                    votes = updatedVotes,
                    accumulator = updatedAccumulator,
                    qcs = updatedQcs,
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def applyTimeoutVoteEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      timeoutVote: TimeoutVote,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: snapshot =>
        if snapshot.timeoutVotes.contains(timeoutVote.timeoutVoteId) then
          snapshot.copy(duplicates = snapshot.duplicates :+ event) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          HotStuffValidator.validateTimeoutVote(timeoutVote, validatorSet) match
            case Left(error) =>
              snapshot -> artifactRejected(error)
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Right(_) =>
              snapshot.timeoutAccumulator.record(timeoutVote) match
                case Left(error) =>
                  snapshot -> artifactRejected(error)
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right((updatedAccumulator, _)) =>
                  val updatedTimeoutVotes =
                    snapshot.timeoutVotes.updated(
                      timeoutVote.timeoutVoteId,
                      timeoutVote,
                    )
                  val maybeTimeoutCertificate =
                    assembleTimeoutCertificate(
                      timeoutVote.subject,
                      updatedAccumulator.votesFor(timeoutVote.subject),
                    )
                  val updatedTimeoutCertificates =
                    maybeTimeoutCertificate.fold(snapshot.timeoutCertificates):
                      timeoutCertificate =>
                        snapshot.timeoutCertificates.updated(
                          timeoutCertificate.subject,
                          timeoutCertificate,
                        )
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  snapshot.copy(
                    timeoutVotes = updatedTimeoutVotes,
                    timeoutAccumulator = updatedAccumulator,
                    timeoutCertificates = updatedTimeoutCertificates,
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def applyNewViewEvent(
      event: GossipEvent[HotStuffGossipArtifact],
      newView: NewView,
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    ref
      .modify: snapshot =>
        if snapshot.newViews.contains(newView.newViewId) then
          snapshot.copy(duplicates = snapshot.duplicates :+ event) -> (
            ArtifactApplyResult(applied = false, duplicate = true) -> Option
              .empty[RelayEnvelope]
          ).asRight[CanonicalRejection.ArtifactContractRejected]
        else
          val senderWindowKey = (newView.window, newView.sender)
          snapshot.newViewsBySenderWindow.get(senderWindowKey) match
            case Some(existing) if existing.newViewId =!= newView.newViewId =>
              snapshot -> CanonicalRejection
                .ArtifactContractRejected(
                  reason = "conflictingNewView",
                  detail = Some(newView.sender.value),
                )
                .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
            case Some(_) =>
              snapshot.copy(duplicates = snapshot.duplicates :+ event) -> (
                ArtifactApplyResult(applied = false, duplicate = true) -> Option
                  .empty[RelayEnvelope]
              ).asRight[CanonicalRejection.ArtifactContractRejected]
            case None =>
              HotStuffValidator.validateNewView(newView, validatorSet) match
                case Left(error) =>
                  snapshot -> artifactRejected(error)
                    .asLeft[(ArtifactApplyResult, Option[RelayEnvelope])]
                case Right(_) =>
                  val relayArtifact =
                    if relayPolicy.relayValidatedArtifacts then
                      Some(event.payload -> event.ts)
                    else Option.empty[RelayEnvelope]
                  snapshot.copy(
                    newViews =
                      snapshot.newViews.updated(newView.newViewId, newView),
                    newViewsBySenderWindow =
                      snapshot.newViewsBySenderWindow.updated(
                        senderWindowKey,
                        newView,
                      ),
                  ) -> (
                    ArtifactApplyResult(
                      applied = true,
                      duplicate = false,
                    ) -> relayArtifact
                  ).asRight[CanonicalRejection.ArtifactContractRejected]
      .flatMap(finalizeApply)

  private def finalizeApply(
      stored: Either[
        CanonicalRejection.ArtifactContractRejected,
        (ArtifactApplyResult, Option[RelayEnvelope]),
      ],
  ): F[
    Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult],
  ] =
    stored match
      case Left(rejection) =>
        rejection.asLeft[ArtifactApplyResult].pure[F]
      case Right((result, maybeRelay)) =>
        maybeRelay
          .traverse_ { case (artifact, ts) =>
            relayPublisher.append(artifact, ts).void
          }
          .as(result.asRight)

  private def artifactRejected(
      error: HotStuffValidationFailure,
  ): CanonicalRejection.ArtifactContractRejected =
    CanonicalRejection.ArtifactContractRejected(
      reason = error.reason,
      detail = error.detail,
    )

  private def assembleQuorumCertificate(
      subject: QuorumCertificateSubject,
      votes: Vector[Vote],
  ): Option[QuorumCertificate] =
    QuorumCertificateAssembler
      .assemble(subject, votes, validatorSet)
      .toOption

  private def assembleTimeoutCertificate(
      subject: TimeoutVoteSubject,
      votes: Vector[TimeoutVote],
  ): Option[TimeoutCertificate] =
    TimeoutCertificateAssembler
      .assemble(subject, votes, validatorSet)
      .toOption

  private def withFinalization(
      snapshot: InMemoryHotStuffSinkSnapshot,
  ): InMemoryHotStuffSinkSnapshot =
    snapshot.copy(
      finalization = HotStuffFinalizationTracker.trackAll(
        snapshot.proposals.values,
      ),
    )

  /** Returns the current sink snapshot. */
  def snapshot: F[InMemoryHotStuffSinkSnapshot] =
    ref.get

/** Companion for `InMemoryHotStuffArtifactSink`. */
object InMemoryHotStuffArtifactSink:
  def create[F[_]: Sync](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
  ): F[InMemoryHotStuffArtifactSink[F]] =
    Ref
      .of[F, InMemoryHotStuffSinkSnapshot](InMemoryHotStuffSinkSnapshot.empty)
      .map:
        new InMemoryHotStuffArtifactSink[F](
          validatorSet,
          relayPolicy,
          relayPublisher,
          HotStuffRuntimeScheduling.allowAll[F],
          _,
        )

  def createWithProposalValidation[F[_]
    : Sync, TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
      validatorSet: ValidatorSet,
      relayPolicy: HotStuffRelayPolicy,
      relayPublisher: HotStuffArtifactPublisher[F],
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  ): F[InMemoryHotStuffArtifactSink[F]] =
    Ref
      .of[F, InMemoryHotStuffSinkSnapshot](InMemoryHotStuffSinkSnapshot.empty)
      .map:
        new InMemoryHotStuffArtifactSink[F](
          validatorSet,
          relayPolicy,
          relayPublisher,
          HotStuffRuntimeScheduling.proposalValidationFromBlockQuery(
            validatorSet = validatorSet,
            blockQuery = blockQuery,
          )(classifyTx),
          _,
        )
