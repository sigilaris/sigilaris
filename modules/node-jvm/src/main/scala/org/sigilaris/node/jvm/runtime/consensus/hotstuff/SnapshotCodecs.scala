package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteDecoder.{BigNat as DecoderBigNat}
import org.sigilaris.core.codec.OrderedCodec.orderedByteVector
import org.sigilaris.core.crypto.Signature
import org.sigilaris.core.datatype.{BigNat as DataBigNat, UInt256, Utf8}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.runtime.block.{
  BlockHeader,
  BlockHeight,
  BlockId,
  BlockTimestamp,
  BodyRoot,
  StateRoot,
}
import org.sigilaris.node.gossip.{ChainId, StableArtifactId}

given ByteDecoder[ChainId] =
  ByteDecoder[Utf8].emap: utf8 =>
    ChainId.parse(utf8.asString).left.map(DecodeFailure(_))

given ByteDecoder[ValidatorId] =
  ByteDecoder[Utf8].emap: utf8 =>
    ValidatorId.parse(utf8.asString).left.map(DecodeFailure(_))

given ByteDecoder[ProposalId] =
  ByteDecoder[UInt256].map(ProposalId(_))

given ByteDecoder[VoteId] =
  ByteDecoder[UInt256].map(VoteId(_))

given ByteDecoder[ValidatorSetHash] =
  ByteDecoder[UInt256].map(ValidatorSetHash(_))

given ByteDecoder[HotStuffHeight] =
  ByteDecoder[DataBigNat].map(HotStuffHeight(_))

given ByteDecoder[HotStuffView] =
  ByteDecoder[DataBigNat].map(HotStuffView(_))

given ByteDecoder[HotStuffWindow] = ByteDecoder.derived

given ByteDecoder[BlockId] =
  ByteDecoder[UInt256].map(BlockId(_))

given ByteDecoder[BlockHeight] =
  ByteDecoder[DataBigNat].map(BlockHeight(_))

given ByteDecoder[StateRoot] =
  ByteDecoder[UInt256].map(StateRoot(_))

given ByteDecoder[BodyRoot] =
  ByteDecoder[UInt256].map(BodyRoot(_))

given ByteDecoder[BlockTimestamp] =
  ByteDecoder[Long].emap: value =>
    BlockTimestamp.fromEpochMillis(value).left.map(DecodeFailure(_))

given [A: ByteDecoder]: ByteDecoder[Vector[A]] =
  ByteDecoder[DecoderBigNat]
    .flatMap(ByteDecoder.sizedListDecoder[A])
    .map(_.toVector)

given ByteDecoder[StableArtifactId] =
  ByteDecoder[scodec.bits.ByteVector].emap: bytes =>
    StableArtifactId.fromBytes(bytes).left.map(DecodeFailure(_))

given ByteDecoder[Signature] =
  ByteDecoder[Long].flatMap: v =>
    ByteDecoder[UInt256].flatMap: r =>
      ByteDecoder[UInt256].emap: s =>
        Either
          .cond(
            v >= Int.MinValue.toLong && v <= Int.MaxValue.toLong,
            Signature(v = v.toInt, r = r, s = s),
            DecodeFailure("signature.v out of Int range: " + v.toString),
          )

given ByteDecoder[QuorumCertificateSubject] = ByteDecoder.derived
given ByteDecoder[Vote]                     = ByteDecoder.derived
given ByteDecoder[QuorumCertificate]        = ByteDecoder.derived
given ByteDecoder[ProposalTxSet] =
  val proposalTxIdDecoder =
    ByteDecoder
      .fromFixedSizeBytes[ByteVector](UInt256.Size.toLong)(identity)
      .emap(bytes =>
        StableArtifactId.fromBytes(bytes).left.map(DecodeFailure(_)),
      )
  ByteDecoder[DecoderBigNat]
    .flatMap: size =>
      given ByteDecoder[StableArtifactId] = proposalTxIdDecoder
      ByteDecoder.sizedListDecoder[StableArtifactId](size)
    .map(txIds => ProposalTxSet(txIds.toVector))
given ByteDecoder[BlockHeader] = ByteDecoder.derived
given ByteDecoder[Proposal]    = ByteDecoder.derived

given ByteEncoder[SnapshotStatus] =
  ByteEncoder[Utf8].contramap:
    case SnapshotStatus.Pending  => Utf8("pending")
    case SnapshotStatus.Syncing  => Utf8("syncing")
    case SnapshotStatus.Complete => Utf8("complete")
    case SnapshotStatus.Failed   => Utf8("failed")

given ByteDecoder[SnapshotStatus] =
  ByteDecoder[Utf8].emap: status =>
    val decoded: Either[DecodeFailure, SnapshotStatus] =
      status.asString match
        case "pending" =>
          Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Pending)
        case "syncing" =>
          Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Syncing)
        case "complete" =>
          Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Complete)
        case "failed" =>
          Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Failed)
        case other =>
          Left[DecodeFailure, SnapshotStatus](
            DecodeFailure("unknown snapshot status: " + other),
          )
    decoded

given ByteEncoder[SnapshotAnchor] = ByteEncoder.derived
given ByteDecoder[SnapshotAnchor] = ByteDecoder.derived

given ByteEncoder[SnapshotMetadata] = ByteEncoder.derived
given ByteDecoder[SnapshotMetadata] = ByteDecoder.derived

given ByteEncoder[FinalizedProof] = ByteEncoder.derived
given ByteDecoder[FinalizedProof] = ByteDecoder.derived

given ByteEncoder[FinalizedAnchorSuggestion] = ByteEncoder.derived
given ByteDecoder[FinalizedAnchorSuggestion] = ByteDecoder.derived
