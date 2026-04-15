package org.sigilaris.node.gossip

import java.time.{Duration, Instant}

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*

final case class GossipEvent[A](
    chainId: ChainId,
    topic: GossipTopic,
    id: StableArtifactId,
    cursor: CursorToken,
    ts: Instant,
    payload: A,
)

/** Base trait for gossip event filters used in producer-side deduplication. */
sealed trait GossipFilter

/** Companion for `GossipFilter` defining concrete filter types. */
object GossipFilter:

  /** A Bloom filter for probabilistic transaction deduplication.
    *
    * @param bitset
    *   the filter bit array
    * @param numHashes
    *   the number of hash functions used
    * @param hashFamilyId
    *   identifier of the hash family (e.g. "murmur3-32")
    */
  final case class TxBloomFilter(
      bitset: ByteVector,
      numHashes: Int,
      hashFamilyId: String,
  ) extends GossipFilter

/** Enumeration of control operation kinds used in the gossip control channel.
  *
  * @param wireName
  *   the wire-protocol name of this operation kind
  */
enum ControlOpKind(val wireName: String):

  /** Sets a Bloom filter for transaction deduplication. */
  case SetFilter extends ControlOpKind("setFilter")

  /** Declares known transaction artifact ids. */
  case SetKnownTx extends ControlOpKind("setKnown.tx")

  /** Declares known exact-scoped artifact ids. */
  case SetKnownExact extends ControlOpKind("setKnown.exact")

  /** Sets the durable cursor for resumable streaming. */
  case SetCursor extends ControlOpKind("setCursor")

  /** Negative acknowledgement requesting replay. */
  case Nack extends ControlOpKind("nack")

  /** Requests specific transaction artifacts by id. */
  case RequestByIdTx extends ControlOpKind("requestById.tx")

  /** Requests specific exact-scoped artifacts by id. */
  case RequestByIdExact extends ControlOpKind("requestById.exact")

  /** Adjusts session configuration parameters. */
  case Config extends ControlOpKind("config")

/** Companion for `ControlOpKind` providing wire-format parsing. */
object ControlOpKind:

  /** Parses a wire-format control operation kind string.
    *
    * @param value
    *   the wire name
    * @return
    *   the parsed kind, or a rejection if unknown
    */
  def parse(
      value: String,
  ): Either[CanonicalRejection.ControlBatchRejected, ControlOpKind] =
    ControlOpKind.values
      .find(_.wireName === value)
      .toRight:
        CanonicalRejection.ControlBatchRejected(
          reason = "unknownControlOpKind",
          detail = Some(value),
        )

/** Enumeration of session configuration keys negotiable over the control
  * channel.
  *
  * @param wireName
  *   the wire-protocol name of this config key
  */
enum SessionConfigKey(val wireName: String):

  /** Maximum number of items per event batch. */
  case TxMaxBatchItems extends SessionConfigKey("tx.maxBatchItems")

  /** Flush interval in milliseconds for event batching. */
  case TxFlushIntervalMs extends SessionConfigKey("tx.flushIntervalMs")

/** Companion for `SessionConfigKey` providing wire-format parsing. */
object SessionConfigKey:

  /** Parses a wire-format config key string.
    *
    * @param value
    *   the wire name
    * @return
    *   the parsed key, or a rejection if unsupported
    */
  def parse(
      value: String,
  ): Either[CanonicalRejection.ControlBatchRejected, SessionConfigKey] =
    SessionConfigKey.values
      .find(_.wireName === value)
      .toRight:
        CanonicalRejection.ControlBatchRejected(
          reason = "unsupportedConfigKey",
          detail = Some(value),
        )

/** Algebraic data type representing individual control operations within a
  * control batch.
  */
enum ControlOp:

  /** Sets a gossip filter for a chain and topic. */
  case SetFilter(chainId: ChainId, topic: GossipTopic, filter: GossipFilter)

  /** Declares known transaction ids for deduplication. */
  case SetKnownTx(chainId: ChainId, ids: Vector[StableArtifactId])

  /** Declares known exact-scoped artifact ids. */
  case SetKnownExact(scope: ExactKnownSetScope, ids: Vector[StableArtifactId])

  /** Updates the durable composite cursor. */
  case SetCursor(cursor: CompositeCursor)

  /** Requests replay from a given cursor position. */
  case Nack(chainId: ChainId, topic: GossipTopic, cursor: Option[CursorToken])

  /** Requests specific transaction artifacts by id. */
  case RequestByIdTx(chainId: ChainId, ids: Vector[StableArtifactId])

  /** Requests specific exact-scoped artifacts by id. */
  case RequestByIdExact(
      scope: ExactKnownSetScope,
      ids: Vector[StableArtifactId],
  )

  /** Adjusts session configuration parameters. */
  case Config(values: Map[SessionConfigKey, Long])

