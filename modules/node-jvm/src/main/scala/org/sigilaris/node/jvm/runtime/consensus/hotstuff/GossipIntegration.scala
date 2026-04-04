package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.ByteBuffer
import java.time.{Duration, Instant}

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{Hash, KeyPair}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.block.{BlockBody, BlockHeader, BlockHeight, BlockQuery, BlockRecord, BlockStore, BlockTimestamp, BlockView, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.*

enum HotStuffGossipArtifact:
  case ProposalArtifact(proposal: Proposal)
  case VoteArtifact(vote: Vote)

object HotStuffGossipArtifact:
  def topicOf(
      artifact: HotStuffGossipArtifact,
  ): GossipTopic =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(_) =>
        GossipTopic.consensusProposal
      case HotStuffGossipArtifact.VoteArtifact(_) => GossipTopic.consensusVote

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

  val default: HotStuffGossipPolicy =
    HotStuffGossipPolicy(
      proposal = defaultProposalPolicy,
      vote = defaultVotePolicy,
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
            Some:
              ExactKnownSetScope(
                chainId = proposal.window.chainId,
                topic = topic,
                windowKey = HotStuffWindowKey.fromWindow(proposal.window),
              )
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
            Some:
              ExactKnownSetScope(
                chainId = vote.window.chainId,
                topic = topic,
                windowKey = HotStuffWindowKey.fromWindow(vote.window),
              )
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
    )

final case class InMemoryHotStuffSourceSnapshot(
    eventsByTopic: Map[ChainTopic, Vector[GossipEvent[HotStuffGossipArtifact]]],
)

final case class InMemoryHotStuffSinkSnapshot(
    proposals: Map[ProposalId, Proposal],
    votes: Map[VoteId, Vote],
    accumulator: VoteAccumulator,
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
                  ss"sequence=${sequence.toString} max=${maxSequence.toString}"
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
                    ArtifactApplyResult(applied = false, duplicate = true) -> Option
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

  private def finalizeApply(
      stored: Either[
        CanonicalRejection.ArtifactContractRejected,
        (ArtifactApplyResult, Option[RelayEnvelope]),
      ],
  ): F[Either[CanonicalRejection.ArtifactContractRejected, ArtifactApplyResult]] =
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

  def createWithProposalValidation[F[_]: Sync, TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
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
):
  def bootstrapTrustRoot: BootstrapTrustRoot =
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

  def emitProposal(
      proposer: ValidatorId,
      block: BlockHeader,
      txSet: ProposalTxSet,
      window: HotStuffWindow,
      justify: QuorumCertificate,
      ts: Instant,
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    emitSigned(proposer): keyPair =>
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
          Sync[F].raiseError[GossipEvent[HotStuffGossipArtifact]]:
            new IllegalStateException(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            )
        case Right(proposal) =>
          services.publisher.append(
            HotStuffGossipArtifact.ProposalArtifact(proposal),
            ts,
          )

  def emitProposalFromCandidates[TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
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
        HotStuffRuntimeRejection.Validation(failure)
          .asLeft[HotStuffProposalEmission[TxRef, ResultRef, Event]]
          .pure[F]
      case Right(body) =>
        BlockBody.computeBodyRoot(body)
          .leftMap(HotStuffRuntimeScheduling.fromBlockValidationFailure) match
          case Left(failure) =>
            HotStuffRuntimeRejection.Validation(failure)
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
                  HotStuffRuntimeRejection.Validation(failure)
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
                        )
                  )

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
          Sync[F].raiseError[GossipEvent[HotStuffGossipArtifact]]:
            new IllegalStateException(
              ss"${error.reason}:${error.detail.getOrElse("")}",
            )
        case Right(vote) =>
          services.publisher.append(
            HotStuffGossipArtifact.VoteArtifact(vote),
            ts,
          )

  def emitVoteForProposalView[TxRef: ByteEncoder: Hash, ResultRef: ByteEncoder, Event: ByteEncoder](
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
          HotStuffRuntimeRejection.Validation(failure)
            .asLeft[GossipEvent[HotStuffGossipArtifact]]
            .pure[F]
        case Right(_) =>
          emitVote(voter, proposal, ts)
            .map(_.leftMap(HotStuffRuntimeRejection.Policy.apply))

  private def emitSigned(
      validatorId: ValidatorId,
  )(
      f: KeyPair => F[GossipEvent[HotStuffGossipArtifact]],
  ): F[Either[HotStuffPolicyViolation, GossipEvent[HotStuffGossipArtifact]]] =
    HotStuffPolicy.canEmitLocally(role, localPeer, validatorId, holders) match
      case Left(rejection) =>
        rejection.asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
      case Right(_) =>
        localKeys.get(validatorId) match
          case None =>
            HotStuffPolicyViolation(
              reason = "localValidatorKeyUnavailable",
              detail = Some(ss"${validatorId.value}@${localPeer.value}"),
            ).asLeft[GossipEvent[HotStuffGossipArtifact]].pure[F]
          case Some(keyPair) =>
            f(keyPair).map(_.asRight[HotStuffPolicyViolation])

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
  ): HotStuffNodeRuntime[F] =
    HotStuffNodeRuntime(
      bootstrapInput = bootstrapInput,
      services = services,
      diagnostics = diagnostics,
    )

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def fromServices[F[_]: Sync](
      bootstrapInput: HotStuffRuntimeBootstrapInput,
      services: HotStuffRuntimeServices[F],
      diagnostics: Option[HotStuffInMemoryRuntimeDiagnostics[F]] = None,
  ): Either[HotStuffPolicyViolation, HotStuffNodeRuntime[F]] =
    validateBootstrapInput(bootstrapInput)
      .map(fromValidatedServices(_, services, diagnostics))

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
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
          bootstrap = HotStuffBootstrapServicesRuntime.inMemory[F](validatorSet, sink),
        )
      services -> diagnostics

  @SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
  def create[F[_]: Sync](
      localPeer: PeerIdentity,
      role: LocalNodeRole,
      holders: Vector[ValidatorKeyHolder],
      validatorSet: ValidatorSet,
      localKeys: Map[ValidatorId, KeyPair],
      gossipPolicy: HotStuffGossipPolicy = HotStuffGossipPolicy.default,
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
        ).map: (services, diagnostics) =>
          fromValidatedServices(validatedInput, services, Some(diagnostics))
            .asRight[HotStuffPolicyViolation]
