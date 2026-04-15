package org.sigilaris.node.gossip

import scala.util.Random

import cats.Eq

import org.sigilaris.core.util.SafeStringInterp.*

opaque type DirectionalSessionId = String

private object CrossRuntimeUuid:
  // Shared node-common code also targets Scala.js, so UUID generation stays
  // local instead of depending on JVM-only UUID helpers.
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
    *   the raw string; must already be a lowercase canonical UUIDv4
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
    *   the raw string; must already be a lowercase canonical UUIDv4
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
