package org.sigilaris.node.gossip

import cats.Eq
import cats.syntax.all.*
import scodec.bits.{Bases, ByteVector}

import org.sigilaris.core.util.SafeStringInterp.*

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
  def issue(
      payload: ByteVector,
      version: Int = CurrentVersion,
  ): Either[String, CursorToken] =
    Either.cond(
      version >= 0 && version <= 0xff,
      ByteVector.fromByte(version.toByte) ++ payload,
      "cursor token version must fit in one unsigned byte",
    )

  /** Issues a cursor token, throwing on invalid version values. */
  def unsafeIssue(
      payload: ByteVector,
      version: Int = CurrentVersion,
  ): CursorToken =
    issue(payload, version) match
      case Right(token) => token
      case Left(error)  => throw new IllegalArgumentException(error)

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
