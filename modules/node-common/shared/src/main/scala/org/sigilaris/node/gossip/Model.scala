package org.sigilaris.node.gossip

import java.time.{Duration, Instant}

import scala.util.Random

import cats.Eq
import cats.syntax.all.*
import scodec.bits.{Bases, ByteVector}

import org.sigilaris.core.util.SafeStringInterp.*

/** Base trait for all canonical rejection types in the gossip protocol. */
sealed trait CanonicalRejection:

  /** @return the classification of this rejection */
  def rejectionClass: String

  /** @return the machine-readable reason code */
  def reason: String

  /** @return optional human-readable detail */
  def detail: Option[String]

/** Companion for `CanonicalRejection` defining concrete rejection subtypes.
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object CanonicalRejection:

  /** Rejection during session handshake.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class HandshakeRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "handshakeRejected"

  /** Rejection when a cursor token is no longer valid.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class StaleCursor(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "staleCursor"

  /** Rejection when a control batch cannot be processed.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class ControlBatchRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "controlBatchRejected"

  /** Rejection when an artifact fails contract validation.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class ArtifactContractRejected(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "artifactContractRejected"

  /** Rejection when backfill data is not available.
    *
    * @param reason
    *   machine-readable reason code
    * @param detail
    *   optional human-readable detail
    */
  final case class BackfillUnavailable(
      reason: String,
      detail: Option[String] = None,
  ) extends CanonicalRejection:
    override val rejectionClass: String = "backfillUnavailable"

/** Validation utilities for gossip protocol field values. */
object GossipFieldValidation:
  private val LowerAsciiToken = "^[a-z0-9][a-z0-9._-]*$".r
  private val LowerAsciiTopic = "^[a-z0-9][a-z0-9._-]*$".r
  private val CanonicalUuidV4 =
    "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$".r
  private val UuidLike =
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$".r

  /** Validates that a value is a non-empty lowercase ASCII token.
    *
    * @param kind
    *   the field name, used in error messages
    * @param value
    *   the value to validate
    * @return
    *   the value or an error message
    */
  def validateLowerAsciiToken(
      kind: String,
      value: String,
  ): Either[String, String] =
    Either.cond(
      LowerAsciiToken.matches(value),
      value,
      ss"${kind} must be a non-empty lowercase ASCII token",
    )

  /** Validates that a value is a valid gossip topic string.
    *
    * @param value
    *   the topic string to validate
    * @return
    *   the value or an error message
    */
  def validateTopic(value: String): Either[String, String] =
    Either.cond(
      LowerAsciiTopic.matches(value),
      value,
      "topic must be a non-empty lowercase ASCII token",
    )

  /** Validates that a value is a canonical lowercase UUIDv4 string.
    *
    * @param kind
    *   the field name, used in error messages
    * @param value
    *   the UUID string to validate
    * @return
    *   the validated UUID string or an error message
    */
  def validateUuidV4(kind: String, value: String): Either[String, String] =
    if CanonicalUuidV4.matches(value) then Right[String, String](value)
    else if UuidLike.matches(value) then
      Left[String, String](
        ss"${kind} must be a lowercase canonical UUIDv4 string",
      )
    else Left[String, String](ss"${kind} must be a UUIDv4 string")

/** Opaque type representing a unique identifier for one direction of a gossip
  * session.
  */
opaque type DirectionalSessionId = String

private object CrossRuntimeUuid:
  private def byteToHex(byte: Byte): String =
    ((byte & 0xff) + 0x100).toHexString.substring(1)

  private def randomUuidV4String(random: Random): String =
    val bytes = Array.ofDim[Byte](16)
    random.nextBytes(bytes)
    bytes(6) = ((bytes(6) & 0x0f) | 0x40).toByte
    bytes(8) = ((bytes(8) & 0x3f) | 0x80).toByte
    val hex = bytes.iterator.map(byteToHex).mkString
    ss"${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"

  def randomUuidV4(): String =
    randomUuidV4From(Random)

  def randomUuidV4From(random: Random): String =
    randomUuidV4String(random)

