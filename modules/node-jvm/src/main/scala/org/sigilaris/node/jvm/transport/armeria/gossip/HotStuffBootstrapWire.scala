package org.sigilaris.node.jvm.transport.armeria.gossip

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

/** Wire format for a finalized anchor suggestion response.
  *
  * @param suggestionBase64Url
  *   Base64URL-encoded finalized anchor suggestion, or None if unavailable
  */
final case class FinalizedSuggestionResponseWire(
    suggestionBase64Url: Option[String],
)

/** JSON codec instances for `FinalizedSuggestionResponseWire`. */
object FinalizedSuggestionResponseWire:
  given Decoder[FinalizedSuggestionResponseWire] = deriveDecoder
  given Encoder[FinalizedSuggestionResponseWire] = deriveEncoder

/** Wire format for a snapshot node fetch request.
  *
  * @param stateRoot
  *   hex-encoded state root hash
  * @param hashes
  *   hex-encoded Merkle trie node hashes to fetch
  */
final case class SnapshotNodeFetchRequestWire(
    stateRoot: String,
    hashes: Vector[String],
)

/** JSON codec instances for `SnapshotNodeFetchRequestWire`. */
object SnapshotNodeFetchRequestWire:
  given Decoder[SnapshotNodeFetchRequestWire] = deriveDecoder
  given Encoder[SnapshotNodeFetchRequestWire] = deriveEncoder

/** Wire format for a single snapshot Merkle trie node.
  *
  * @param hash
  *   hex-encoded hash of the node
  * @param nodeBase64Url
  *   Base64URL-encoded serialized node data
  */
final case class SnapshotNodeWire(
    hash: String,
    nodeBase64Url: String,
)

/** JSON codec instances for `SnapshotNodeWire`. */
object SnapshotNodeWire:
  given Decoder[SnapshotNodeWire] = deriveDecoder
  given Encoder[SnapshotNodeWire] = deriveEncoder

/** Wire format for a snapshot node fetch response containing fetched nodes.
  *
  * @param nodes
  *   the fetched Merkle trie nodes
  */
final case class SnapshotNodeFetchResponseWire(
    nodes: Vector[SnapshotNodeWire],
)

/** JSON codec instances for `SnapshotNodeFetchResponseWire`. */
object SnapshotNodeFetchResponseWire:
  given Decoder[SnapshotNodeFetchResponseWire] = deriveDecoder
  given Encoder[SnapshotNodeFetchResponseWire] = deriveEncoder

/** Wire format for a paginated proposal fetch request.
  *
  * @param blockId
  *   hex-encoded block identifier used as the anchor or boundary
  * @param height
  *   string-encoded block height
  * @param limit
  *   maximum number of proposals to return
  */
final case class ProposalPageRequestWire(
    blockId: String,
    height: String,
    limit: Int,
)

/** JSON codec instances for `ProposalPageRequestWire`. */
object ProposalPageRequestWire:
  given Decoder[ProposalPageRequestWire] = deriveDecoder
  given Encoder[ProposalPageRequestWire] = deriveEncoder

/** Wire format for a batch of proposals returned from replay or backfill requests.
  *
  * @param proposalsBase64Url
  *   Base64URL-encoded serialized proposals
  */
final case class ProposalBatchResponseWire(
    proposalsBase64Url: Vector[String],
)

/** JSON codec instances for `ProposalBatchResponseWire`. */
object ProposalBatchResponseWire:
  given Decoder[ProposalBatchResponseWire] = deriveDecoder
  given Encoder[ProposalBatchResponseWire] = deriveEncoder

/** Transport-level limits for HotStuff bootstrap requests. */
object HotStuffBootstrapTransportLimits:
  /** Maximum number of snapshot node hashes allowed per fetch request. */
  val MaxSnapshotNodeHashes: Int = 256

  /** Maximum page size for proposal replay and backfill requests. */
  val MaxProposalPageLimit: Int  = 256
