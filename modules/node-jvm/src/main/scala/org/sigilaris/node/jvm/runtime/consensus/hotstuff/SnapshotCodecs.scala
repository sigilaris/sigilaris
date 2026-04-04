package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.byte.ByteDecoder.{BigNat as DecoderBigNat}
import org.sigilaris.core.datatype.{BigNat as DataBigNat, UInt256, Utf8}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.runtime.block.{BlockHeight, BlockId, StateRoot}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

given ByteDecoder[ChainId] =
  ByteDecoder[Utf8].emap: utf8 =>
    ChainId.parse(utf8.asString).left.map(DecodeFailure(_))

given ByteDecoder[ProposalId] =
  ByteDecoder[UInt256].map(ProposalId(_))

given ByteDecoder[BlockId] =
  ByteDecoder[UInt256].map(BlockId(_))

given ByteDecoder[BlockHeight] =
  ByteDecoder[DataBigNat].map(BlockHeight(_))

given ByteDecoder[StateRoot] =
  ByteDecoder[UInt256].map(StateRoot(_))

given [A: ByteDecoder]: ByteDecoder[Vector[A]] =
  ByteDecoder[DecoderBigNat].flatMap(ByteDecoder.sizedListDecoder[A]).map(_.toVector)

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
        case "pending"  => Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Pending)
        case "syncing"  => Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Syncing)
        case "complete" => Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Complete)
        case "failed"   => Right[DecodeFailure, SnapshotStatus](SnapshotStatus.Failed)
        case other =>
          Left[DecodeFailure, SnapshotStatus](DecodeFailure("unknown snapshot status: " + other))
    decoded

given ByteEncoder[SnapshotAnchor] = ByteEncoder.derived
given ByteDecoder[SnapshotAnchor] = ByteDecoder.derived

given ByteEncoder[SnapshotMetadata] = ByteEncoder.derived
given ByteDecoder[SnapshotMetadata] = ByteDecoder.derived
