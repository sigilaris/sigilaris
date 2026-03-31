package org.sigilaris.node.jvm.runtime.gossip.tx

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.node.jvm.runtime.gossip.*

trait TxIdentity[-A]:
  def stableIdOf(value: A): StableArtifactId

object TxIdentity:
  def fromHash[A: Hash]: TxIdentity[A] =
    (value: A) =>
      StableArtifactId.unsafeFromBytes(value.toHash.toUInt256.bytes)

object TxTopic extends GossipTopicContract[Any]:
  override val topic: GossipTopic = GossipTopic.tx

  override def validateArtifact(
      event: GossipEvent[Any],
  ): Either[CanonicalRejection.ArtifactContractRejected, Unit] =
    Either.cond(
      event.topic == GossipTopic.tx,
      (),
      CanonicalRejection.ArtifactContractRejected(
        reason = "unexpectedTopic",
        detail = Some(event.topic.value),
      ),
    )
