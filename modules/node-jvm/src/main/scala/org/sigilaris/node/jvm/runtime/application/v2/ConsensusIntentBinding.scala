package org.sigilaris.node.jvm.runtime.application.v2

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.{BigNat, Utf8}
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffCanonicalEncoding,
  UnsignedVote,
  ValidatorId,
}

import V2Codecs.given

/** Reads the existing HotStuff signed layout to reconstruct durable vote keys.
  * It never defines a new consensus signature format or domain.
  */
private[v2] final case class ConsensusSignFields(
    domain: Text,
    chainId: Text,
    height: BigNat,
    view: BigNat,
    voter: Text,
    validatorSetHash: Hash,
    targetProposalId: Hash,
):
  def equivocationKey: (String, String, BigInt, BigInt) =
    (chainId.asString, voter.asString, height.toBigInt, view.toBigInt)

@SuppressWarnings(Array("org.wartremover.warts.Equals"))
private[v2] object ConsensusSignFields:
  given ByteEncoder[ConsensusSignFields]         = ByteEncoder.derived
  given ByteDecoder[ConsensusSignFields]         = ByteDecoder.derived
  val codec: CanonicalCodec[ConsensusSignFields] = CanonicalCodec.derived:
    value =>
      V2Validation.require(
        value.domain == Utf8("sigilaris.hotstuff.vote.sign.v1") &&
          ChainId.parse(value.chainId.asString).isRight && ValidatorId
            .parse(value.voter.asString)
            .isRight,
        FailureCode.NonCanonicalEncoding,
        "consensusVoteSignBytes",
      )

  def fromUnsigned(
      value: UnsignedVote,
  ): Either[V2RuntimeFailure, ConsensusSignFields] =
    RuntimeCheck.core(
      codec.decode(HotStuffCanonicalEncoding.voteSignBytes(value)),
    )

  def validateIntent(
      value: ConsensusVoteIntent,
  ): Either[V2RuntimeFailure, ConsensusSignFields] =
    for
      _ <- RuntimeCheck.core(
        ConsensusVoteIntent.codec.encode(value).map(_ => ()),
      )
      fields <- RuntimeCheck.core(codec.decode(value.unsignedVoteSignBytes))
      _      <- RuntimeCheck.require(
        fields.chainId == value.context.chainId && fields.voter == value.validatorId &&
          fields.validatorSetHash == value.context.validatorSetHash && fields.targetProposalId == value.proposalId,
        RuntimeFailureCode.DomainMismatch,
        "durable consensus intent differs from its actual unsigned vote signing bytes",
      )
    yield fields
