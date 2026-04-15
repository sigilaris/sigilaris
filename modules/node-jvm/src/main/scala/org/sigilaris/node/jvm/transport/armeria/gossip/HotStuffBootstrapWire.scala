package org.sigilaris.node.jvm.transport.armeria.gossip

import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.{BigNat, UInt256}
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.gossip.CanonicalRejection
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}

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

  /** Parses a typed snapshot fetch request from the wire payload. */
  def parse(
      request: SnapshotNodeFetchRequestWire,
  ): Either[
    CanonicalRejection.BackfillUnavailable,
    SnapshotNodeFetchRequest,
  ] =
    for
      stateRoot <- StateRoot
        .fromHex(request.stateRoot)
        .leftMap(bootstrapRejected("invalidStateRoot", _))
      _ <- Either.cond(
        request.hashes.sizeIs <= HotStuffBootstrapTransportLimits.MaxSnapshotNodeHashes,
        (),
        bootstrapRejected(
          "bootstrapRequestTooLarge",
          ss"max=${HotStuffBootstrapTransportLimits.MaxSnapshotNodeHashes.toString} actual=${request.hashes.size.toString}",
        ),
      )
      hashes <- request.hashes.traverse: hash =>
        UInt256
          .fromHex(hash)
          .leftMap(error => bootstrapRejected("invalidSnapshotNodeHash", error.toString))
          .map(Hash.Value[org.sigilaris.core.merkle.MerkleTrieNode](_))
    yield SnapshotNodeFetchRequest(
      stateRoot = stateRoot,
      hashes = hashes,
    )

/** Typed bootstrap snapshot fetch request. */
final case class SnapshotNodeFetchRequest(
    stateRoot: StateRoot,
    hashes: Vector[org.sigilaris.core.merkle.MerkleTrieNode.MerkleHash],
)

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

  /** Parses a replay request using replay-specific rejection reasons. */
  def parseReplay(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection.BackfillUnavailable, ProposalPageRequest] =
    parse(
      request = request,
      blockIdReason = "invalidAnchorBlockId",
      heightReason = "invalidReplayHeight",
    )

  /** Parses a historical backfill request using backfill-specific rejection reasons. */
  def parseHistoricalBackfill(
      request: ProposalPageRequestWire,
  ): Either[CanonicalRejection.BackfillUnavailable, ProposalPageRequest] =
    parse(
      request = request,
      blockIdReason = "invalidBeforeBlockId",
      heightReason = "invalidBeforeHeight",
    )

  private def parse(
      request: ProposalPageRequestWire,
      blockIdReason: String,
      heightReason: String,
  ): Either[CanonicalRejection.BackfillUnavailable, ProposalPageRequest] =
    for
      blockId <- BlockId
        .fromHex(request.blockId)
        .leftMap(bootstrapRejected(blockIdReason, _))
      height <- parseBlockHeight(request.height, heightReason)
      _ <- Either.cond(
        request.limit >= 0 &&
          request.limit <= HotStuffBootstrapTransportLimits.MaxProposalPageLimit,
        (),
        bootstrapRejected(
          "bootstrapRequestTooLarge",
          ss"max=${HotStuffBootstrapTransportLimits.MaxProposalPageLimit.toString} actual=${request.limit.toString}",
        ),
      )
    yield ProposalPageRequest(
      blockId = blockId,
      height = height,
      limit = request.limit,
    )

  private def parseBlockHeight(
      value: String,
      reason: String,
  ): Either[CanonicalRejection.BackfillUnavailable, BlockHeight] =
    Either
      .catchNonFatal(BigInt(value))
      .leftMap(_ => bootstrapRejected(reason, value))
      .flatMap(bigInt =>
        BigNat
          .fromBigInt(bigInt)
          .leftMap(bootstrapRejected(reason, _))
          .map(BlockHeight(_)),
      )

/** Typed bootstrap proposal-page request. */
final case class ProposalPageRequest(
    blockId: BlockId,
    height: BlockHeight,
    limit: Int,
)

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

private def bootstrapRejected(
    reason: String,
    detail: String,
): CanonicalRejection.BackfillUnavailable =
  CanonicalRejection.BackfillUnavailable(
    reason = reason,
    detail = Some(detail),
  )
