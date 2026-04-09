package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.{Duration, Instant}

import cats.effect.kernel.{Async, Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{Hash, KeyPair}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{
  BlockBody,
  BlockHeader,
  BlockHeight,
  BlockQuery,
  BlockRecord,
  BlockStore,
  BlockTimestamp,
  BlockView,
  StateRoot,
}
import org.sigilaris.node.jvm.runtime.gossip.*

enum HotStuffGossipArtifact:
  case ProposalArtifact(proposal: Proposal)
  case VoteArtifact(vote: Vote)
  case TimeoutVoteArtifact(timeoutVote: TimeoutVote)
  case NewViewArtifact(newView: NewView)

object HotStuffGossipArtifact:
  def topicOf(
      artifact: HotStuffGossipArtifact,
  ): GossipTopic =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(_) =>
        GossipTopic.consensusProposal
      case HotStuffGossipArtifact.VoteArtifact(_) => GossipTopic.consensusVote
      case HotStuffGossipArtifact.TimeoutVoteArtifact(_) =>
        GossipTopic.consensusTimeoutVote
      case HotStuffGossipArtifact.NewViewArtifact(_) =>
        GossipTopic.consensusNewView

  def stableIdOf(
      artifact: HotStuffGossipArtifact,
  ): StableArtifactId =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        StableArtifactId.unsafeFromBytes:
          proposal.proposalId.toUInt256.bytes
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        StableArtifactId.unsafeFromBytes:
          vote.voteId.toUInt256.bytes
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        StableArtifactId.unsafeFromBytes:
          timeoutVote.timeoutVoteId.toUInt256.bytes
      case HotStuffGossipArtifact.NewViewArtifact(newView) =>
        StableArtifactId.unsafeFromBytes:
          newView.newViewId.toUInt256.bytes

  given ByteEncoder[HotStuffGossipArtifact] = artifact =>
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        ByteVector.fromByte(0x01.toByte) ++ proposal.toBytes
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        ByteVector.fromByte(0x02.toByte) ++ vote.toBytes
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        ByteVector.fromByte(0x03.toByte) ++ timeoutVote.toBytes
      case HotStuffGossipArtifact.NewViewArtifact(newView) =>
        ByteVector.fromByte(0x04.toByte) ++ newView.toBytes

final case class HotStuffTopicPolicy(
    exactKnownSetLimit: Int,
    requestByIdLimit: Int,
    maxBatchItems: Int,
    flushInterval: Duration,
    deliveryPriority: Int,
):
  require(exactKnownSetLimit > 0, "exactKnownSetLimit must be positive")
  require(requestByIdLimit > 0, "requestByIdLimit must be positive")
  require(maxBatchItems > 0, "maxBatchItems must be positive")

  def producerQoS: GossipProducerQoS =
    new GossipProducerQoS:
      override def maxBatchItems: Int = HotStuffTopicPolicy.this.maxBatchItems
      override def flushInterval: Duration =
        HotStuffTopicPolicy.this.flushInterval

final case class HotStuffGossipPolicy(
    proposal: HotStuffTopicPolicy,
    vote: HotStuffTopicPolicy,
    timeoutVote: HotStuffTopicPolicy,
    newView: HotStuffTopicPolicy,
)

object HotStuffGossipPolicy:
  private val defaultProposalPolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy(
      exactKnownSetLimit = 256,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxProposalRequestIds,
      maxBatchItems = 32,
      flushInterval = Duration.ZERO,
      deliveryPriority = 2,
    )

  private val defaultVotePolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy(
      exactKnownSetLimit = 2048,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 256,
      flushInterval = Duration.ZERO,
      deliveryPriority = 1,
    )

  private val defaultTimeoutVotePolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy(
      exactKnownSetLimit = 2048,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 128,
      flushInterval = Duration.ZERO,
      deliveryPriority = 3,
    )

  private val defaultNewViewPolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy(
      exactKnownSetLimit = 1024,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 64,
      flushInterval = Duration.ZERO,
      deliveryPriority = 4,
    )

  val default: HotStuffGossipPolicy =
    HotStuffGossipPolicy(
      proposal = defaultProposalPolicy,
      vote = defaultVotePolicy,
      timeoutVote = defaultTimeoutVotePolicy,
      newView = defaultNewViewPolicy,
    )

object HotStuffWindowKey:
  private final case class WindowInput(
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
  ) derives ByteEncoder

  def fromWindow(
      window: HotStuffWindow,
  ): TopicWindowKey =
    TopicWindowKey.unsafeFromBytes:
      WindowInput(
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        validatorSetHash = window.validatorSetHash,
      ).toBytes

object HotStuffTopic:
  private def scopeForWindow(
      topic: GossipTopic,
      chainId: ChainId,
      window: HotStuffWindow,
  ): Option[ExactKnownSetScope] =
    Some(
      ExactKnownSetScope(
        chainId = chainId,
        topic = topic,
        windowKey = HotStuffWindowKey.fromWindow(window),
      ),
    )

  def proposalContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusProposal
      override val exactKnownSetLimit: Option[Int] = Some:
        policy.exactKnownSetLimit
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int         = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            val expectedId = StableArtifactId.unsafeFromBytes:
              proposal.proposalId.toUInt256.bytes
            Either.cond(
              event.topic === topic &&
                event.chainId === proposal.window.chainId &&
                event.id === expectedId,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusProposalEvent",
                detail = Some(proposal.proposalId.toHexLower),
              ),
            )
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Unit]

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[
        ExactKnownSetScope,
      ]] =
        event.payload match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            scopeForWindow(topic, proposal.window.chainId, proposal.window)
              .asRight[CanonicalRejection.ArtifactContractRejected]
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Option[ExactKnownSetScope]]

  def voteContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusVote
      override val exactKnownSetLimit: Option[Int] = Some:
        policy.exactKnownSetLimit
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int         = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.VoteArtifact(vote) =>
            val expectedId = StableArtifactId.unsafeFromBytes:
              vote.voteId.toUInt256.bytes
            Either.cond(
              event.topic === topic &&
                event.chainId === vote.window.chainId &&
                event.id === expectedId,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusVoteEvent",
                detail = Some(vote.voteId.toHexLower),
              ),
            )
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Unit]

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[
        ExactKnownSetScope,
      ]] =
        event.payload match
          case HotStuffGossipArtifact.VoteArtifact(vote) =>
            scopeForWindow(topic, vote.window.chainId, vote.window)
              .asRight[CanonicalRejection.ArtifactContractRejected]
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Option[ExactKnownSetScope]]

  def timeoutVoteContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusTimeoutVote
      override val exactKnownSetLimit: Option[Int] = Some:
        policy.exactKnownSetLimit
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int         = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            val expectedId = StableArtifactId.unsafeFromBytes:
              timeoutVote.timeoutVoteId.toUInt256.bytes
            Either.cond(
              event.topic === topic &&
                event.chainId === timeoutVote.subject.window.chainId &&
                event.id === expectedId,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusTimeoutVoteEvent",
                detail = Some(timeoutVote.timeoutVoteId.toHexLower),
              ),
            )
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Unit]

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[
        ExactKnownSetScope,
      ]] =
        event.payload match
          case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
            scopeForWindow(
              topic,
              timeoutVote.subject.window.chainId,
              timeoutVote.subject.window,
            ).asRight[CanonicalRejection.ArtifactContractRejected]
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Option[ExactKnownSetScope]]

  def newViewContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusNewView
      override val exactKnownSetLimit: Option[Int] = Some:
        policy.exactKnownSetLimit
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int         = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            val expectedId = StableArtifactId.unsafeFromBytes:
              newView.newViewId.toUInt256.bytes
            Either.cond(
              event.topic === topic &&
                event.chainId === newView.window.chainId &&
                event.id === expectedId,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusNewViewEvent",
                detail = Some(newView.newViewId.toHexLower),
              ),
            )
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Unit]

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[
        ExactKnownSetScope,
      ]] =
        event.payload match
          case HotStuffGossipArtifact.NewViewArtifact(newView) =>
            scopeForWindow(topic, newView.window.chainId, newView.window)
              .asRight[CanonicalRejection.ArtifactContractRejected]
          case _ =>
            CanonicalRejection
              .ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
              .asLeft[Option[ExactKnownSetScope]]

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def registry(
      policy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
  ): GossipTopicContractRegistry[HotStuffGossipArtifact] =
    GossipTopicContractRegistry.of(
      proposalContract(policy.proposal),
      voteContract(policy.vote),
      timeoutVoteContract(policy.timeoutVote),
      newViewContract(policy.newView),
    )

