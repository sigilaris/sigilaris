package org.sigilaris.node.jvm.runtime.gossip

import java.time.{Duration, Instant}
import java.util.{Base64, UUID}

import scala.util.Try

import cats.Eq
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*

sealed trait CanonicalRejection:
  def rejectionClass: String
  def reason: String
  def detail: Option[String]

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object CanonicalRejection:
  final case class HandshakeRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "handshakeRejected"

  final case class StaleCursor(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "staleCursor"

  final case class ControlBatchRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "controlBatchRejected"

  final case class ArtifactContractRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "artifactContractRejected"

  final case class BackfillUnavailable(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "backfillUnavailable"

object GossipFieldValidation:
  private val LowerAsciiToken = "^[a-z0-9][a-z0-9._-]*$".r
  private val LowerAsciiTopic = "^[a-z0-9][a-z0-9._-]*$".r

  def validateLowerAsciiToken(
      kind: String,
      value: String,
  ): Either[String, String] =
    Either.cond(
      LowerAsciiToken.matches(value),
      value,
      ss"${kind} must be a non-empty lowercase ASCII token",
    )

  def validateTopic(value: String): Either[String, String] =
    Either.cond(
      LowerAsciiTopic.matches(value),
      value,
      "topic must be a non-empty lowercase ASCII token",
    )

  def validateUuidV4(kind: String, value: String): Either[String, UUID] =
    Try(UUID.fromString(value)).toEither.left
      .map(_ => ss"${kind} must be a UUIDv4 string")
      .flatMap: uuid =>
        Either.cond(
          uuid.version() === 4 && uuid.toString === value,
          uuid,
          ss"${kind} must be a lowercase canonical UUIDv4 string",
        )

opaque type DirectionalSessionId = String

object DirectionalSessionId:
  def random(): DirectionalSessionId =
    UUID.randomUUID().toString

  def parse(value: String): Either[String, DirectionalSessionId] =
    GossipFieldValidation
      .validateUuidV4("directionalSessionId", value)
      .map(_ => value)

  extension (id: DirectionalSessionId) def value: String = id

  given Eq[DirectionalSessionId] = Eq.by(_.value)

opaque type PeerCorrelationId = String

object PeerCorrelationId:
  def random(): PeerCorrelationId =
    UUID.randomUUID().toString

  def parse(value: String): Either[String, PeerCorrelationId] =
    GossipFieldValidation
      .validateUuidV4("peerCorrelationId", value)
      .map(_ => value)

  def lexicographicCompare(
      left: PeerCorrelationId,
      right: PeerCorrelationId,
  ): Int =
    left.value.compareTo(right.value)

  extension (id: PeerCorrelationId) def value: String = id

  given Eq[PeerCorrelationId] = Eq.by(_.value)

opaque type PeerIdentity = String

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object PeerIdentity:
  def parse(value: String): Either[String, PeerIdentity] =
    GossipFieldValidation
      .validateLowerAsciiToken("peerIdentity", value)
      .map(_ => value)

  def unsafe(value: String): PeerIdentity =
    parse(value) match
      case Right(peer) => peer
      case Left(error) => throw new IllegalArgumentException(error)

  extension (identity: PeerIdentity) def value: String = identity

  given Eq[PeerIdentity] = Eq.by(_.value)

opaque type ChainId = String

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ChainId:
  def parse(value: String): Either[String, ChainId] =
    GossipFieldValidation
      .validateLowerAsciiToken("chainId", value)
      .map(_ => value)

  def unsafe(value: String): ChainId =
    parse(value) match
      case Right(chainId) => chainId
      case Left(error)    => throw new IllegalArgumentException(error)

  extension (chainId: ChainId) def value: String = chainId

  given Eq[ChainId] = Eq.by(_.value)

opaque type GossipTopic = String

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object GossipTopic:
  val tx: GossipTopic                   = "tx"
  val consensusProposal: GossipTopic    = "consensus.proposal"
  val consensusVote: GossipTopic        = "consensus.vote"
  val consensusTimeoutVote: GossipTopic = "consensus.timeout-vote"
  val consensusNewView: GossipTopic     = "consensus.new-view"

  def parse(value: String): Either[String, GossipTopic] =
    GossipFieldValidation.validateTopic(value).map(_ => value)

  def unsafe(value: String): GossipTopic =
    parse(value) match
      case Right(topic) => topic
      case Left(error)  => throw new IllegalArgumentException(error)

  extension (topic: GossipTopic) def value: String = topic

  given Eq[GossipTopic] = Eq.by(_.value)

final case class ChainTopic(
    chainId: ChainId,
    topic: GossipTopic,
)

object ChainTopic:
  given Eq[ChainTopic] = Eq.fromUniversalEquals

@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
final case class SessionSubscription private (
    values: Set[ChainTopic],
):
  def contains(chainTopic: ChainTopic): Boolean =
    values.contains(chainTopic)

  def contains(chainId: ChainId, topic: GossipTopic): Boolean =
    contains(ChainTopic(chainId, topic))

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object SessionSubscription:
  given Eq[SessionSubscription] = Eq.fromUniversalEquals

  def of(values: ChainTopic*): Either[String, SessionSubscription] =
    fromSet(values.toSet)

  def fromSet(values: Set[ChainTopic]): Either[String, SessionSubscription] =
    Either.cond(
      values.nonEmpty,
      SessionSubscription(values),
      "subscription must not be empty",
    )

  def unsafe(values: ChainTopic*): SessionSubscription =
    of(values*) match
      case Right(subscription) => subscription
      case Left(error)         => throw new IllegalArgumentException(error)

opaque type StableArtifactId = ByteVector

@SuppressWarnings(
  Array("org.wartremover.warts.Throw", "org.wartremover.warts.Any"),
)
object StableArtifactId:
  def fromBytes(bytes: ByteVector): Either[String, StableArtifactId] =
    Either.cond(bytes.nonEmpty, bytes, "stable artifact id must not be empty")

  def fromHex(value: String): Either[String, StableArtifactId] =
    ByteVector
      .fromHexDescriptive(value)
      .left
      .map(error => s"invalid stable artifact id hex: $error")
      .flatMap(fromBytes)

  def unsafeFromHex(value: String): StableArtifactId =
    fromHex(value) match
      case Right(id)   => id
      case Left(error) => throw new IllegalArgumentException(error)

  def unsafeFromBytes(bytes: ByteVector): StableArtifactId =
    fromBytes(bytes) match
      case Right(id)   => id
      case Left(error) => throw new IllegalArgumentException(error)

  extension (id: StableArtifactId)
    def bytes: ByteVector  = id
    def toHexLower: String = id.toHex

  given Eq[StableArtifactId] = Eq.by(_.toHexLower)

opaque type TopicWindowKey = ByteVector

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object TopicWindowKey:
  def fromBytes(
      bytes: ByteVector,
  ): Either[String, TopicWindowKey] =
    Either.cond(bytes.nonEmpty, bytes, "topic window key must not be empty")

  def fromHex(
      value: String,
  ): Either[String, TopicWindowKey] =
    ByteVector
      .fromHexDescriptive(value)
      .left
      .map(error => ss"invalid topic window key hex: ${error}")
      .flatMap(fromBytes)

  def unsafeFromHex(
      value: String,
  ): TopicWindowKey =
    fromHex(value) match
      case Right(windowKey) => windowKey
      case Left(error)      => throw new IllegalArgumentException(error)

  def unsafeFromBytes(
      bytes: ByteVector,
  ): TopicWindowKey =
    fromBytes(bytes) match
      case Right(windowKey) => windowKey
      case Left(error)      => throw new IllegalArgumentException(error)

  extension (windowKey: TopicWindowKey)
    def bytes: ByteVector  = windowKey
    def toHexLower: String = windowKey.toHex

  given Eq[TopicWindowKey] = Eq.by(_.toHexLower)

final case class ExactKnownSetScope(
    chainId: ChainId,
    topic: GossipTopic,
    windowKey: TopicWindowKey,
)

object ExactKnownSetScope:
  given Eq[ExactKnownSetScope] = Eq.fromUniversalEquals

opaque type CursorToken = ByteVector

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.Throw",
  ),
)
object CursorToken:
  val CurrentVersion: Int = 1

  def issue(payload: ByteVector, version: Int = CurrentVersion): CursorToken =
    if version < 0 || version > 0xff then
      throw new IllegalArgumentException(
        "cursor token version must fit in one unsigned byte",
      )
    ByteVector.fromByte(version.toByte) ++ payload

  def fromBytes(bytes: ByteVector): Either[String, CursorToken] =
    Either.cond(bytes.nonEmpty, bytes, "cursor token must not be empty")

  def decodeBase64Url(value: String): Either[String, CursorToken] =
    Try(ByteVector.view(Base64.getUrlDecoder.decode(value))).toEither.left
      .map(_ => "cursor token must be base64url without padding")
      .flatMap(fromBytes)

  extension (token: CursorToken)
    def bytes: ByteVector   = token
    def version: Int        = token.head.toInt & 0xff
    def payload: ByteVector = token.drop(1)
    def toBase64Url: String =
      Base64.getUrlEncoder.withoutPadding().encodeToString(token.toArray)

    def validateVersion(
        expected: Int = CurrentVersion,
    ): Either[CanonicalRejection.StaleCursor, CursorToken] =
      Either.cond(
        version === expected,
        token,
        CanonicalRejection.StaleCursor(
          reason = "cursorTokenVersionMismatch",
          detail =
            Some(
              ss"expected=${expected.toString} actual=${version.toString}",
            ),
        ),
      )

  given Eq[CursorToken] = Eq.by(_.toBase64Url)

