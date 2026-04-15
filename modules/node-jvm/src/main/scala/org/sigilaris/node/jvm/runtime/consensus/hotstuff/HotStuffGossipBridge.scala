package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.node.gossip.*

enum HotStuffGossipArtifact:
  case ProposalArtifact(proposal: Proposal)
  case VoteArtifact(vote: Vote)
  case TimeoutVoteArtifact(timeoutVote: TimeoutVote)
  case NewViewArtifact(newView: NewView)

/** Companion for `HotStuffGossipArtifact`, providing topic/ID resolution and encoding. */
object HotStuffGossipArtifact:
  /** Returns the gossip topic for the given artifact type. */
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

  /** Returns the stable artifact ID for the given artifact. */
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

/** Per-topic gossip configuration for a HotStuff artifact type.
  *
  * @param exactKnownSetLimit max entries in the exact-known set
  * @param requestByIdLimit max IDs per request-by-ID batch
  * @param maxBatchItems max items per gossip batch
  * @param flushInterval how often to flush gossip batches
  * @param deliveryPriority priority for delivery ordering (lower = higher priority)
  */
final case class HotStuffTopicPolicy private (
    exactKnownSetLimit: Int,
    requestByIdLimit: Int,
    maxBatchItems: Int,
    flushInterval: Duration,
    deliveryPriority: Int,
):

  /** Converts this policy to a gossip producer QoS configuration. */
  def producerQoS: GossipProducerQoS =
    new GossipProducerQoS:
      override def maxBatchItems: Int = HotStuffTopicPolicy.this.maxBatchItems
      override def flushInterval: Duration =
        HotStuffTopicPolicy.this.flushInterval

/** Companion for `HotStuffTopicPolicy`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object HotStuffTopicPolicy:
  def apply(
      exactKnownSetLimit: Int,
      requestByIdLimit: Int,
      maxBatchItems: Int,
      flushInterval: Duration,
      deliveryPriority: Int,
  ): Either[String, HotStuffTopicPolicy] =
    Either
      .cond(
        exactKnownSetLimit > 0,
        (),
        "exactKnownSetLimit must be positive",
      )
      .flatMap(_ =>
        Either.cond(
          requestByIdLimit > 0,
          (),
          "requestByIdLimit must be positive",
        ),
      )
      .flatMap: _ =>
        Either.cond(
          maxBatchItems > 0,
          new HotStuffTopicPolicy(
            exactKnownSetLimit = exactKnownSetLimit,
            requestByIdLimit = requestByIdLimit,
            maxBatchItems = maxBatchItems,
            flushInterval = flushInterval,
            deliveryPriority = deliveryPriority,
          ),
          "maxBatchItems must be positive",
        )

  def unsafe(
      exactKnownSetLimit: Int,
      requestByIdLimit: Int,
      maxBatchItems: Int,
      flushInterval: Duration,
      deliveryPriority: Int,
  ): HotStuffTopicPolicy =
    apply(
      exactKnownSetLimit = exactKnownSetLimit,
      requestByIdLimit = requestByIdLimit,
      maxBatchItems = maxBatchItems,
      flushInterval = flushInterval,
      deliveryPriority = deliveryPriority,
    ) match
      case Right(policy) => policy
      case Left(error)   => throw new IllegalArgumentException(error)

/** Aggregated gossip policies for all HotStuff artifact types. */
final case class HotStuffGossipPolicy(
    proposal: HotStuffTopicPolicy,
    vote: HotStuffTopicPolicy,
    timeoutVote: HotStuffTopicPolicy,
    newView: HotStuffTopicPolicy,
)

/** Companion for `HotStuffGossipPolicy`. */
object HotStuffGossipPolicy:
  private val defaultProposalPolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy.unsafe(
      exactKnownSetLimit = 256,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxProposalRequestIds,
      maxBatchItems = 32,
      flushInterval = Duration.ZERO,
      deliveryPriority = 2,
    )

  private val defaultVotePolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy.unsafe(
      exactKnownSetLimit = 2048,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 256,
      flushInterval = Duration.ZERO,
      deliveryPriority = 1,
    )

  private val defaultTimeoutVotePolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy.unsafe(
      exactKnownSetLimit = 2048,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 128,
      flushInterval = Duration.ZERO,
      deliveryPriority = 3,
    )

  private val defaultNewViewPolicy: HotStuffTopicPolicy =
    HotStuffTopicPolicy.unsafe(
      exactKnownSetLimit = 1024,
      requestByIdLimit = HotStuffPolicy.requestPolicy.maxVoteRequestIds,
      maxBatchItems = 64,
      flushInterval = Duration.ZERO,
      deliveryPriority = 4,
    )

  /** The default gossip policy. */
  val default: HotStuffGossipPolicy =
    HotStuffGossipPolicy(
      proposal = defaultProposalPolicy,
      vote = defaultVotePolicy,
      timeoutVote = defaultTimeoutVotePolicy,
      newView = defaultNewViewPolicy,
    )

/** Constructs gossip topic window keys from HotStuff consensus windows. */
object HotStuffWindowKey:
  private final case class WindowInput(
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      validatorSetHash: ValidatorSetHash,
  ) derives ByteEncoder

  /** Creates a topic window key from a HotStuff consensus window. */
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

/** Creates gossip topic contracts for each HotStuff artifact type. */
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

  /** Creates a gossip topic contract for proposal artifacts. */
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

  /** Creates a gossip topic contract for vote artifacts. */
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

  /** Creates a gossip topic contract for timeout vote artifacts. */
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

  /** Creates a gossip topic contract for new-view artifacts. */
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

  /** Creates a complete gossip topic contract registry for all HotStuff artifact types. */
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