/** Companion for `DirectionalSessionId`. */
object DirectionalSessionId:

  /** Generates a random directional session identifier.
    *
    * @return
    *   a new random session id
    */
  def random(): DirectionalSessionId =
    CrossRuntimeUuid.randomUuidV4()

  private[gossip] def fromRandom(random: Random): DirectionalSessionId =
    CrossRuntimeUuid.randomUuidV4From(random)

  /** Parses a string into a validated directional session id.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated id or an error message
    */
  def parse(value: String): Either[String, DirectionalSessionId] =
    GossipFieldValidation
      .validateUuidV4("directionalSessionId", value)
      .map(_ => value)

  extension (id: DirectionalSessionId) def value: String = id

  given Eq[DirectionalSessionId] = Eq.by(_.value)

/** Opaque type for a correlation identifier that ties bidirectional sessions
  * between two peers.
  */
opaque type PeerCorrelationId = String

/** Companion for `PeerCorrelationId`. */
object PeerCorrelationId:

  /** Generates a random peer correlation identifier.
    *
    * @return
    *   a new random correlation id
    */
  def random(): PeerCorrelationId =
    CrossRuntimeUuid.randomUuidV4()

  private[gossip] def fromRandom(random: Random): PeerCorrelationId =
    CrossRuntimeUuid.randomUuidV4From(random)

  /** Parses a string into a validated peer correlation id.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated id or an error message
    */
  def parse(value: String): Either[String, PeerCorrelationId] =
    GossipFieldValidation
      .validateUuidV4("peerCorrelationId", value)
      .map(_ => value)

  /** Compares two correlation ids lexicographically, used for simultaneous-open
    * tie-breaking.
    *
    * @param left
    *   the first id
    * @param right
    *   the second id
    * @return
    *   negative, zero, or positive per standard comparison contract
    */
  def lexicographicCompare(
      left: PeerCorrelationId,
      right: PeerCorrelationId,
  ): Int =
    left.value.compareTo(right.value)

  extension (id: PeerCorrelationId) def value: String = id

  given Eq[PeerCorrelationId] = Eq.by(_.value)

/** Opaque type representing the identity of a gossip peer. */
opaque type PeerIdentity = String

/** Companion for `PeerIdentity`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object PeerIdentity:

  /** Parses and validates a peer identity string.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated identity or an error message
    */
  def parse(value: String): Either[String, PeerIdentity] =
    GossipFieldValidation
      .validateLowerAsciiToken("peerIdentity", value)
      .map(_ => value)

  /** Parses a peer identity, throwing on invalid input.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated identity
    * @throws IllegalArgumentException
    *   if the value is invalid
    */
  def unsafe(value: String): PeerIdentity =
    parse(value) match
      case Right(peer) => peer
      case Left(error) => throw new IllegalArgumentException(error)

  extension (identity: PeerIdentity)
    /** @return the underlying string value */
    def value: String = identity

  given Eq[PeerIdentity] = Eq.by(_.value)

/** Opaque type representing a blockchain chain identifier. */
opaque type ChainId = String

/** Companion for `ChainId`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ChainId:

  /** Parses and validates a chain id string.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated chain id or an error message
    */
  def parse(value: String): Either[String, ChainId] =
    GossipFieldValidation
      .validateLowerAsciiToken("chainId", value)
      .map(_ => value)

  /** Parses a chain id, throwing on invalid input.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated chain id
    * @throws IllegalArgumentException
    *   if the value is invalid
    */
  def unsafe(value: String): ChainId =
    parse(value) match
      case Right(chainId) => chainId
      case Left(error)    => throw new IllegalArgumentException(error)

  extension (chainId: ChainId)
    /** @return the underlying string value */
    def value: String = chainId

  given Eq[ChainId] = Eq.by(_.value)

/** Opaque type representing a gossip topic name. */
opaque type GossipTopic = String

