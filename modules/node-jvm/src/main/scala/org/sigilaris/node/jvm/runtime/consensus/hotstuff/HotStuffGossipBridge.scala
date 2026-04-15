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
  private final case class ArtifactMetadata(
      tag: Byte,
      topic: GossipTopic,
      stableId: StableArtifactId,
      encodedPayload: ByteVector,
  )

  private def metadataOf(
      artifact: HotStuffGossipArtifact,
  ): ArtifactMetadata =
    artifact match
      case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
        ArtifactMetadata(
          tag = 0x01.toByte,
          topic = GossipTopic.consensusProposal,
          stableId =
            StableArtifactId.unsafeFromBytes(proposal.proposalId.toUInt256.bytes),
          encodedPayload = proposal.toBytes,
        )
      case HotStuffGossipArtifact.VoteArtifact(vote) =>
        ArtifactMetadata(
          tag = 0x02.toByte,
          topic = GossipTopic.consensusVote,
          stableId =
            StableArtifactId.unsafeFromBytes(vote.voteId.toUInt256.bytes),
          encodedPayload = vote.toBytes,
        )
      case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
        ArtifactMetadata(
          tag = 0x03.toByte,
          topic = GossipTopic.consensusTimeoutVote,
          stableId = StableArtifactId.unsafeFromBytes:
            timeoutVote.timeoutVoteId.toUInt256.bytes,
          encodedPayload = timeoutVote.toBytes,
        )
      case HotStuffGossipArtifact.NewViewArtifact(newView) =>
        ArtifactMetadata(
          tag = 0x04.toByte,
          topic = GossipTopic.consensusNewView,
          stableId =
            StableArtifactId.unsafeFromBytes(newView.newViewId.toUInt256.bytes),
          encodedPayload = newView.toBytes,
        )

  /** Returns the gossip topic for the given artifact type. */
  def topicOf(
      artifact: HotStuffGossipArtifact,
  ): GossipTopic =
    metadataOf(artifact).topic

  /** Returns the stable artifact ID for the given artifact. */
  def stableIdOf(
      artifact: HotStuffGossipArtifact,
  ): StableArtifactId =
    metadataOf(artifact).stableId

  given ByteEncoder[HotStuffGossipArtifact] = artifact =>
    val metadata = metadataOf(artifact)
    ByteVector.fromByte(metadata.tag) ++ metadata.encodedPayload

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
  private final case class ArtifactContractView(
      stableId: StableArtifactId,
      chainId: ChainId,
      window: HotStuffWindow,
      invalidDetail: String,
  )

  private def viewFor(
      artifact: HotStuffGossipArtifact,
      chainId: ChainId,
      window: HotStuffWindow,
      invalidDetail: String,
  ): ArtifactContractView =
    ArtifactContractView(
      stableId = HotStuffGossipArtifact.stableIdOf(artifact),
      chainId = chainId,
      window = window,
      invalidDetail = invalidDetail,
    )

  private trait ArtifactContractSpec:
    def topic: GossipTopic
    def invalidReason: String
    def viewOf(
        artifact: HotStuffGossipArtifact,
    ): Option[ArtifactContractView]

  private object ProposalContractSpec extends ArtifactContractSpec:
    val topic: GossipTopic        = GossipTopic.consensusProposal
    val invalidReason: String     = "invalidConsensusProposalEvent"

    def viewOf(
        artifact: HotStuffGossipArtifact,
    ): Option[ArtifactContractView] =
      artifact match
        case HotStuffGossipArtifact.ProposalArtifact(proposal) =>
          Some(
            viewFor(
              artifact = artifact,
              chainId = proposal.window.chainId,
              window = proposal.window,
              invalidDetail = proposal.proposalId.toHexLower,
            ),
          )
        case _ =>
          None

  private object VoteContractSpec extends ArtifactContractSpec:
    val topic: GossipTopic        = GossipTopic.consensusVote
    val invalidReason: String     = "invalidConsensusVoteEvent"

    def viewOf(
        artifact: HotStuffGossipArtifact,
    ): Option[ArtifactContractView] =
      artifact match
        case HotStuffGossipArtifact.VoteArtifact(vote) =>
          Some(
            viewFor(
              artifact = artifact,
              chainId = vote.window.chainId,
              window = vote.window,
              invalidDetail = vote.voteId.toHexLower,
            ),
          )
        case _ =>
          None

  private object TimeoutVoteContractSpec extends ArtifactContractSpec:
    val topic: GossipTopic        = GossipTopic.consensusTimeoutVote
    val invalidReason: String     = "invalidConsensusTimeoutVoteEvent"

    def viewOf(
        artifact: HotStuffGossipArtifact,
    ): Option[ArtifactContractView] =
      artifact match
        case HotStuffGossipArtifact.TimeoutVoteArtifact(timeoutVote) =>
          Some(
            viewFor(
              artifact = artifact,
              chainId = timeoutVote.subject.window.chainId,
              window = timeoutVote.subject.window,
              invalidDetail = timeoutVote.timeoutVoteId.toHexLower,
            ),
          )
        case _ =>
          None

  private object NewViewContractSpec extends ArtifactContractSpec:
    val topic: GossipTopic        = GossipTopic.consensusNewView
    val invalidReason: String     = "invalidConsensusNewViewEvent"

    def viewOf(
        artifact: HotStuffGossipArtifact,
    ): Option[ArtifactContractView] =
      artifact match
        case HotStuffGossipArtifact.NewViewArtifact(newView) =>
          Some(
            viewFor(
              artifact = artifact,
              chainId = newView.window.chainId,
              window = newView.window,
              invalidDetail = newView.newViewId.toHexLower,
            ),
          )
        case _ =>
          None

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

  private def unexpectedTopicPayload(
      topic: GossipTopic,
  ): CanonicalRejection.ArtifactContractRejected =
    CanonicalRejection.ArtifactContractRejected(
      reason = "unexpectedTopicPayload",
      detail = Some(topic.value),
    )

  private def contractFor(
      policy: HotStuffTopicPolicy,
      spec: ArtifactContractSpec,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    new GossipTopicContract[HotStuffGossipArtifact]:
      override val topic: GossipTopic = spec.topic
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
        spec.viewOf(event.payload) match
          case Some(view) =>
            Either.cond(
              event.topic === topic &&
                event.chainId === view.chainId &&
                event.id === view.stableId,
              (),
              CanonicalRejection.ArtifactContractRejected(
                reason = spec.invalidReason,
                detail = Some(view.invalidDetail),
              ),
            )
          case None =>
            unexpectedTopicPayload(topic).asLeft[Unit]

      override def exactKnownScopeOf(
          event: GossipEvent[HotStuffGossipArtifact],
      ): Either[CanonicalRejection.ArtifactContractRejected, Option[
        ExactKnownSetScope,
      ]] =
        spec.viewOf(event.payload) match
          case Some(view) =>
            scopeForWindow(topic, view.chainId, view.window)
              .asRight[CanonicalRejection.ArtifactContractRejected]
          case None =>
            unexpectedTopicPayload(topic).asLeft[Option[ExactKnownSetScope]]

  /** Creates a gossip topic contract for proposal artifacts. */
  def proposalContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    contractFor(policy, ProposalContractSpec)

  /** Creates a gossip topic contract for vote artifacts. */
  def voteContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    contractFor(policy, VoteContractSpec)

  /** Creates a gossip topic contract for timeout vote artifacts. */
  def timeoutVoteContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    contractFor(policy, TimeoutVoteContractSpec)

  /** Creates a gossip topic contract for new-view artifacts. */
  def newViewContract(
      policy: HotStuffTopicPolicy,
  ): GossipTopicContract[HotStuffGossipArtifact] =
    contractFor(policy, NewViewContractSpec)

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
