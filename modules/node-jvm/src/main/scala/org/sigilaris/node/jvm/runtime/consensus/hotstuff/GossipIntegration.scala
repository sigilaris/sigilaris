package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.KeyPair
import org.sigilaris.node.jvm.runtime.gossip.*

enum HotStuffGossipArtifact:
  case ProposalArtifact(proposal: Proposal)
  case VoteArtifact(vote: Vote)

object HotStuffGossipArtifact:
  def topicOf(
      artifact: HotStuffGossipArtifact,
  ): GossipTopic =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(_) => GossipTopic.consensusProposal
      case HotStuffGossipArtifact.VoteArtifact(_)     => GossipTopic.consensusVote

  def stableIdOf(
      artifact: HotStuffGossipArtifact,
  ): StableArtifactId =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        StableArtifactId.unsafeFromBytes(proposal.proposalId.toUInt256.bytes)
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        StableArtifactId.unsafeFromBytes(vote.voteId.toUInt256.bytes)

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
      override def flushInterval: Duration = HotStuffTopicPolicy.this.flushInterval

final case class HotStuffGossipPolicy(
    proposal: HotStuffTopicPolicy =
      HotStuffTopicPolicy(
        exactKnownSetLimit = 256,
        requestByIdLimit = HotStuffPolicy.requestPolicy.maxProposalRequestIds,
        maxBatchItems = 32,
        flushInterval = Duration.ZERO,
        deliveryPriority = 2,
      ),
    vote: HotStuffTopicPolicy =
      HotStuffTopicPolicy(
        exactKnownSetLimit = 2048,
        requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
        maxBatchItems = 256,
        flushInterval = Duration.ZERO,
        deliveryPriority = 1,
      ),
)

object HotStuffWindowKey:
  private final case class WindowInput(
      chainId: ChainId,
      height: Long,
      view: Long,
      validatorSetHash: ValidatorSetHash,
  ) derives ByteEncoder

  def fromWindow(
      window: HotStuffWindow,
  ): TopicWindowKey =
    TopicWindowKey.unsafeFromBytes(
      WindowInput(
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        validatorSetHash = window.validatorSetHash,
      ).toBytes
    )

object HotStuffTopic:
  def proposalContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusProposal
      override val exactKnownSetLimit: Option[Int] = Some(policy.exactKnownSetLimit)
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            Either.cond(
              event.topic == topic &&
                event.chainId == proposal.window.chainId &&
                event.id.bytes == proposal.proposalId.toUInt256.bytes,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusProposalEvent",
                detail = Some(proposal.proposalId.toHexLower),
              ),
            )
          case _ =>
            Left(
              CanonicalRejection.ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
            )

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[ExactKnownSetScope]] =
        event.payload match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
            Right(
              Some(
                ExactKnownSetScope(
                  chainId = proposal.window.chainId,
                  topic = topic,
                  windowKey = HotStuffWindowKey.fromWindow(proposal.window),
                )
              )
            )
          case _ =>
            Left(
              CanonicalRejection.ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
            )

  def voteContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = GossipTopic.consensusVote
      override val exactKnownSetLimit: Option[Int] = Some(policy.exactKnownSetLimit)
      override val requestByIdLimit: Option[Int] = Some(policy.requestByIdLimit)
      override val deliveryPriority: Int = policy.deliveryPriority

      override def producerQoS(
          default: GossipProducerQoS,
      ): GossipProducerQoS =
        policy.producerQoS

      override def validateArtifact(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        event.payload match
          case HotStuffGossipArtifact.VoteArtifact(vote) =>
            Either.cond(
              event.topic == topic &&
                event.chainId == vote.window.chainId &&
                event.id.bytes == vote.voteId.toUInt256.bytes,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = "invalidConsensusVoteEvent",
                detail = Some(vote.voteId.toHexLower),
              ),
            )
          case _ =>
            Left(
              CanonicalRejection.ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
            )

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[ExactKnownSetScope]] =
        event.payload match
          case HotStuffGossipArtifact.VoteArtifact(vote) =>
            Right(
              Some(
                ExactKnownSetScope(
                  chainId = vote.window.chainId,
                  topic = topic,
                  windowKey = HotStuffWindowKey.fromWindow(vote.window),
                )
              )
            )
          case _ =>
            Left(
              CanonicalRejection.ArtifactContractRejected(
                reason = "unexpectedTopicPayload",
                detail = Some(topic.value),
              )
            )

  def registry(
      policy: HotStuffGossipPolicy = HotStuffGossipPolicy(),
  ): GossipTopicContractRegistry[HotStuffGossipArtifact] =
    GossipTopicContractRegistry.of(
      proposalContract(policy.proposal),
      voteContract(policy.vote),
    )