/** Companion for `GossipTopic` with well-known topic constants and parsing.
  */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object GossipTopic:

  /** Topic for transaction gossip. */
  val tx: GossipTopic = "tx"

  /** Topic for consensus proposal gossip. */
  val consensusProposal: GossipTopic = "consensus.proposal"

  /** Topic for consensus vote gossip. */
  val consensusVote: GossipTopic = "consensus.vote"

  /** Topic for consensus timeout vote gossip. */
  val consensusTimeoutVote: GossipTopic = "consensus.timeout-vote"

  /** Topic for consensus new-view gossip. */
  val consensusNewView: GossipTopic = "consensus.new-view"

  /** Parses and validates a topic string.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated topic or an error message
    */
  def parse(value: String): Either[String, GossipTopic] =
    GossipFieldValidation.validateTopic(value).map(_ => value)

  /** Parses a topic, throwing on invalid input.
    *
    * @param value
    *   the raw string
    * @return
    *   the validated topic
    * @throws IllegalArgumentException
    *   if the value is invalid
    */
  def unsafe(value: String): GossipTopic =
    parse(value) match
      case Right(topic) => topic
      case Left(error)  => throw new IllegalArgumentException(error)

  extension (topic: GossipTopic)
    /** @return the underlying string value */
    def value: String = topic

  given Eq[GossipTopic] = Eq.by(_.value)

/** A pair of chain id and gossip topic, identifying a specific gossip stream.
  *
  * @param chainId
  *   the chain identifier
  * @param topic
  *   the gossip topic
  */
final case class ChainTopic(
    chainId: ChainId,
    topic: GossipTopic,
)

/** Companion for `ChainTopic`. */
object ChainTopic:
  given Eq[ChainTopic] = Eq.fromUniversalEquals

/** An immutable set of chain-topic pairs that a session is subscribed to.
  *
  * @param values
  *   the subscribed chain-topic pairs
  */
@SuppressWarnings(Array("org.wartremover.warts.Overloading"))
final case class SessionSubscription private (
    values: Set[ChainTopic],
):

  /** Checks whether the subscription includes the given chain-topic pair.
    *
    * @param chainTopic
    *   the chain-topic pair
    * @return
    *   true if subscribed
    */
  def contains(chainTopic: ChainTopic): Boolean =
    values.contains(chainTopic)

  /** Checks whether the subscription includes the given chain and topic.
    *
    * @param chainId
    *   the chain identifier
    * @param topic
    *   the gossip topic
    * @return
    *   true if subscribed
    */
  def contains(chainId: ChainId, topic: GossipTopic): Boolean =
    contains(ChainTopic(chainId, topic))

/** Companion for `SessionSubscription` providing factory methods. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object SessionSubscription:
  given Eq[SessionSubscription] = Eq.fromUniversalEquals

  /** Creates a subscription from the given chain-topic pairs.
    *
    * @param values
    *   the chain-topic pairs to subscribe to
    * @return
    *   the subscription, or an error if empty
    */
  def of(values: ChainTopic*): Either[String, SessionSubscription] =
    fromSet(values.toSet)

  /** Creates a subscription from a set of chain-topic pairs.
    *
    * @param values
    *   the chain-topic pairs to subscribe to
    * @return
    *   the subscription, or an error if empty
    */
  def fromSet(values: Set[ChainTopic]): Either[String, SessionSubscription] =
    Either.cond(
      values.nonEmpty,
      SessionSubscription(values),
      "subscription must not be empty",
    )

  /** Creates a subscription, throwing on invalid input.
    *
    * @param values
    *   the chain-topic pairs to subscribe to
    * @return
    *   the subscription
    * @throws IllegalArgumentException
    *   if the values are empty
    */
  def unsafe(values: ChainTopic*): SessionSubscription =
    of(values*) match
      case Right(subscription) => subscription
      case Left(error)         => throw new IllegalArgumentException(error)

/** Opaque type representing a content-addressable artifact identifier. */
opaque type StableArtifactId = ByteVector