final case class CompositeCursor(
    values: Map[ChainTopic, CursorToken],
):
  def tokenFor(chainTopic: ChainTopic): Option[CursorToken] =
    values.get(chainTopic)

  def tokenForChainAndTopic(
      chainId: ChainId,
      topic: GossipTopic,
  ): Option[CursorToken] =
    tokenFor(ChainTopic(chainId, topic))

  def isOriginReplay(chainTopic: ChainTopic): Boolean =
    !values.contains(chainTopic)

  def isEmpty: Boolean =
    values.isEmpty

object CompositeCursor:
  val empty: CompositeCursor = CompositeCursor(Map.empty)

final case class GossipEvent[A](
    chainId: ChainId,
    topic: GossipTopic,
    id: StableArtifactId,
    cursor: CursorToken,
    ts: Instant,
    payload: A,
)

sealed trait GossipFilter

object GossipFilter:
  final case class TxBloomFilter(
      bitset: ByteVector,
      numHashes: Int,
      hashFamilyId: String,
  ) extends GossipFilter

enum ControlOpKind(val wireName: String):
  case SetFilter        extends ControlOpKind("setFilter")
  case SetKnownTx       extends ControlOpKind("setKnown.tx")
  case SetKnownExact    extends ControlOpKind("setKnown.exact")
  case SetCursor        extends ControlOpKind("setCursor")
  case Nack             extends ControlOpKind("nack")
  case RequestByIdTx    extends ControlOpKind("requestById.tx")
  case RequestByIdExact extends ControlOpKind("requestById.exact")
  case Config           extends ControlOpKind("config")