/** Opaque type for a UUIDv4 idempotency key used to deduplicate control
  * batches.
  */
opaque type ControlIdempotencyKey = String

/** Companion for `ControlIdempotencyKey`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ControlIdempotencyKey:

  /** Parses and validates a control idempotency key.
    *
    * @param value
    *   the raw string; must already be a lowercase canonical UUIDv4
    * @return
    *   the validated key, or a control batch rejection
    */
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

  /** Parses a key, throwing on invalid input.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated key
    * @throws IllegalArgumentException
    *   if the value is invalid
    */
  def unsafe(value: String): ControlIdempotencyKey =
    parse(value) match
      case Right(key)  => key
      case Left(error) => throw new IllegalArgumentException(error.reason)

  extension (key: ControlIdempotencyKey) def value: String = key

/** A batch of control operations with an idempotency key for deduplication.
  *
  * @param idempotencyKey
  *   unique key to prevent duplicate application
  * @param ops
  *   the control operations in this batch
  */
final case class ControlBatch(
    idempotencyKey: ControlIdempotencyKey,
    ops: Vector[ControlOp],
)

/** Companion for `ControlBatch`. */
object ControlBatch:

  /** Creates a control batch, validating the idempotency key.
    *
    * @param idempotencyKey
    *   the raw idempotency key string
    * @param ops
    *   the control operations
    * @return
    *   the batch, or a rejection if the key is invalid
    */
  def create(
      idempotencyKey: String,
      ops: Vector[ControlOp],
  ): Either[CanonicalRejection.ControlBatchRejected, ControlBatch] =
    // Empty batches are an intentional no-op success path in the baseline contract.
    ControlIdempotencyKey.parse(idempotencyKey).map(ControlBatch(_, ops))

/** Parameters negotiated during session handshake that govern session timing.
  *
  * @param heartbeatInterval
  *   interval between heartbeat keep-alive messages
  * @param livenessTimeout
  *   duration after which a session is considered dead if no activity
  * @param maxControlRetryInterval
  *   maximum interval between control batch retries
  */
final case class NegotiatedSessionParameters(
    heartbeatInterval: Duration,
    livenessTimeout: Duration,
    maxControlRetryInterval: Duration,
)

/** Policy governing session handshake timing constraints and defaults.
  *
  * @param openingHandshakeTimeout
  *   timeout for completing the handshake
  * @param defaultHeartbeatInterval
  *   default heartbeat interval if not proposed
  * @param defaultLivenessTimeout
  *   default liveness timeout if not proposed
  * @param defaultMaxControlRetryInterval
  *   default max control retry interval if not proposed
  * @param minHeartbeatInterval
  *   minimum allowed heartbeat interval
  * @param maxHeartbeatInterval
  *   maximum allowed heartbeat interval
  * @param minMaxControlRetryInterval
  *   minimum allowed max control retry interval
  * @param maxMaxControlRetryInterval
  *   maximum allowed max control retry interval
  */
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

  /** Validates that a control retry horizon falls within the allowed range.
    *
    * @param retryHorizon
    *   the proposed retry horizon
    * @param negotiated
    *   the negotiated session parameters
    * @return
    *   the validated horizon, or a rejection
    */
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
          ss"minimum=${minimum.toString} maximum=${maximum.toString} actual=${retryHorizon.toString}",
        ),
      ),
    )

/** Companion for `HandshakePolicy`. */
object HandshakePolicy:

  /** The default handshake policy with standard timing values. */
  val default: HandshakePolicy = HandshakePolicy()

/** Proposal sent by the initiator to open a gossip session.
  *
  * @param sessionId
  *   the directional session identifier
  * @param peerCorrelationId
  *   the correlation id tying bidirectional sessions
  * @param initiator
  *   the peer initiating the session
  * @param acceptor
  *   the peer being asked to accept
  * @param subscriptions
  *   the chain-topic pairs to subscribe to
  * @param heartbeatInterval
  *   proposed heartbeat interval, or None for default
  * @param livenessTimeout
  *   proposed liveness timeout, or None for default
  * @param maxControlRetryInterval
  *   proposed max control retry interval, or None for default
  */
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

