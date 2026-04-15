package org.sigilaris.node.jvm.transport.armeria.gossip

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import org.sigilaris.node.gossip.CanonicalRejection

/** Wire format for a chain-topic subscription pair.
  *
  * @param chainId
  *   string identifier of the chain
  * @param topic
  *   gossip topic name
  */
final case class ChainTopicWire(
    chainId: String,
    topic: String,
)

/** JSON codec instances for `ChainTopicWire`. */
object ChainTopicWire:
  given Decoder[ChainTopicWire] = deriveDecoder
  given Encoder[ChainTopicWire] = deriveEncoder

/** Wire format for a session open proposal sent by the initiating peer.
  *
  * @param sessionId
  *   proposed directional session identifier
  * @param peerCorrelationId
  *   correlation identifier assigned by the proposing peer
  * @param initiator
  *   identity of the session initiator
  * @param acceptor
  *   identity of the session acceptor
  * @param subscriptions
  *   chain-topic pairs the initiator wants to subscribe to
  * @param heartbeatIntervalMs
  *   proposed heartbeat interval in milliseconds
  * @param livenessTimeoutMs
  *   proposed liveness timeout in milliseconds
  * @param maxControlRetryIntervalMs
  *   proposed maximum control retry interval in milliseconds
  */
final case class SessionOpenProposalWire(
    sessionId: String,
    peerCorrelationId: String,
    initiator: String,
    acceptor: String,
    subscriptions: Vector[ChainTopicWire],
    heartbeatIntervalMs: Option[Long],
    livenessTimeoutMs: Option[Long],
    maxControlRetryIntervalMs: Option[Long],
)

/** JSON codec instances for `SessionOpenProposalWire`. */
object SessionOpenProposalWire:
  given Decoder[SessionOpenProposalWire] = deriveDecoder
  given Encoder[SessionOpenProposalWire] = deriveEncoder

/** Wire format for a session open acknowledgement returned by the accepting peer.
  *
  * @param sessionId
  *   confirmed directional session identifier
  * @param peerCorrelationId
  *   correlation identifier from the original proposal
  * @param initiator
  *   identity of the session initiator
  * @param acceptor
  *   identity of the session acceptor
  * @param subscriptions
  *   negotiated chain-topic subscriptions
  * @param heartbeatIntervalMs
  *   negotiated heartbeat interval in milliseconds
  * @param livenessTimeoutMs
  *   negotiated liveness timeout in milliseconds
  * @param maxControlRetryIntervalMs
  *   negotiated maximum control retry interval in milliseconds
  */
final case class SessionOpenAckWire(
    sessionId: String,
    peerCorrelationId: String,
    initiator: String,
    acceptor: String,
    subscriptions: Vector[ChainTopicWire],
    heartbeatIntervalMs: Long,
    livenessTimeoutMs: Long,
    maxControlRetryIntervalMs: Long,
)

/** JSON codec instances for `SessionOpenAckWire`. */
object SessionOpenAckWire:
  given Decoder[SessionOpenAckWire] = deriveDecoder
  given Encoder[SessionOpenAckWire] = deriveEncoder

/** Wire format for a single cursor entry within a composite cursor.
  *
  * @param chainId
  *   chain identifier for this cursor position
  * @param topic
  *   gossip topic for this cursor position
  * @param token
  *   Base64URL-encoded cursor token
  */
final case class CursorEntryWire(
    chainId: String,
    topic: String,
    token: String,
)

/** JSON codec instances for `CursorEntryWire`. */
object CursorEntryWire:
  given Decoder[CursorEntryWire] = deriveDecoder
  given Encoder[CursorEntryWire] = deriveEncoder

/** Wire format for a transaction Bloom filter used in gossip deduplication.
  *
  * @param bitsetBase64Url
  *   Base64URL-encoded bitset bytes
  * @param numHashes
  *   number of hash functions used
  * @param hashFamilyId
  *   identifier of the hash family
  */
final case class TxBloomFilterWire(
    bitsetBase64Url: String,
    numHashes: Int,
    hashFamilyId: String,
)

/** JSON codec instances for `TxBloomFilterWire`. */
object TxBloomFilterWire:
  given Decoder[TxBloomFilterWire] = deriveDecoder
  given Encoder[TxBloomFilterWire] = deriveEncoder

/** Wire format for a single control operation within a control batch.
  *
  * @param kind
  *   operation kind (e.g. "setFilter", "setCursor", "nack")
  * @param chainId
  *   optional chain identifier for chain-scoped operations
  * @param topic
  *   optional gossip topic for topic-scoped operations
  * @param windowKey
  *   optional hex-encoded topic window key for exact known set operations
  * @param cursor
  *   optional composite cursor entries for setCursor operations
  * @param cursorToken
  *   optional Base64URL-encoded cursor token for nack operations
  * @param ids
  *   optional hex-encoded artifact identifiers
  * @param filter
  *   optional Bloom filter for setFilter operations
  * @param config
  *   optional key-value configuration for config operations
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ControlOpWire(
    kind: String,
    chainId: Option[String] = None,
    topic: Option[String] = None,
    windowKey: Option[String] = None,
    cursor: Option[Vector[CursorEntryWire]] = None,
    cursorToken: Option[String] = None,
    ids: Option[Vector[String]] = None,
    filter: Option[TxBloomFilterWire] = None,
    config: Option[Map[String, Long]] = None,
)

/** JSON codec instances for `ControlOpWire`. */
object ControlOpWire:
  given Decoder[ControlOpWire] = deriveDecoder
  given Encoder[ControlOpWire] = deriveEncoder