object ControlOpKind:
  def parse(
      value: String,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlOpKind] =
    ControlOpKind.values
      .find(_.wireName === value)
      .toRight(
        CanonicalRejection.ControlBatchRejected(
          reason = "unknownControlOpKind",
          detail = Some(value),
        ),
      )

enum SessionConfigKey(val wireName: String):
  case TxMaxBatchItems   extends SessionConfigKey("tx.maxBatchItems")
  case TxFlushIntervalMs extends SessionConfigKey("tx.flushIntervalMs")

object SessionConfigKey:
  def parse(
      value: String,
  ): Either[CanonicalRejection.ControlBatchRejected, SessionConfigKey] =
    SessionConfigKey.values
      .find(_.wireName === value)
      .toRight(
        CanonicalRejection.ControlBatchRejected(
          reason = "unsupportedConfigKey",
          detail = Some(value),
        ),
      )

enum ControlOp:
  case SetFilter(chainId: ChainId, topic: GossipTopic, filter: GossipFilter)
  case SetKnownTx(chainId: ChainId, ids: Vector[StableArtifactId])
  case SetKnownExact(scope: ExactKnownSetScope, ids: Vector[StableArtifactId])
  case SetCursor(cursor: CompositeCursor)
  case Nack(chainId: ChainId, topic: GossipTopic, cursor: Option[CursorToken])
  case RequestByIdTx(chainId: ChainId, ids: Vector[StableArtifactId])
  case RequestByIdExact(
      scope: ExactKnownSetScope,
      ids: Vector[StableArtifactId],
  )
  case Config(values: Map[SessionConfigKey, Long])