/** Acknowledgement sent by the acceptor confirming a session open proposal.
  *
  * @param sessionId
  *   the directional session identifier from the proposal
  * @param peerCorrelationId
  *   the correlation id from the proposal
  * @param initiator
  *   the initiating peer
  * @param acceptor
  *   the accepting peer
  * @param subscriptions
  *   the confirmed subscriptions
  * @param negotiated
  *   the final negotiated timing parameters
  */
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
/** Pure functions for session parameter negotiation and ack validation. */
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

  /** Resolves proposed session parameters against the handshake policy.
    *
    * @param proposal
    *   the session open proposal
    * @param policy
    *   the handshake policy to enforce
    * @return
    *   the resolved parameters, or a rejection
    */
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

  /** Builds a session open acknowledgement, validating the acceptor's chosen
    * parameters.
    *
    * @param proposal
    *   the original proposal
    * @param heartbeatInterval
    *   the acceptor's chosen heartbeat interval
    * @param livenessTimeout
    *   the acceptor's chosen liveness timeout
    * @param maxControlRetryInterval
    *   the acceptor's chosen max control retry interval
    * @param policy
    *   the handshake policy to enforce
    * @return
    *   the ack, or a rejection
    */
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

  /** Validates a received ack against the original proposal and policy.
    *
    * @param proposal
    *   the original proposal
    * @param ack
    *   the received acknowledgement
    * @param policy
    *   the handshake policy to enforce
    * @return
    *   the negotiated parameters, or a rejection
    */
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

/** Messages sent on the event stream direction of a gossip session.
  *
  * @tparam A
  *   the artifact payload type
  */
enum EventStreamMessage[A]:

  /** An artifact event delivery. */
  case Event(event: GossipEvent[A])

  /** A heartbeat keep-alive signal. */
  case KeepAlive(sessionId: DirectionalSessionId, at: Instant)

  /** A terminal rejection ending the stream. */
  case Rejection(rejection: CanonicalRejection)

/** Messages sent on the control channel direction of a gossip session. */
enum ControlChannelMessage:

  /** A control batch containing one or more operations. */
  case Batch(batch: ControlBatch)

  /** A heartbeat keep-alive signal. */
  case KeepAlive(sessionId: DirectionalSessionId, at: Instant)

  /** An acknowledgement of a received control batch. */
  case Ack(sessionId: DirectionalSessionId, at: Instant)

  /** A terminal rejection ending the channel. */
  case Rejection(rejection: CanonicalRejection)

/** Contract defining validation and delivery rules for a specific gossip topic.
  *
  * @tparam A
  *   the artifact payload type
  */
trait GossipTopicContract[A]:

  /** @return the gossip topic this contract governs */
  def topic: GossipTopic

  /** Validates an artifact event against this contract.
    *
    * @param event
    *   the event to validate
    * @return
    *   unit on success, or a rejection
    */
  def validateArtifact(
      event: GossipEvent[A],
  ): Either[CanonicalRejection.ArtifactContractRejected, Unit]

  /** Determines the exact known set scope for the given event, if applicable.
    *
    * @param event
    *   the event to inspect
    * @return
    *   the scope if the event belongs to an exact-known-set topic, or None
    */
  def exactKnownScopeOf(
      @annotation.unused event: GossipEvent[A],
  ): Either[CanonicalRejection.ArtifactContractRejected, Option[
    ExactKnownSetScope,
  ]] =
    none[ExactKnownSetScope]
      .asRight[CanonicalRejection.ArtifactContractRejected]
  /** @return optional limit on the size of exact known sets */
  def exactKnownSetLimit: Option[Int] = None

  /** @return optional limit on the number of request-by-id entries */
  def requestByIdLimit: Option[Int] = None

  /** @return delivery priority; higher values are delivered first */
  def deliveryPriority: Int = 0

  /** Returns the producer QoS settings for this contract, optionally
    * overriding the default.
    *
    * @param default
    *   the default QoS settings
    * @return
    *   the effective QoS settings
    */
  def producerQoS(
      default: GossipProducerQoS,
  ): GossipProducerQoS =
    default
