package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.time.Duration

import cats.Applicative
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.gossip.*

/** Retention and catch-up policy hints for an embedder-owned application gossip
  * topic.
  *
  * The source remains responsible for retaining artifacts and serving them by
  * stable ID. This policy records the contract Sigilaris should expose at the
  * HotStuff peer-gossip boundary.
  */
final case class ApplicationGossipTopicPolicy(
    catchUpRetentionHint: Option[Duration],
)

/** Companion for `ApplicationGossipTopicPolicy`. */
object ApplicationGossipTopicPolicy:
  /** Default policy when the embedder does not publish a retention hint. */
  val default: ApplicationGossipTopicPolicy =
    ApplicationGossipTopicPolicy(catchUpRetentionHint = None)

/** Registration for an embedder-owned application topic sharing the HotStuff
  * peer gossip session.
  */
final case class ApplicationGossipTopic[F[_], A](
    topic: GossipTopic,
    source: GossipArtifactSource[F, A],
    sink: GossipArtifactSink[F, A],
    contract: GossipTopicContract[A],
    policy: ApplicationGossipTopicPolicy,
)

/** Peer-gossip artifact envelope used when HotStuff consensus artifacts and
  * embedder application artifacts share the same session.
  */
enum HotStuffPeerArtifact[+A]:
  /** A HotStuff consensus artifact. */
  case Consensus(value: HotStuffGossipArtifact)

  /** An embedder-owned application artifact. */
  case Application(value: A)

