package org.sigilaris.node.gossip.tx

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.node.gossip.*

/** Type class for computing a stable content-addressable identifier from a
  * transaction payload.
  *
  * @tparam A
  *   the transaction payload type (contravariant)
  */
trait TxIdentity[-A]:

  /** Computes the stable artifact id for the given value.
    *
    * @param value
    *   the transaction payload
    * @return
    *   the stable artifact identifier
    */
  def stableIdOf(value: A): StableArtifactId

/** Companion for `TxIdentity` providing derivation from Hash instances. */
object TxIdentity:

  /** Derives a `TxIdentity` from a `Hash` instance, using the hash as the
    * stable id.
    *
    * @tparam A
    *   the hashable type
    * @return
    *   a TxIdentity that uses the hash as the artifact id
    */
  def fromHash[A: Hash]: TxIdentity[A] =
    (value: A) => StableArtifactId.unsafeFromBytes(value.toHash.toUInt256.bytes)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
/** Provides the standard gossip topic contract for transaction artifacts. */
object TxTopic:

  /** Creates a `GossipTopicContract` for the "tx" topic that validates the
    * event topic matches.
    *
    * @tparam A
    *   the artifact payload type
    * @return
    *   the transaction topic contract
    */
  def contract[A]: GossipTopicContract[A] =
    new GossipTopicContract[A]:
      override val topic: GossipTopic = GossipTopic.tx

      override def validateArtifact(
          event: GossipEvent[A],
      ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
        Either.cond(
          event.topic == GossipTopic.tx,
          (),
          CanonicalRejection.ArtifactContractRejected(
            reason = "unexpectedTopic",
            detail = Some(event.topic.value),
          ),
        )