/** Companion for `StableArtifactId` providing parsing and construction. */
@SuppressWarnings(
  Array("org.wartremover.warts.Throw", "org.wartremover.warts.Any"),
)
object StableArtifactId:

  /** Creates a stable artifact id from raw bytes.
    *
    * @param bytes
    *   the byte content
    * @return
    *   the id, or an error if empty
    */
  def fromBytes(bytes: ByteVector): Either[String, StableArtifactId] =
    Either.cond(bytes.nonEmpty, bytes, "stable artifact id must not be empty")

  /** Parses a hex-encoded stable artifact id.
    *
    * @param value
    *   the hex string
    * @return
    *   the id, or an error message
    */
  def fromHex(value: String): Either[String, StableArtifactId] =
    ByteVector
      .fromHexDescriptive(value)
      .left
      .map(error => ss"invalid stable artifact id hex: ${error}")
      .flatMap(fromBytes)

  /** Parses a hex-encoded stable artifact id, throwing on invalid input.
    *
    * @param value
    *   the hex string
    * @return
    *   the id
    * @throws IllegalArgumentException
    *   if the hex is invalid or empty
    */
  def unsafeFromHex(value: String): StableArtifactId =
    fromHex(value) match
      case Right(id)   => id
      case Left(error) => throw new IllegalArgumentException(error)

  /** Creates a stable artifact id from raw bytes, throwing on invalid input.
    *
    * @param bytes
    *   the byte content
    * @return
    *   the id
    * @throws IllegalArgumentException
    *   if the bytes are empty
    */
  def unsafeFromBytes(bytes: ByteVector): StableArtifactId =
    fromBytes(bytes) match
      case Right(id)   => id
      case Left(error) => throw new IllegalArgumentException(error)

  extension (id: StableArtifactId)
    def bytes: ByteVector  = id
    def toHexLower: String = id.toHex

  given Eq[StableArtifactId] = Eq.by(_.toHexLower)

/** Opaque type representing a windowing key within a gossip topic scope. */
opaque type TopicWindowKey = ByteVector

/** Companion for `TopicWindowKey` providing parsing and construction. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object TopicWindowKey:

  /** Creates a topic window key from raw bytes.
    *
    * @param bytes
    *   the byte content
    * @return
    *   the key, or an error if empty
    */
  def fromBytes(
      bytes: ByteVector,
  ): Either[String, TopicWindowKey] =
    Either.cond(bytes.nonEmpty, bytes, "topic window key must not be empty")

  /** Parses a hex-encoded topic window key.
    *
    * @param value
    *   the hex string
    * @return
    *   the key, or an error message
    */
  def fromHex(
      value: String,
  ): Either[String, TopicWindowKey] =
    ByteVector
      .fromHexDescriptive(value)
      .left
      .map(error => ss"invalid topic window key hex: ${error}")
      .flatMap(fromBytes)

  /** Parses a hex-encoded topic window key, throwing on invalid input.
    *
    * @param value
    *   the hex string
    * @return
    *   the key
    * @throws IllegalArgumentException
    *   if the hex is invalid or empty
    */
  def unsafeFromHex(
      value: String,
  ): TopicWindowKey =
    fromHex(value) match
      case Right(windowKey) => windowKey
      case Left(error)      => throw new IllegalArgumentException(error)

  /** Creates a topic window key from raw bytes, throwing on invalid input.
    *
    * @param bytes
    *   the byte content
    * @return
    *   the key
    * @throws IllegalArgumentException
    *   if the bytes are empty
    */
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

/** Identifies a scoped set of known artifacts within a specific chain, topic,
  * and window.
  *
  * @param chainId
  *   the chain identifier
  * @param topic
  *   the gossip topic
  * @param windowKey
  *   the window key within the topic
  */
final case class ExactKnownSetScope(
    chainId: ChainId,
    topic: GossipTopic,
    windowKey: TopicWindowKey,
)

/** Companion for `ExactKnownSetScope`. */
object ExactKnownSetScope:
  given Eq[ExactKnownSetScope] = Eq.fromUniversalEquals

/** Opaque type representing a versioned cursor token for resumable event
  * streaming.
  */
opaque type CursorToken = ByteVector

