package org.sigilaris.node.gossip.tx

import java.time.{Duration, Instant}

import org.sigilaris.node.gossip.{GossipFilter, StableArtifactId}

/** Peer knowledge classification for an artifact needed by a proposal. */
enum GossipArtifactKnownState:
  case Known
  case Missing
  case Ambiguous(reason: GossipArtifactAmbiguityReason)

/** Why the producer cannot safely omit an artifact. */
enum GossipArtifactAmbiguityReason:
  case NoBloomFilter
  case BloomMatched
  case BloomStale
  case BloomSaturated
  case BloomInvalid
  case BloomReceiptTimeUnavailable

/** Classification policy for Bloom freshness and saturation. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class GossipArtifactKnownStatePolicy(
    maxFilterAge: Duration = Duration.ofSeconds(30),
    saturatedBitDensity: Double = 0.95d,
):
  require(!maxFilterAge.isNegative, "maxFilterAge must be non-negative")
  require(
    saturatedBitDensity >= 0.0d && saturatedBitDensity <= 1.0d,
    "saturatedBitDensity must be within [0.0, 1.0]",
  )

/** Known-state classifier shared by proposal dependency planning. */
object GossipArtifactKnownStateClassifier:
  val defaultPolicy: GossipArtifactKnownStatePolicy =
    GossipArtifactKnownStatePolicy()

  def classify(
      artifactId: StableArtifactId,
      exactKnownIds: Set[StableArtifactId],
      bloomFilter: Option[GossipFilter.TxBloomFilter],
      bloomReceivedAt: Option[Instant],
      now: Instant,
      policy: GossipArtifactKnownStatePolicy,
  ): GossipArtifactKnownState =
    if exactKnownIds.contains(artifactId) then GossipArtifactKnownState.Known
    else
      bloomFilter match
        case None =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.NoBloomFilter,
          )
        case Some(filter) if !validBloom(filter) =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.BloomInvalid,
          )
        case Some(_) if bloomReceivedAt.isEmpty =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.BloomReceiptTimeUnavailable,
          )
        case Some(filter)
            if bloomReceivedAt.exists(received =>
              received.plus(policy.maxFilterAge).isBefore(now),
            ) =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.BloomStale,
          )
        case Some(filter)
            if TxBloomFilterSupport.bitDensity(filter) >=
              policy.saturatedBitDensity =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.BloomSaturated,
          )
        case Some(filter) if TxBloomFilterSupport.mightContain(filter, artifactId) =>
          GossipArtifactKnownState.Ambiguous(
            GossipArtifactAmbiguityReason.BloomMatched,
          )
        case Some(_) =>
          GossipArtifactKnownState.Missing

  private def validBloom(filter: GossipFilter.TxBloomFilter): Boolean =
    filter.bitset.nonEmpty && filter.numHashes > 0