opaque type ControlIdempotencyKey = String

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ControlIdempotencyKey:
  def parse(
      value: String,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlIdempotencyKey] =
    GossipFieldValidation
      .validateUuidV4("control idempotency key", value)
      .left
      .map: error =>
        CanonicalRejection.ControlBatchRejected(
          reason = "invalidIdempotencyKey",
          detail = Some(error),
        )
      .map(_ => value)

  def unsafe(value: String): ControlIdempotencyKey =
    parse(value) match
      case Right(key)  => key
      case Left(error) => throw new IllegalArgumentException(error.reason)

  extension (key: ControlIdempotencyKey) def value: String = key

final case class ControlBatch(
    idempotencyKey: ControlIdempotencyKey,
    ops: Vector[ControlOp],
)

object ControlBatch:
  def create(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): Either[CanonicalRejection.ControlBatchRejected, ControlBatch] =
    // Empty batches are an intentional no-op success path in the baseline contract.
    ControlIdempotencyKey.parse(idempotencyKey).map(ControlBatch(_, ops))

final case class NegotiatedSessionParameters(
    heartbeatInterval: Duration,
    livenessTimeout: Duration,
    maxControlRetryInterval: Duration,
)

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class HandshakePolicy(
    openingHandshakeTimeout: Duration = Duration.ofSeconds(30),
    defaultHeartbeatInterval: Duration = Duration.ofSeconds(10),
    defaultLivenessTimeout: Duration = Duration.ofSeconds(30),
    defaultMaxControlRetryInterval: Duration = Duration.ofSeconds(30),
    minHeartbeatInterval: Duration = Duration.ofSeconds(1),
    maxHeartbeatInterval: Duration = Duration.ofSeconds(60),
    minMaxControlRetryInterval: Duration = Duration.ofSeconds(1),
    maxMaxControlRetryInterval: Duration = Duration.ofMinutes(5),
):
  def validateControlRetryHorizon(
      retryHorizon: Duration,
      negotiated: NegotiatedSessionParameters,
  ): Either[CanonicalRejection.ControlBatchRejected, Duration] =
    val minimum = negotiated.maxControlRetryInterval.multipliedBy(2)
    val maximum = negotiated.maxControlRetryInterval.multipliedBy(10)
    Either.cond(
      !retryHorizon
        .minus(minimum)
        .isNegative && !maximum.minus(retryHorizon).isNegative,
      retryHorizon,
      CanonicalRejection.ControlBatchRejected(
        reason = "invalidControlRetryHorizon",
        detail = Some(
          ss"minimum=${minimum.toString} maximum=${maximum.toString} actual=${retryHorizon.toString}"
        ),
      ),
    )

object HandshakePolicy:
  val default: HandshakePolicy = HandshakePolicy()

final case class SessionOpenProposal(
    sessionId: DirectionalSessionId,
    peerCorrelationId: PeerCorrelationId,
    initiator: PeerIdentity,
    acceptor: PeerIdentity,
    subscriptions: SessionSubscription,
    heartbeatInterval: Option[Duration],
    livenessTimeout: Option[Duration],
    maxControlRetryInterval: Option[Duration],
)