@SuppressWarnings(
  Array(
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.Throw",
  ),
)
/** Companion for `CursorToken` providing construction and decoding. */
object CursorToken:

  /** The current cursor token wire format version. */
  val CurrentVersion: Int = 1

  /** Issues a new cursor token with the given payload and version.
    *
    * @param payload
    *   the cursor payload bytes
    * @param version
    *   the version byte (default: CurrentVersion)
    * @return
    *   the assembled cursor token
    */
  def issue(payload: ByteVector, version: Int = CurrentVersion): CursorToken =
    if version < 0 || version > 0xff then
      throw new IllegalArgumentException(
        "cursor token version must fit in one unsigned byte",
      )
    ByteVector.fromByte(version.toByte) ++ payload

  /** Creates a cursor token from raw bytes.
    *
    * @param bytes
    *   the raw bytes (must not be empty)
    * @return
    *   the token, or an error if empty
    */
  def fromBytes(bytes: ByteVector): Either[String, CursorToken] =
    Either.cond(bytes.nonEmpty, bytes, "cursor token must not be empty")

  /** Decodes a base64url-encoded cursor token.
    *
    * @param value
    *   the base64url string (without padding)
    * @return
    *   the token, or an error message
    */
  def decodeBase64Url(value: String): Either[String, CursorToken] =
    ByteVector
      .fromBase64Descriptive(value, Bases.Alphabets.Base64UrlNoPad)
      .flatMap(fromBytes)

  extension (token: CursorToken)
    def bytes: ByteVector   = token
    def version: Int        = token.head.toInt & 0xff
    def payload: ByteVector = token.drop(1)
    def toBase64Url: String =
      token.toBase64UrlNoPad

    def validateVersion(
        expected: Int = CurrentVersion,
    ): Either[CanonicalRejection.StaleCursor, CursorToken] =
      Either.cond(
        version === expected,
        token,
        CanonicalRejection.StaleCursor(
          reason = "cursorTokenVersionMismatch",
          detail = Some(
            ss"expected=${expected.toString} actual=${version.toString}",
          ),
        ),
      )

  given Eq[CursorToken] = Eq.by(_.toBase64Url)

/** A cursor that holds per-chain-topic cursor tokens for multiplexed session
  * streaming.
  *
  * @param values
  *   the mapping of chain-topic pairs to their cursor tokens
  */
final case class CompositeCursor(
    values: Map[ChainTopic, CursorToken],
):

  /** Returns the cursor token for the given chain-topic pair, if present.
    *
    * @param chainTopic
    *   the chain-topic pair
    * @return
    *   the cursor token, or None
    */
  def tokenFor(chainTopic: ChainTopic): Option[CursorToken] =
    values.get(chainTopic)

  /** Returns the cursor token for the given chain id and topic, if present.
    *
    * @param chainId
    *   the chain identifier
    * @param topic
    *   the gossip topic
    * @return
    *   the cursor token, or None
    */
  def tokenForChainAndTopic(
      chainId: ChainId,
      topic: GossipTopic,
  ): Option[CursorToken] =
    tokenFor(ChainTopic(chainId, topic))

  /** Checks whether no cursor exists for the given chain-topic, indicating a
    * full replay from origin.
    *
    * @param chainTopic
    *   the chain-topic pair
    * @return
    *   true if no cursor is stored
    */
  def isOriginReplay(chainTopic: ChainTopic): Boolean =
    !values.contains(chainTopic)

  /** @return true if this cursor contains no entries */
  def isEmpty: Boolean =
    values.isEmpty

/** Companion for `CompositeCursor`. */
object CompositeCursor:

  /** An empty composite cursor with no chain-topic entries. */
  val empty: CompositeCursor = CompositeCursor(Map.empty)

/** A single gossip event carrying an artifact payload.
  *
  * @tparam A
  *   the payload type
  * @param chainId
  *   the originating chain
  * @param topic
  *   the gossip topic
  * @param id
  *   the content-addressable artifact identifier
  * @param cursor
  *   the cursor token for resumable streaming
  * @param ts
  *   the event timestamp
  * @param payload
  *   the artifact payload
  */
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
    *   the raw string
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
