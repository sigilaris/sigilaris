package org.sigilaris.node.jvm.runtime.gossip.tx

import scala.util.hashing.MurmurHash3

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.runtime.gossip.*

@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
object TxBloomFilterSupport:
  val SupportedHashFamilyId: String = "murmur3-32"

  def validate(
      filter: GossipFilter.TxBloomFilter,
      policy: TxRuntimePolicy,
  ): Either[
    CanonicalRejection.ControlBatchRejected,
    GossipFilter.TxBloomFilter,
  ] =
    Either
      .cond(
        filter.bitset.nonEmpty,
        (),
        CanonicalRejection.ControlBatchRejected(
          reason = "invalidFilter",
          detail = Some("bitset must not be empty"),
        ),
      )
      .flatMap: _ =>
        Either.cond(
          filter.numHashes > 0,
          (),
          CanonicalRejection.ControlBatchRejected(
            reason = "invalidFilter",
            detail = Some("numHashes must be positive"),
          ),
        )
      .flatMap: _ =>
        Either.cond(
          filter.hashFamilyId === policy.supportedBloomHashFamilyId,
          filter,
          CanonicalRejection.ControlBatchRejected(
            reason = "unsupportedFilterFamily",
            detail = Some(filter.hashFamilyId),
          ),
        )

  def build(
      ids: Iterable[StableArtifactId],
      bitsetBytes: Int,
      numHashes: Int,
      hashFamilyId: String = SupportedHashFamilyId,
  ): GossipFilter.TxBloomFilter =
    val initial = Array.fill[Byte](bitsetBytes.max(1))(0)
    val filter = GossipFilter.TxBloomFilter(
      bitset = ByteVector.view(initial),
      numHashes = numHashes.max(1),
      hashFamilyId = hashFamilyId,
    )
    val populated = ids.foldLeft(initial): (acc, id) =>
      indexes(filter, id).foreach: index =>
        val byteIndex = index / 8
        val bitIndex  = index % 8
        acc(byteIndex) = (acc(byteIndex) | (1 << bitIndex)).toByte
      acc
    filter.copy(bitset = ByteVector.view(populated))

  def mightContain(
      filter: GossipFilter.TxBloomFilter,
      id: StableArtifactId,
  ): Boolean =
    indexes(filter, id).forall: index =>
      val byteIndex = index / 8
      val bitIndex  = index % 8
      ((filter.bitset(byteIndex) >> bitIndex) & 1.toByte) === 1.toByte

  private def indexes(
      filter: GossipFilter.TxBloomFilter,
      id: StableArtifactId,
  ): Vector[Int] =
    val bitSize = (filter.bitset.size.toInt max 1) * 8
    (0 until filter.numHashes).toVector.map: seedIndex =>
      val seed =
        MurmurHash3.stringHash(
          ss"${filter.hashFamilyId}:${seedIndex.toString}",
        )
      Math.floorMod(MurmurHash3.bytesHash(id.bytes.toArray, seed), bitSize)