final case class InMemoryHotStuffSourceSnapshot(
    eventsByTopic: Map[ChainTopic, Vector[GossipEvent[HotStuffGossipArtifact]]],
)

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

object InMemoryHotStuffSinkSnapshot:
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

trait HotStuffArtifactPublisher[F[_]]:
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]]

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
    CursorToken.issue:
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

object InMemoryHotStuffArtifactSource:
  def create[F[_]: Sync](using
      clock: GossipClock[F],
  ): F[InMemoryHotStuffArtifactSource[F]] =
    Ref
      .of[F, Map[ChainTopic, Vector[
        AvailableGossipEvent[HotStuffGossipArtifact],
      ]]](Map.empty)
      .map(new InMemoryHotStuffArtifactSource[F](clock, _))

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

  def snapshot: F[InMemoryHotStuffSinkSnapshot] =
    ref.get

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

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffRuntimeBootstrapInput(
    localPeer: PeerIdentity,
    role: LocalNodeRole,
    holders: Vector[ValidatorKeyHolder],
    validatorSet: ValidatorSet,
    localKeys: Map[ValidatorId, KeyPair],
    gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
    bootstrapTrustRootOverride: Option[BootstrapTrustRoot] = None,
):
  def bootstrapTrustRoot: BootstrapTrustRoot =
    bootstrapTrustRootOverride.getOrElse:
      BootstrapTrustRoot.staticValidatorSet(validatorSet)