/** Wire format for a batch of control operations with an idempotency key.
  *
  * @param idempotencyKey
  *   unique key for deduplicating repeated batch submissions
  * @param ops
  *   ordered control operations in this batch
  */
final case class ControlBatchWire(
    idempotencyKey: String,
    ops: Vector[ControlOpWire],
)

/** JSON codec instances for `ControlBatchWire`. */
object ControlBatchWire:
  given Decoder[ControlBatchWire] = deriveDecoder
  given Encoder[ControlBatchWire] = deriveEncoder

/** Wire format for a control channel request.
  *
  * @param kind
  *   request kind (e.g. "batch", "controlKeepAlive")
  * @param batch
  *   optional control batch payload when kind is "batch"
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ControlRequestWire(
    kind: String,
    batch: Option[ControlBatchWire] = None,
)

/** JSON codec instances for `ControlRequestWire`. */
object ControlRequestWire:
  given Decoder[ControlRequestWire] = deriveDecoder
  given Encoder[ControlRequestWire] = deriveEncoder

/** Wire format for an event stream request.
  *
  * @param kind
  *   request kind (e.g. "poll", "eventKeepAlive")
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class EventRequestWire(
    kind: String = "poll",
)

/** JSON codec instances for `EventRequestWire`. */
object EventRequestWire:
  given Decoder[EventRequestWire] = deriveDecoder
  given Encoder[EventRequestWire] = deriveEncoder

/** Wire format for a canonical rejection response.
  *
  * @param rejectionClass
  *   classification of the rejection (e.g. "handshakeRejected", "controlBatchRejected")
  * @param reason
  *   machine-readable reason code
  * @param detail
  *   optional human-readable detail message
  */
final case class RejectionWire(
    rejectionClass: String,
    reason: String,
    detail: Option[String],
)

/** JSON codec instances for `RejectionWire`. */
object RejectionWire:
  given Decoder[RejectionWire] = deriveDecoder
  given Encoder[RejectionWire] = deriveEncoder

  def fromCanonical(
      rejection: CanonicalRejection,
  ): RejectionWire =
    RejectionWire(
      rejectionClass = rejection.rejectionClass,
      reason = rejection.reason,
      detail = rejection.detail,
    )

  def toCanonical(
      wire: RejectionWire,
  ): CanonicalRejection =
    wire.rejectionClass match
      case "handshakeRejected" =>
        CanonicalRejection.HandshakeRejected(
          reason = wire.reason,
          detail = wire.detail,
        )
      case "controlBatchRejected" =>
        CanonicalRejection.ControlBatchRejected(
          reason = wire.reason,
          detail = wire.detail,
        )
      case "artifactContractRejected" =>
        CanonicalRejection.ArtifactContractRejected(
          reason = wire.reason,
          detail = wire.detail,
        )
      case "staleCursor" =>
        CanonicalRejection.StaleCursor(
          reason = wire.reason,
          detail = wire.detail,
        )
      case _ =>
        CanonicalRejection.BackfillUnavailable(
          reason = wire.reason,
          detail = wire.detail,
        )

/** Wire format for a control channel response.
  *
  * @param status
  *   outcome status (e.g. "applied", "deduplicated", "ack")
  * @param sessionId
  *   optional session identifier echoed back in the response
  * @param deduplicated
  *   optional flag indicating whether the batch was deduplicated
  * @param rejection
  *   optional rejection payload when the control request was rejected
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class ControlResponseWire(
    status: String,
    sessionId: Option[String] = None,
    deduplicated: Option[Boolean] = None,
    rejection: Option[RejectionWire] = None,
)

/** JSON codec instances for `ControlResponseWire`. */
object ControlResponseWire:
  given Decoder[ControlResponseWire] = deriveDecoder
  given Encoder[ControlResponseWire] = deriveEncoder

/** Wire format for a single gossip event carrying an artifact payload.
  *
  * @tparam A
  *   the payload type
  * @param chainId
  *   chain identifier the event belongs to
  * @param topic
  *   gossip topic the event belongs to
  * @param id
  *   hex-encoded stable artifact identifier
  * @param cursor
  *   Base64URL-encoded cursor token marking this event's position
  * @param ts
  *   event timestamp as epoch milliseconds
  * @param payload
  *   the artifact payload
  */
final case class EventWire[A](
    chainId: String,
    topic: String,
    id: String,
    cursor: String,
    ts: Long,
    payload: A,
)

/** JSON codec instances for `EventWire`. */
object EventWire:
  given [A: Decoder]: Decoder[EventWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventWire[A]] = deriveEncoder

/** Wire format for an event stream envelope, which may carry an event, keep-alive, or rejection.
  *
  * @tparam A
  *   the event payload type
  * @param kind
  *   envelope kind (e.g. "event", "keepAlive", "rejection")
  * @param sessionId
  *   session identifier this envelope belongs to
  * @param atEpochMs
  *   optional epoch milliseconds for keep-alive envelopes
  * @param event
  *   optional event payload when kind is "event"
  * @param rejection
  *   optional rejection payload when kind is "rejection"
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class EventEnvelopeWire[A](
    kind: String,
    sessionId: String,
    atEpochMs: Option[Long] = None,
    event: Option[EventWire[A]] = None,
    rejection: Option[RejectionWire] = None,
)

/** JSON codec instances for `EventEnvelopeWire`. */
object EventEnvelopeWire:
  given [A: Decoder]: Decoder[EventEnvelopeWire[A]] = deriveDecoder
  given [A: Encoder]: Encoder[EventEnvelopeWire[A]] = deriveEncoder