/** Composite artifact helpers for HotStuff peer gossip. */
object HotStuffPeerArtifact:
  private val ConsensusNamespace: Byte   = 0x01.toByte
  private val ApplicationNamespace: Byte = 0x02.toByte
  private val ConsensusTopics: Vector[GossipTopic] =
    Vector(
      GossipTopic.consensusProposal,
      GossipTopic.consensusVote,
      GossipTopic.consensusTimeoutVote,
      GossipTopic.consensusNewView,
    )

  private def artifactRejected(
      reason: String,
      detail: String,
  ): CanonicalRejection.ArtifactContractRejected =
    CanonicalRejection.ArtifactContractRejected(
      reason = reason,
      detail = Some(detail),
    )

  private def unexpectedTopicPayload(
      topic: GossipTopic,
  ): CanonicalRejection.ArtifactContractRejected =
    artifactRejected("unexpectedTopicPayload", topic.value)

  private def decodeFailure(
      reason: String,
      detail: String,
  ): DecodeFailure =
    DecodeFailure(reason + ": " + detail)

  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  private def consensusArtifact[A](
      value: HotStuffGossipArtifact,
  ): HotStuffPeerArtifact[A] =
    HotStuffPeerArtifact.Consensus(value)

  private def ensurePayloadConsumed[A](
      namespace: String,
      result: DecodeResult[A],
  ): Either[DecodeFailure, DecodeResult[A]] =
    Either.cond(
      result.remainder.isEmpty,
      result,
      decodeFailure(
        "peerArtifactTrailingBytes",
        namespace + "=" + result.remainder.size.toString,
      ),
    )

  given [A: ByteEncoder]: ByteEncoder[HotStuffPeerArtifact[A]] =
    case HotStuffPeerArtifact.Consensus(value) =>
      ByteVector.fromByte(ConsensusNamespace) ++
        ByteEncoder[HotStuffGossipArtifact].encode(value)
    case HotStuffPeerArtifact.Application(value) =>
      ByteVector.fromByte(ApplicationNamespace) ++ ByteEncoder[A].encode(value)

  given [A: ByteDecoder]: ByteDecoder[HotStuffPeerArtifact[A]] = bytes =>
    if bytes.isEmpty then
      decodeFailure("truncatedPeerArtifactPayload", "missing namespace")
        .asLeft[DecodeResult[HotStuffPeerArtifact[A]]]
    else
      val namespace = bytes.head
      val payload   = bytes.tail
      namespace match
        case ConsensusNamespace =>
          ByteDecoder[HotStuffGossipArtifact]
            .decode(payload)
            .leftMap(error =>
              decodeFailure("truncatedPeerArtifactPayload", error.msg),
            )
            .flatMap(ensurePayloadConsumed("consensus", _))
            .map(result =>
              DecodeResult(
                consensusArtifact[A](result.value),
                ByteVector.empty,
              ),
            )
        case ApplicationNamespace =>
          ByteDecoder[A]
            .decode(payload)
            .leftMap(error =>
              decodeFailure("applicationPeerArtifactDecodeFailed", error.msg),
            )
            .flatMap(ensurePayloadConsumed("application", _))
            .map(result =>
              DecodeResult(
                HotStuffPeerArtifact.Application(result.value),
                ByteVector.empty,
              ),
            )
        case other =>
          decodeFailure(
            "unknownPeerArtifactNamespace",
            (other.toInt & 0xff).toString,
          ).asLeft[DecodeResult[HotStuffPeerArtifact[A]]]

  /** Builds the default HotStuff consensus-topic subscription for one chain. */
  def consensusSubscription(chainId: ChainId): SessionSubscription =
    SessionSubscription.unsafe(
      ConsensusTopics.map(topic => ChainTopic(chainId, topic))*
    )

  /** Builds a HotStuff consensus-plus-application subscription for one chain. */
  def subscription(
      chainId: ChainId,
      applicationTopics: Vector[ApplicationGossipTopic[?, ?]],
  ): Either[String, SessionSubscription] =
    SessionSubscription.fromSet:
      (ConsensusTopics.map(topic => ChainTopic(chainId, topic)) ++
        applicationTopics.map(topic => ChainTopic(chainId, topic.topic))).toSet

  /** Builds a composite source that delegates consensus topics to HotStuff and
    * application topics to their registrations.
    */
  def source[F[_]: Applicative, A: ByteEncoder](
      consensusSource: GossipArtifactSource[F, HotStuffGossipArtifact],
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
  ): GossipArtifactSource[F, HotStuffPeerArtifact[A]] =
    new CompositeSource(consensusSource, consensusContracts, applicationTopics)

  /** Builds a composite sink that routes by topic and payload namespace. */
  def sink[F[_]: Applicative, A](
      consensusSink: GossipArtifactSink[F, HotStuffGossipArtifact],
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
  ): GossipArtifactSink[F, HotStuffPeerArtifact[A]] =
    new CompositeSink(consensusSink, consensusContracts, applicationTopics)

  /** Builds a topic-contract registry for consensus plus application topics. */
  def topicContracts[A](
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[?, A]],
  ): GossipTopicContractRegistry[HotStuffPeerArtifact[A]] =
    new CompositeTopicContractRegistry(consensusContracts, applicationTopics)

  private final class CompositeSource[F[_]: Applicative, A: ByteEncoder](
      consensusSource: GossipArtifactSource[F, HotStuffGossipArtifact],
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
  ) extends GossipArtifactSource[F, HotStuffPeerArtifact[A]]:
    private val applicationsByTopic =
      applicationTopics.iterator.map(topic => topic.topic -> topic).toMap

    override def readAfter(
        chainId: ChainId,
        topic: GossipTopic,
        cursor: Option[CursorToken],
    ): F[Either[CanonicalRejection, Vector[
      AvailableGossipEvent[HotStuffPeerArtifact[A]],
    ]]] =
      consensusContracts.contractFor(topic) match
        case Right(_) =>
          consensusSource
            .readAfter(chainId, topic, cursor)
            .map(_.map(_.map(wrapConsensusEventWithoutSize)))
        case Left(consensusRejection) =>
          applicationsByTopic.get(topic) match
            case Some(applicationTopic) =>
              applicationTopic.source
                .readAfter(chainId, topic, cursor)
                .map(_.map(_.map(wrapApplicationEventWithoutSize)))
            case None =>
              consensusRejection
                .asLeft[Vector[
                  AvailableGossipEvent[HotStuffPeerArtifact[A]],
                ]]
                .pure[F]

    override def readByIds(
        chainId: ChainId,
        topic: GossipTopic,
        ids: Vector[StableArtifactId],
    ): F[Vector[AvailableGossipEvent[HotStuffPeerArtifact[A]]]] =
      consensusContracts.contractFor(topic) match
        case Right(_) =>
          consensusSource
            .readByIds(chainId, topic, ids)
            .map(_.map(wrapConsensusEventWithSize))
        case Left(_) =>
          applicationsByTopic.get(topic) match
            case Some(applicationTopic) =>
              applicationTopic.source
                .readByIds(chainId, topic, ids)
                .map(_.map(wrapApplicationEventWithSize))
            case None =>
              Vector.empty[AvailableGossipEvent[HotStuffPeerArtifact[A]]]
                .pure[F]

    private def wrapConsensusEventWithoutSize(
        event: AvailableGossipEvent[HotStuffGossipArtifact],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      val payload = consensusArtifact[A](event.event.payload)
      wrapConsensusEvent(event, payload, encodedSizeBytes = None)

    private def wrapConsensusEventWithSize(
        event: AvailableGossipEvent[HotStuffGossipArtifact],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      val payload = consensusArtifact[A](event.event.payload)
      wrapConsensusEvent(event, payload, encodedSizeBytes(payload))

    private def wrapConsensusEvent(
        event: AvailableGossipEvent[HotStuffGossipArtifact],
        payload: HotStuffPeerArtifact[A],
        encodedSizeBytes: Option[Long],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      event.copy(
        event = event.event.copy(payload = payload),
        encodedSizeBytes = encodedSizeBytes,
      )

    private def wrapApplicationEventWithoutSize(
        event: AvailableGossipEvent[A],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      val payload = HotStuffPeerArtifact.Application(event.event.payload)
      wrapApplicationEvent(event, payload, encodedSizeBytes = None)

    private def wrapApplicationEventWithSize(
        event: AvailableGossipEvent[A],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      val payload = HotStuffPeerArtifact.Application(event.event.payload)
      wrapApplicationEvent(event, payload, encodedSizeBytes(payload))

    private def wrapApplicationEvent(
        event: AvailableGossipEvent[A],
        payload: HotStuffPeerArtifact[A],
        encodedSizeBytes: Option[Long],
    ): AvailableGossipEvent[HotStuffPeerArtifact[A]] =
      event.copy(
        event = event.event.copy(payload = payload),
        encodedSizeBytes = encodedSizeBytes,
      )

    private def encodedSizeBytes(
        payload: HotStuffPeerArtifact[A],
    ): Option[Long] =
      Some(ByteEncoder[HotStuffPeerArtifact[A]].encode(payload).size)

  private final class CompositeSink[F[_]: Applicative, A](
      consensusSink: GossipArtifactSink[F, HotStuffGossipArtifact],
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[F, A]],
  ) extends GossipArtifactSink[F, HotStuffPeerArtifact[A]]:
    private val applicationsByTopic =
      applicationTopics.iterator.map(topic => topic.topic -> topic).toMap

    override def applyEvent(
        event: GossipEvent[HotStuffPeerArtifact[A]],
    ): F[Either[
      CanonicalRejection.ArtifactContractRejected,
      ArtifactApplyResult,
    ]] =
      consensusContracts.contractFor(event.topic) match
        case Right(_) =>
          event.payload match
            case HotStuffPeerArtifact.Consensus(value) =>
              consensusSink.applyEvent(event.copy(payload = value))
            case HotStuffPeerArtifact.Application(_) =>
              unexpectedTopicPayload(event.topic)
                .asLeft[ArtifactApplyResult]
                .pure[F]
        case Left(consensusRejection) =>
          applicationsByTopic.get(event.topic) match
            case Some(applicationTopic) =>
              event.payload match
                case HotStuffPeerArtifact.Application(value) =>
                  applicationTopic.sink.applyEvent(event.copy(payload = value))
                case HotStuffPeerArtifact.Consensus(_) =>
                  unexpectedTopicPayload(event.topic)
                    .asLeft[ArtifactApplyResult]
                    .pure[F]
            case None =>
              consensusRejection.asLeft[ArtifactApplyResult].pure[F]

  private final class CompositeTopicContractRegistry[A](
      consensusContracts: GossipTopicContractRegistry[HotStuffGossipArtifact],
      applicationTopics: Vector[ApplicationGossipTopic[?, A]],
  ) extends GossipTopicContractRegistry[HotStuffPeerArtifact[A]]:
    private val applicationsByTopic =
      applicationTopics.iterator.map(topic => topic.topic -> topic).toMap

    override def contractFor(
        topic: GossipTopic,
    ): Either[
      CanonicalRejection.ArtifactContractRejected,
      GossipTopicContract[HotStuffPeerArtifact[A]],
    ] =
      consensusContracts.contractFor(topic) match
        case Right(contract) =>
          wrapConsensusContract(contract)
            .asRight[CanonicalRejection.ArtifactContractRejected]
        case Left(consensusRejection) =>
          applicationsByTopic.get(topic) match
            case Some(applicationTopic) =>
              wrapApplicationContract(applicationTopic.contract)
                .asRight[CanonicalRejection.ArtifactContractRejected]
            case None =>
              consensusRejection
                .asLeft[GossipTopicContract[HotStuffPeerArtifact[A]]]

    private def wrapConsensusContract(
        contract: GossipTopicContract[HotStuffGossipArtifact],
    ): GossipTopicContract[HotStuffPeerArtifact[A]] =
      new GossipTopicContract[HotStuffPeerArtifact[A]]:
        override val topic: GossipTopic = contract.topic
        override val exactKnownSetLimit: Option[Int] =
          contract.exactKnownSetLimit
        override val requestByIdLimit: Option[Int] =
          contract.requestByIdLimit
        override val deliveryPriority: Int =
          contract.deliveryPriority

        override def producerQoS(
            default: GossipProducerQoS,
        ): GossipProducerQoS =
          contract.producerQoS(default)

        override def validateArtifact(
            event: GossipEvent[HotStuffPeerArtifact[A]],
        ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
          event.payload match
            case HotStuffPeerArtifact.Consensus(value) =>
              contract.validateArtifact(event.copy(payload = value))
            case HotStuffPeerArtifact.Application(_) =>
              unexpectedTopicPayload(topic).asLeft[Unit]

        override def exactKnownScopeOf(
            event: GossipEvent[HotStuffPeerArtifact[A]],
        ): Either[CanonicalRejection.ArtifactContractRejected, Option[
          ExactKnownSetScope,
        ]] =
          event.payload match
            case HotStuffPeerArtifact.Consensus(value) =>
              contract.exactKnownScopeOf(event.copy(payload = value))
            case HotStuffPeerArtifact.Application(_) =>
              unexpectedTopicPayload(topic).asLeft[Option[ExactKnownSetScope]]

    private def wrapApplicationContract(
        contract: GossipTopicContract[A],
    ): GossipTopicContract[HotStuffPeerArtifact[A]] =
      new GossipTopicContract[HotStuffPeerArtifact[A]]:
        override val topic: GossipTopic = contract.topic
        override val exactKnownSetLimit: Option[Int] =
          contract.exactKnownSetLimit
        override val requestByIdLimit: Option[Int] =
          contract.requestByIdLimit
        override val deliveryPriority: Int =
          contract.deliveryPriority

        override def producerQoS(
            default: GossipProducerQoS,
        ): GossipProducerQoS =
          contract.producerQoS(default)

        override def validateArtifact(
            event: GossipEvent[HotStuffPeerArtifact[A]],
        ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
          event.payload match
            case HotStuffPeerArtifact.Application(value) =>
              contract.validateArtifact(event.copy(payload = value))
            case HotStuffPeerArtifact.Consensus(_) =>
              unexpectedTopicPayload(topic).asLeft[Unit]

        override def exactKnownScopeOf(
            event: GossipEvent[HotStuffPeerArtifact[A]],
        ): Either[CanonicalRejection.ArtifactContractRejected, Option[
          ExactKnownSetScope,
        ]] =
          event.payload match
            case HotStuffPeerArtifact.Application(value) =>
              contract.exactKnownScopeOf(event.copy(payload = value))
            case HotStuffPeerArtifact.Consensus(_) =>
              unexpectedTopicPayload(topic).asLeft[Option[ExactKnownSetScope]]