final case class SessionOpenAck(
    sessionId: DirectionalSessionId,
    peerCorrelationId: PeerCorrelationId,
    initiator: PeerIdentity,
    acceptor: PeerIdentity,
    subscriptions: SessionSubscription,
    negotiated: NegotiatedSessionParameters,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments",
  ),
)
object SessionNegotiation:
  private def ensureAtLeast(
      name: String,
      actual: Duration,
      minimum: Duration,
  ): Either[CanonicalRejection.HandshakeRejected, Duration] =
    Either.cond(
      !actual.minus(minimum).isNegative,
      actual,
      CanonicalRejection.HandshakeRejected(
        reason = "invalidNegotiationValue",
        detail = Some(
          ss"${name} must be >= ${minimum.toString} but was ${actual.toString}",
        ),
      ),
    )

  private def ensureAtMost(
      name: String,
      actual: Duration,
      maximum: Duration,
  ): Either[CanonicalRejection.HandshakeRejected, Duration] =
    Either.cond(
      !maximum.minus(actual).isNegative,
      actual,
      CanonicalRejection.HandshakeRejected(
        reason = "invalidNegotiationValue",
        detail = Some(
          ss"${name} must be <= ${maximum.toString} but was ${actual.toString}",
        ),
      ),
    )

  def resolveProposal(
      proposal: SessionOpenProposal,
      policy: HandshakePolicy = HandshakePolicy.default,
  ): Either[CanonicalRejection.HandshakeRejected, NegotiatedSessionParameters] =
    for
      heartbeat <- ensureAtLeast(
        "heartbeatInterval",
        proposal.heartbeatInterval.getOrElse(policy.defaultHeartbeatInterval),
        policy.minHeartbeatInterval,
      ).flatMap(
        ensureAtMost("heartbeatInterval", _, policy.maxHeartbeatInterval),
      )
      liveness <- ensureAtLeast(
        "livenessTimeout",
        proposal.livenessTimeout.getOrElse(policy.defaultLivenessTimeout),
        heartbeat.multipliedBy(3),
      )
      retry <- ensureAtLeast(
        "maxControlRetryInterval",
        proposal.maxControlRetryInterval.getOrElse(
          policy.defaultMaxControlRetryInterval,
        ),
        policy.minMaxControlRetryInterval,
      ).flatMap(
        ensureAtMost(
          "maxControlRetryInterval",
          _,
          policy.maxMaxControlRetryInterval,
        ),
      )
    yield NegotiatedSessionParameters(heartbeat, liveness, retry)

  def acknowledge(
      proposal: SessionOpenProposal,
      heartbeatInterval: Duration,
      livenessTimeout: Duration,
      maxControlRetryInterval: Duration,
      policy: HandshakePolicy = HandshakePolicy.default,
  ): Either[CanonicalRejection.HandshakeRejected, SessionOpenAck] =
    resolveProposal(proposal, policy).flatMap: resolved =>
      for
        _ <- ensureAtLeast(
          "heartbeatInterval",
          heartbeatInterval,
          policy.minHeartbeatInterval,
        )
        _ <- ensureAtMost(
          "heartbeatInterval",
          heartbeatInterval,
          resolved.heartbeatInterval,
        )
        // Liveness timeout has no absolute ceiling in the baseline contract.
        // The only enforced invariants are >= 3 * heartbeat and >= proposal floor.
        _ <- ensureAtLeast(
          "livenessTimeout",
          livenessTimeout,
          heartbeatInterval.multipliedBy(3),
        )
        _ <- ensureAtLeast(
          "livenessTimeout",
          livenessTimeout,
          resolved.livenessTimeout,
        )
        _ <- ensureAtLeast(
          "maxControlRetryInterval",
          maxControlRetryInterval,
          policy.minMaxControlRetryInterval,
        )
        _ <- ensureAtMost(
          "maxControlRetryInterval",
          maxControlRetryInterval,
          resolved.maxControlRetryInterval,
        )
      yield SessionOpenAck(
        sessionId = proposal.sessionId,
        peerCorrelationId = proposal.peerCorrelationId,
        initiator = proposal.initiator,
        acceptor = proposal.acceptor,
        subscriptions = proposal.subscriptions,
        negotiated = NegotiatedSessionParameters(
          heartbeatInterval = heartbeatInterval,
          livenessTimeout = livenessTimeout,
          maxControlRetryInterval = maxControlRetryInterval,
        ),
      )

  def validateAck(
      proposal: SessionOpenProposal,
      ack: SessionOpenAck,
      policy: HandshakePolicy = HandshakePolicy.default,
  ): Either[CanonicalRejection.HandshakeRejected, NegotiatedSessionParameters] =
    resolveProposal(proposal, policy).flatMap: resolved =>
      Either
        .cond(
          ack.sessionId === proposal.sessionId,
          (),
          CanonicalRejection.HandshakeRejected(
            reason = "handshakeAckSessionMismatch",
            detail = Some("session id mismatch"),
          ),
        )
        .flatMap: _ =>
          Either.cond(
            ack.peerCorrelationId === proposal.peerCorrelationId,
            (),
            CanonicalRejection.HandshakeRejected(
              reason = "handshakeAckPeerCorrelationMismatch",
              detail = Some("peer correlation id mismatch"),
            ),
          )
        .flatMap: _ =>
          Either.cond(
            ack.initiator === proposal.initiator,
            (),
            CanonicalRejection.HandshakeRejected(
              reason = "handshakeAckInitiatorMismatch",
              detail = Some("initiator mismatch"),
            ),
          )
        .flatMap: _ =>
          Either.cond(
            ack.acceptor === proposal.acceptor,
            (),
            CanonicalRejection.HandshakeRejected(
              reason = "handshakeAckAcceptorMismatch",
              detail = Some("acceptor mismatch"),
            ),
          )
        .flatMap: _ =>
          Either.cond(
            ack.subscriptions === proposal.subscriptions,
            (),
            CanonicalRejection.HandshakeRejected(
              reason = "handshakeAckSubscriptionMismatch",
              detail = Some("session subscriptions are immutable"),
            ),
          )
        .flatMap: _ =>
          ensureAtLeast(
            "heartbeatInterval",
            ack.negotiated.heartbeatInterval,
            policy.minHeartbeatInterval,
          )
        .flatMap: _ =>
          ensureAtMost(
            "heartbeatInterval",
            ack.negotiated.heartbeatInterval,
            resolved.heartbeatInterval,
          )
        .flatMap: _ =>
          // Liveness timeout has no absolute ceiling in the baseline contract.
          // The ack only needs to respect the negotiated heartbeat-derived floor
          // and the proposal floor.
          ensureAtLeast(
            "livenessTimeout",
            ack.negotiated.livenessTimeout,
            ack.negotiated.heartbeatInterval.multipliedBy(3),
          )
        .flatMap: _ =>
          ensureAtLeast(
            "livenessTimeout",
            ack.negotiated.livenessTimeout,
            resolved.livenessTimeout,
          )
        .flatMap: _ =>
          ensureAtLeast(
            "maxControlRetryInterval",
            ack.negotiated.maxControlRetryInterval,
            policy.minMaxControlRetryInterval,
          )
        .flatMap: _ =>
          ensureAtMost(
            "maxControlRetryInterval",
            ack.negotiated.maxControlRetryInterval,
            resolved.maxControlRetryInterval,
          )
        .map(_ => ack.negotiated)

enum EventStreamMessage[A]:
  case Event(event: GossipEvent[A])
  case KeepAlive(sessionId: DirectionalSessionId, at: Instant)
  case Rejection(rejection: CanonicalRejection)

enum ControlChannelMessage:
  case Batch(batch: ControlBatch)
  case KeepAlive(sessionId: DirectionalSessionId, at: Instant)
  case Ack(sessionId: DirectionalSessionId, at: Instant)
  case Rejection(rejection: CanonicalRejection)

trait GossipTopicContract[A]:
  def topic: GossipTopic
  def validateArtifact(
      event: GossipEvent[A],
  ): Either[CanonicalRejection.ArtifactContractRejected, Unit]
  def exactKnownScopeOf(
      @annotation.unused event: GossipEvent[A],
  ): Either[CanonicalRejection.ArtifactContractRejected, Option[
    ExactKnownSetScope,
  ]] =
    none[ExactKnownSetScope]
      .asRight[CanonicalRejection.ArtifactContractRejected]
  def exactKnownSetLimit: Option[Int] = None
  def requestByIdLimit: Option[Int]   = None
  def deliveryPriority: Int           = 0
  def producerQoS(
      default: GossipProducerQoS,
  ): GossipProducerQoS =
    default