/** Runtime-owned HotStuff service surface.
  *
  * The bootstrap caller owns the coherence between `publisher` and `source`. If
  * locally emitted artifacts must later be readable through `source`, both
  * endpoints need to target the same backing store or an equivalent replicated
  * feed.
  */
final case class HotStuffRuntimeServices[F[_]](
    publisher: HotStuffArtifactPublisher[F],
    source: GossipArtifactSource[F, HotStuffGossipArtifact],
    sink: GossipArtifactSink[F, HotStuffGossipArtifact],
    topicContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
    bootstrap: HotStuffBootstrapServices[F],
)

final case class HotStuffInMemoryRuntimeDiagnostics[F[_]](
    source: InMemoryHotStuffArtifactSource[F],
    sink: InMemoryHotStuffArtifactSink[F],
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HotStuffNodeRuntime[F[_]: Sync](
    bootstrapInput: HotStuffRuntimeBootstrapInput,
    services: HotStuffRuntimeServices[F],
    diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]] = None,
    bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]] = None,
    pacemakerSnapshot: Option[F[HotStuffPacemakerRuntimeSnapshot]] = None,
):
  def localPeer: PeerIdentity = bootstrapInput.localPeer

  def role: LocalNodeRole = bootstrapInput.role

  def holders: Vector[ValidatorKeyHolder] = bootstrapInput.holders

  def validatorSet: ValidatorSet = bootstrapInput.validatorSet

  def localKeys: Map[ValidatorId, KeyPair] = bootstrapInput.localKeys

  def gossipPolicy: HotStuffGossipPolicy = bootstrapInput.gossipPolicy

  def bootstrapTrustRoot: BootstrapTrustRoot =
    bootstrapInput.bootstrapTrustRoot

  def bootstrapServices: HotStuffBootstrapServices[F] =
    services.bootstrap

  def source: GossipArtifactSource[F, HotStuffGossipArtifact] = services.source

  def sink: GossipArtifactSink[F, HotStuffGossipArtifact] = services.sink

  def topicContracts: GossipTopicContractRegistry[HotStuffGossipArtifact] =
    services.topicContracts

  def inMemorySource: Option[InMemoryHotStuffArtifactSource[F]] =
    diagnostics.map(_.source)

  def inMemorySink: Option[InMemoryHotStuffArtifactSink[F]] =
    diagnostics.map(_.sink)

  def currentBootstrapDiagnostics: F[BootstrapDiagnostics] =
    bootstrapLifecycle.fold(services.bootstrap.diagnostics.current)(_.current)

  def currentPacemakerSnapshot: F[Option[HotStuffPacemakerRuntimeSnapshot]] =
    pacemakerSnapshot match
      case Some(snapshot) =>
        snapshot.map(_.some)
      case None =>
        none[HotStuffPacemakerRuntimeSnapshot].pure[F]

  def close: F[Unit] =
    bootstrapLifecycle.fold(Sync[F].unit)(_.close)

  def bootstrap(
      chainId: ChainId,
      sessions: Vector[BootstrapSessionBinding],
      startedAt: Instant,
      liveProposals: Vector[Proposal],
  )(using
      Async[F],
  ): F[Either[BootstrapCoordinatorFailure, BootstrapCoordinatorResult]] =
    bootstrapLifecycle match
      case Some(lifecycle) =>
        lifecycle.bootstrap(
          chainId = chainId,
          sessions = sessions,
          startedAt = startedAt,
          liveProposals = liveProposals,
        )
      case None =>
        BootstrapCoordinatorFailure(
          reason = "bootstrapLifecycleUnavailable",
          detail = None,
        ).asLeft[BootstrapCoordinatorResult].pure[F]

  def emitProposal(
      proposer: ValidatorId,
      block: BlockHeader,
      txSet: ProposalTxSet,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    withLocalSigner(proposer, window.chainId): keyPair =>
      Proposal.sign(
        UnsignedProposal(
          window = window,
          proposer = proposer,
          targetBlockId = BlockHeader.computeId(block),
          block = block,
          txSet = txSet,
          justify = justify,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "proposalSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
        case Right(proposal) =>
          services.publisher
            .append(
              HotStuffGossipArtifact.ProposalArtifact(proposal),
              ts,
            )
            .map(_.asRight[HotStuffPolicyViolation])

  def signTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
  ): F[Either[HotStuffPolicyViolation, TimeoutVote]] =
    withLocalSigner(voter, window.chainId): keyPair =>
      TimeoutVote.sign(
        UnsignedTimeoutVote(
          subject = TimeoutVoteSubject(
            window = window,
            highestKnownQc = highestKnownQc.subject,
          ),
          voter = voter,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "timeoutVoteSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[TimeoutVote].pure[F]
        case Right(timeoutVote) =>
          timeoutVote.asRight[HotStuffPolicyViolation].pure[F]

  def emitTimeoutVote(
      voter: ValidatorId,
      window: HotStuffWindow,
      highestKnownQc: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    signTimeoutVote(voter, window, highestKnownQc).flatMap:
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(timeoutVote) =>
        services.publisher
          .append(
            HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote),
            ts,
          )
          .map(_.asRight[HotStuffPolicyViolation])

  def signNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
  ): F[Either[HotStuffPolicyViolation, NewView]] =
    val nextWindow =
      HotStuffPacemaker.nextWindowAfter(timeoutCertificate.subject.window)
    val nextLeader =
      HotStuffPacemaker.deterministicLeader(nextWindow, validatorSet)
    withLocalSigner(sender, nextWindow.chainId): keyPair =>
      NewView.sign(
        UnsignedNewView(
          window = nextWindow,
          sender = sender,
          nextLeader = nextLeader,
          highestKnownQc = highestKnownQc,
          timeoutCertificate = timeoutCertificate,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "newViewSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[NewView].pure[F]
        case Right(newView) =>
          newView.asRight[HotStuffPolicyViolation].pure[F]

  def emitNewView(
      sender: ValidatorId,
      highestKnownQc: QuorumCertificate,
      timeoutCertificate: TimeoutCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    signNewView(sender, highestKnownQc, timeoutCertificate).flatMap:
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(newView) =>
        services.publisher
          .append(
            HotStuffGossipArtifact.NewViewArtifact(newView),
            ts,
          )
          .map(_.asRight[HotStuffPolicyViolation])

  def emitProposalFromCandidates[
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      proposer: ValidatorId,
      candidates: Iterable[BlockRecord[TxRef, ResultRef, Event]],
      parent: Option[BlockId],
      height: BlockHeight,
      stateRoot: StateRoot,
      timestamp: BlockTimestamp,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
      blockStore: BlockStore[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  ): F[Either[
    HotStuffRuntimeRejection,
    HotStuffProposalEmission[TxRef, ResultRef, Event],
  ]] =
    val selection =
      ConflictFreeBlockBodySelector.select(candidates)(classifyTx)
    selection.toBody match
      case Left(failure) =>
        HotStuffRuntimeRejection
          .Validation(failure)
          .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
          .pure[F]
      case Right(body) =>
        BlockBody
          .computeBodyRoot(body)
          .leftMap(HotStuffRuntimeScheduling.fromBlockValidationFailure) match
          case Left(failure) =>
            HotStuffRuntimeRejection
              .Validation(failure)
              .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
              .pure[F]
          case Right(bodyRoot) =>
            val view =
              BlockView(
                header = BlockHeader(
                  parent = parent,
                  height = height,
                  stateRoot = stateRoot,
                  bodyRoot = bodyRoot,
                  timestamp = timestamp,
                ),
                body = body,
              )
            blockStore
              .putView(view)
              .leftMap(HotStuffRuntimeScheduling.fromBlockValidationFailure)
              .value
              .flatMap:
                case Left(failure) =>
                  HotStuffRuntimeRejection
                    .Validation(failure)
                    .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
                    .pure[F]
                case Right(_) =>
                  val proposalTxSet =
                    ProposalTxSet.fromTxs(
                      view.body.records.toVector.map(_.tx),
                    )
                  emitProposal(
                    proposer = proposer,
                    block = view.header,
                    txSet = proposalTxSet,
                    window = window,
                    justify = justify,
                    ts = ts,
                  ).map(
                    _.leftMap(HotStuffRuntimeRejection.Policy.apply).map:
                      event =>
                        HotStuffProposalEmission(
                          selection = selection,
                          view = view,
                          event = event,
                        ),
                  )

  def emitVote(
      voter: ValidatorId,
      proposal: Proposal,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    withLocalSigner(voter, proposal.window.chainId): keyPair =>
      Vote.sign(
        UnsignedVote(
          window = proposal.window,
          voter = voter,
          targetProposalId = proposal.proposalId,
        ),
        keyPair,
      ) match
        case Left(error) =>
          HotStuffPolicyViolation(
            reason = "voteSigningFailed",
            detail = Some(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            ),
          ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
        case Right(vote) =>
          services.publisher
            .append(
              HotStuffGossipArtifact.VoteArtifact(vote),
              ts,
            )
            .map(_.asRight[HotStuffPolicyViolation])

  def emitVoteForProposalView[
      TxRef: ByteEncoder: Hash,
      ResultRef: ByteEncoder,
      Event: ByteEncoder,
  ](
      voter: ValidatorId,
      proposal: Proposal,
      ts: Instant,
      blockQuery: BlockQuery[F, TxRef, ResultRef, Event],
  )(
      classifyTx: TxRef => org.sigilaris.core.application.scheduling.SchedulingClassification,
  ): F[Either[HotStuffRuntimeRejection, GossipEvent[HotStuffGossipArtifact]]] =
    HotStuffRuntimeScheduling
      .validateProposalViewFromBlockQuery(
        proposal = proposal,
        validatorSet = validatorSet,
        blockQuery = blockQuery,
      )(classifyTx)
      .flatMap:
        case Left(failure) =>
          HotStuffRuntimeRejection
            .Validation(failure)
            .asLeft[GossipEvent[HotStuffGossipArtifact]]
            .pure[F]
        case Right(_) =>
          emitVote(voter, proposal, ts)
            .map(_.leftMap(HotStuffRuntimeRejection.Policy.apply))

  private def withLocalSigner[A](
      validatorId: ValidatorId,
      chainId: ChainId,
  )(
      f: KeyPair => F[Either[HotStuffPolicyViolation, A]],
  ): F[Either[HotStuffPolicyViolation, A]] =
    resolveSigner(validatorId) match
      case Left(rejection) =>
        rejection.asLeft[A].pure[F]
      case Right(keyPair) =>
        ensureQuorumParticipationReadiness(chainId).flatMap:
          case Left(rejection) =>
            rejection.asLeft[A].pure[F]
          case Right(_) =>
            f(keyPair)

  private def resolveSigner(
      validatorId: ValidatorId,
  ): Either[HotStuffPolicyViolation, KeyPair] =
    HotStuffPolicy
      .canEmitLocally(role, localPeer, validatorId, holders)
      .flatMap: _ =>
        localKeys
          .get(validatorId)
          .toRight:
            HotStuffPolicyViolation(
              reason = "localValidatorKeyUnavailable",
              detail = Some(ss"${validatorId.value}@${localPeer.value}"),
            )

  private def ensureQuorumParticipationReadiness(
      chainId: ChainId,
  ): F[Either[HotStuffPolicyViolation, Unit]] =
    bootstrapLifecycle match
      case Some(lifecycle) if role === LocalNodeRole.Validator =>
        lifecycle
          .voteReadiness(chainId)
          .map:
            case BootstrapVoteReadiness.Ready =>
              ().asRight[HotStuffPolicyViolation]
            case BootstrapVoteReadiness.Held(reason) =>
              HotStuffPolicyViolation(
                reason = "bootstrapVoteHeld",
                detail = Some(reason),
              ).asLeft[Unit]
      case _ =>
        ().asRight[HotStuffPolicyViolation].pure[F]

object HotStuffNodeRuntime:
  def validateBootstrapInput(
      bootstrapInput: HotStuffRuntimeBootstrapInput,
  ): Either[HotStuffPolicyViolation, HotStuffRuntimeBootstrapInput] =
    HotStuffPolicy
      .ensureDistinctActiveKeyHolders(bootstrapInput.holders)
      .map(_ => bootstrapInput)

  private[hotstuff] def fromValidatedServices[F[_]: Sync](
      bootstrapInput: HotStuffRuntimeBootstrapInput,
      services: HotStuffRuntimeServices[F],
      diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]],
      bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]],
  ): HotStuffNodeRuntime[F] =
    HotStuffNodeRuntime(
      bootstrapInput = bootstrapInput,
      services = services,
      diagnostics = diagnostics,
      bootstrapLifecycle = bootstrapLifecycle,
      pacemakerSnapshot = None,
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromServices[F[_]: Sync](
      bootstrapInput: HotStuffRuntimeBootstrapInput,
      services: HotStuffRuntimeServices[F],
      diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]] = None,
      bootstrapLifecycle: Option[HotStuffBootstrapLifecycle[F]] = None,
  ): Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]] =
    validateBootstrapInput(bootstrapInput)
      .map(fromValidatedServices(_, services, diagnostics, bootstrapLifecycle))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  // This helper intentionally assembles only the artifact-plane in-memory
  // runtime. The newcomer bootstrap lifecycle is wired by
  // `HotStuffRuntimeBootstrap.fromTopology`, not by the raw test helper path.
  def inMemoryServices[F[_]: Sync](
      validatorSet: ValidatorSet,
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
      relayPolicy: HotStuffRelayPolicy =
        HotStuffRelayPolicy(relayValidatedArtifacts = false),
  )(using
      clock: GossipClock[F],
  ): F[(HotStuffRuntimeServices[F], HotStuffInMemoryRuntimeDiagnostics[F])] =
    for
      source <- InMemoryHotStuffArtifactSource.create[F]
      sink <- InMemoryHotStuffArtifactSink
        .create[F](validatorSet, relayPolicy, source)
    yield
      val diagnostics =
        HotStuffInMemoryRuntimeDiagnostics(source = source, sink = sink)
      val services =
        HotStuffRuntimeServices(
          publisher = source,
          source = source,
          sink = sink,
          topicContracts = HotStuffTopic.registry(gossipPolicy),
          bootstrap =
            HotStuffBootstrapServicesRuntime.inMemory[F](validatorSet, sink),
        )
      services -> diagnostics

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  // `create` remains a lightweight test/runtime convenience that omits the
  // shipped newcomer bootstrap lifecycle. Use `HotStuffRuntimeBootstrap` when
  // the assembled bootstrap gate and diagnostics are required. Automatic
  // consensus stays opt-in here so manual harnesses can seed proposals/votes
  // deterministically; the assembled bootstrap path enables it explicitly.
  def create[F[_]: Sync](
      localPeer: PeerIdentity,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      validatorSet: ValidatorSet,
      localKeys: Map[ValidatorId, KeyPair],
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
      automaticConsensus: Boolean = false,
  )(using
      clock: GossipClock[F],
  ): F[Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]]] =
    val bootstrapInput =
      HotStuffRuntimeBootstrapInput(
        localPeer = localPeer,
        role = role,
        holders = holders,
        validatorSet = validatorSet,
        localKeys = localKeys,
        gossipPolicy = gossipPolicy,
      )
    validateBootstrapInput(bootstrapInput) match
      case Left(rejection) =>
        rejection.asLeft[HotStuffNodeRuntime[F]].pure[F]
      case Right(validatedInput) =>
        inMemoryServices[F](
          validatorSet,
          gossipPolicy,
          HotStuffRelayPolicy.forRole(role),
        ).flatMap: (services, diagnostics) =>
          InMemoryHotStuffPacemakerDriver
            .attach(
              fromValidatedServices(
                validatedInput,
                services,
                Some(diagnostics),
                none[HotStuffBootstrapLifecycle[F]],
              ),
              automaticConsensus = automaticConsensus,
            )
            .map(_.asRight[HotStuffPolicyViolation])