final case class InMemoryHotStuffSourceSnapshot(
    eventsByTopic: Map[ChainTopic, Vector[GossipEvent[HotStuffGossipArtifact]]],
)

final case class InMemoryHotStuffSinkSnapshot(
    proposals: Map[ProposalId, Proposal],
    votes: Map[VoteId, Vote],
    accumulator: VoteAccumulator,
    qcs: Map[ProposalId, QuorumCertificate],
    duplicates: Vector[GossipEvent[HotStuffGossipArtifact]],
)

object InMemoryHotStuffSinkSnapshot:
  val empty: InMemoryHotStuffSinkSnapshot =
    InMemoryHotStuffSinkSnapshot(
      proposals = Map.empty,
      votes = Map.empty,
      accumulator = VoteAccumulator.empty,
      qcs = Map.empty,
      duplicates = Vector.empty,
    )

final class InMemoryHotStuffArtifactSource[F[_]: Sync] private (
    clock: GossipClock[F],
    ref: Ref[F, Map[ChainTopic, Vector[AvailableGossipEvent[HotStuffGossipArtifact]]]],
) extends GossipArtifactSource[F, HotStuffGossipArtifact]:
  def append(
      artifact: HotStuffGossipArtifact,
      ts: Instant,
  ): F[GossipEvent[HotStuffGossipArtifact]] =
    clock.now.flatMap: availableAt =>
      ref.modify: state =>
        val chainId = artifact match
          case HotStuffGossipArtifact.ProposalArtifact(proposal) => proposal.window.chainId
          case HotStuffGossipArtifact.VoteArtifact(vote)         => vote.window.chainId
        val topic = HotStuffGossipArtifact.topicOf(artifact)
        val chainTopic = ChainTopic(chainId, topic)
        val topicEvents = state.getOrElse(chainTopic, Vector.empty)
        val nextSequence = topicEvents.size.toLong + 1L
        val event = GossipEvent(
          chainId = chainId,
          topic = topic,
          id = HotStuffGossipArtifact.stableIdOf(artifact),
          cursor = cursorFor(nextSequence),
          ts = ts,
          payload = artifact,
        )
        val available = AvailableGossipEvent(event = event, availableAt = availableAt)
        state.updated(chainTopic, topicEvents :+ available) -> event

  override def readAfter(
      chainId: ChainId,
      topic: GossipTopic,
      cursor: Option[CursorToken],
  ): F[Either[CanonicalRejection, Vector[AvailableGossipEvent[HotStuffGossipArtifact]]]] =
    ref.get.map: state =>
      val chainTopic = ChainTopic(chainId, topic)
      val topicEvents = state.getOrElse(chainTopic, Vector.empty)
      cursor match
        case None =>
          Right(topicEvents)
        case Some(token) =>
          decodeSequence(token).flatMap: sequence =>
            val maxSequence = topicEvents.size.toLong
            Either.cond(
              sequence >= 1L && sequence <= maxSequence,
              topicEvents.drop(sequence.toInt),
              CanonicalRejection.StaleCursor(
                reason = "unknownCursor",
                detail = Some(s"sequence=$sequence max=$maxSequence"),
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
          .getOrElse(ChainTopic(chainId, topic), Vector.empty)
          .foldLeft(Map.empty[StableArtifactId, AvailableGossipEvent[HotStuffGossipArtifact]]): (acc, available) =>
            acc.updated(available.event.id, available)
      ids.distinct.flatMap(latestById.get)

  private def cursorFor(
      sequence: Long,
  ): CursorToken =
    CursorToken.issue(ByteVector.view(ByteBuffer.allocate(java.lang.Long.BYTES).putLong(sequence).array()))

  private def decodeSequence(
      token: CursorToken,
  ): Either[CanonicalRejection.StaleCursor, Long] =
    token.validateVersion().flatMap: validated =>
      Either.cond(
        validated.payload.size == java.lang.Long.BYTES.toLong,
        ByteBuffer.wrap(validated.payload.toArray).getLong(),
        CanonicalRejection.StaleCursor(
          reason = "invalidCursorPayload",
          detail = Some(s"size=${validated.payload.size}"),
        ),
      )

object InMemoryHotStuffArtifactSource:
  def create[F[_]: Sync](using
      clock: GossipClock[F]
  ): F[InMemoryHotStuffArtifactSource[F]] =
    Ref
      .of[F, Map[ChainTopic, Vector[AvailableGossipEvent[HotStuffGossipArtifact]]]](Map.empty)
      .map(new InMemoryHotStuffArtifactSource[F](clock, _))

final class InMemoryHotStuffArtifactSink[F[_]] private (
    validatorSet: ValidatorSet,
    ref: Ref[F, InMemoryHotStuffSinkSnapshot],
) extends GossipArtifactSink[F, HotStuffGossipArtifact]:
  override def applyEvent(
      event: GossipEvent[HotStuffGossipArtifact],
  ): F[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]] =
    ref.modify: snapshot =>
      event.payload match
        case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
          if snapshot.proposals.contains(proposal.proposalId) then
            snapshot.copy(duplicates = snapshot.duplicates :+ event) -> Right(
              ArtifactApplyResult(applied = false, duplicate = true)
            )
          else
            HotStuffValidator.validateProposal(proposal, validatorSet) match
              case Left(error) =>
                snapshot -> Left(
                  CanonicalRejection.ArtifactContractRejected(
                    reason = error.reason,
                    detail = error.detail,
                  )
                )
              case Right(_) =>
                val updatedProposals = snapshot.proposals.updated(proposal.proposalId, proposal)
                val updatedQcs =
                  snapshot.qcs.updated(proposal.justify.subject.proposalId, proposal.justify)
                // This in-memory test sink favors atomic behavior over
                // incremental cost. If validator sets grow, QC assembly should
                // move to an incremental cache instead of re-verifying on every
                // proposal/vote application.
                val assembled =
                  QuorumCertificateAssembler
                    .assemble(
                      QuorumCertificateSubject(
                        window = proposal.window,
                        proposalId = proposal.proposalId,
                        blockId = proposal.targetBlockId,
                      ),
                      snapshot.accumulator.votesFor(proposal.window, proposal.proposalId),
                      validatorSet,
                    )
                    .toOption
                val finalQcs =
                  assembled.fold(updatedQcs)(qc => updatedQcs.updated(proposal.proposalId, qc))
                snapshot.copy(proposals = updatedProposals, qcs = finalQcs) -> Right(
                  ArtifactApplyResult(applied = true, duplicate = false)
                )

        case HotStuffGossipArtifact.VoteArtifact(vote) =>
          if snapshot.votes.contains(vote.voteId) then
            snapshot.copy(duplicates = snapshot.duplicates :+ event) -> Right(
              ArtifactApplyResult(applied = false, duplicate = true)
            )
          else
            HotStuffValidator.validateVote(vote, validatorSet) match
              case Left(error) =>
                snapshot -> Left(
                  CanonicalRejection.ArtifactContractRejected(
                    reason = error.reason,
                    detail = error.detail,
                  )
                )
              case Right(_) =>
                snapshot.accumulator.record(vote) match
                  case Left(error) =>
                    snapshot -> Left(
                      CanonicalRejection.ArtifactContractRejected(
                        reason = error.reason,
                        detail = error.detail,
                      )
                    )
                  case Right((updatedAccumulator, _)) =>
                    val updatedVotes = snapshot.votes.updated(vote.voteId, vote)
                    val maybeProposal = snapshot.proposals.get(vote.targetProposalId)
                    val maybeQc =
                      maybeProposal.flatMap: proposal =>
                        QuorumCertificateAssembler
                          .assemble(
                            QuorumCertificateSubject(
                              window = proposal.window,
                              proposalId = proposal.proposalId,
                              blockId = proposal.targetBlockId,
                            ),
                            updatedAccumulator.votesFor(proposal.window, proposal.proposalId),
                            validatorSet,
                          )
                          .toOption
                    val updatedQcs =
                      maybeProposal.flatMap(_ => maybeQc).fold(snapshot.qcs): qc =>
                        snapshot.qcs.updated(qc.subject.proposalId, qc)
                    snapshot.copy(
                      votes = updatedVotes,
                      accumulator = updatedAccumulator,
                      qcs = updatedQcs,
                    ) -> Right(ArtifactApplyResult(applied = true, duplicate = false))

  def snapshot: F[InMemoryHotStuffSinkSnapshot] =
    ref.get

object InMemoryHotStuffArtifactSink:
  def create[F[_]: Sync](
      validatorSet: ValidatorSet,
  ): F[InMemoryHotStuffArtifactSink[F]] =
    Ref
      .of[F, InMemoryHotStuffSinkSnapshot](InMemoryHotStuffSinkSnapshot.empty)
      .map(new InMemoryHotStuffArtifactSink[F](validatorSet, _))

final case class HotStuffNodeRuntime[F[_]: Sync](
    localPeer: PeerIdentity,
    role: LocalNodeRole,
    holders: Vector[ValidatorKeyHolder],
    validatorSet: ValidatorSet,
    localKeys: Map[ValidatorId, KeyPair],
    source: InMemoryHotStuffArtifactSource[F],
    sink: InMemoryHotStuffArtifactSink[F],
    gossipPolicy: HotStuffGossipPolicy,
):
  def topicContracts: GossipTopicContractRegistry[HotStuffGossipArtifact] =
    HotStuffTopic.registry(gossipPolicy)

  def emitProposal(
      proposer: ValidatorId,
      block: Block,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    emitSigned(proposer): keyPair =>
      Proposal.sign(
        UnsignedProposal(
          window = window,
          proposer = proposer,
          targetBlockId = Block.computeId(block),
          block = block,
          justify = justify,
        ),
        keyPair,
      ) match
        case Left(error) =>
          Sync[F].raiseError[GossipEvent[HotStuffGossipArtifact]](
            new IllegalStateException(s"${error.reason}:${error.detail.getOrElse("")}")
          )
        case Right(proposal) =>
          source.append(HotStuffGossipArtifact.ProposalArtifact(proposal), ts)

  def emitVote(
      voter: ValidatorId,
      proposal: Proposal,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    emitSigned(voter): keyPair =>
      Vote.sign(
        UnsignedVote(
          window = proposal.window,
          voter = voter,
          targetProposalId = proposal.proposalId,
        ),
        keyPair,
      ) match
        case Left(error) =>
          Sync[F].raiseError[GossipEvent[HotStuffGossipArtifact]](
            new IllegalStateException(s"${error.reason}:${error.detail.getOrElse("")}")
          )
        case Right(vote) =>
          source.append(HotStuffGossipArtifact.VoteArtifact(vote), ts)

  private def emitSigned(
      validatorId: ValidatorId,
  )(
      f: KeyPair => F[GossipEvent[HotStuffGossipArtifact]]
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    HotStuffPolicy.canEmitLocally(role, localPeer, validatorId, holders) match
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(_) =>
        localKeys.get(validatorId) match
          case None =>
            HotStuffPolicyViolation(
              reason = "localValidatorKeyUnavailable",
              detail = Some(s"${validatorId.value}@${localPeer.value}"),
            ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
          case Some(keyPair) =>
            f(keyPair).map(_.asRight[HotStuffPolicyViolation])

object HotStuffNodeRuntime:
  def create[F[_]: Sync](
      localPeer: PeerIdentity,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      validatorSet: ValidatorSet,
      localKeys: Map[ValidatorId, KeyPair],
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy(),
  )(using clock: GossipClock[F]): F[Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]]] =
    HotStuffPolicy.ensureDistinctActiveKeyHolders(holders) match
      case Left(rejection) =>
        rejection.asLeft[HotStuffNodeRuntime[F]].pure[F]
      case Right(_) =>
        for
          source <- InMemoryHotStuffArtifactSource.create[F]
          sink <- InMemoryHotStuffArtifactSink.create[F](validatorSet)
        yield Right(
          HotStuffNodeRuntime(
            localPeer = localPeer,
            role = role,
            holders = holders,
            validatorSet = validatorSet,
            localKeys = localKeys,
            source = source,
            sink = sink,
            gossipPolicy = gossipPolicy,
          )
        )
