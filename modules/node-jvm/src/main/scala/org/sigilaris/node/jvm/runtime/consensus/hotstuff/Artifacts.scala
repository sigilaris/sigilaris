package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, KeyPair, PublicKey, Signature}
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.gossip.ChainId

given ByteEncoder[ChainId] = ByteEncoder[Utf8].contramap(chainId => Utf8(chainId.value))
given ByteEncoder[HotStuffWindow] = ByteEncoder.derived
given ByteEncoder[Signature] = signature =>
  ByteEncoder[Long].encode(signature.v.toLong) ++ signature.r.bytes ++ signature.s.bytes
given [A: ByteEncoder]: ByteEncoder[Vector[A]] = ByteEncoder[List[A]].contramap(_.toList)

final case class HotStuffValidationFailure(
    reason: String,
    detail: Option[String] = None,
)

final case class ValidatorMember(
    id: ValidatorId,
    publicKey: PublicKey,
) derives ByteEncoder

final case class ValidatorSet(
    members: Vector[ValidatorMember],
):
  require(members.nonEmpty, "validator set must not be empty")
  require(members.map(_.id).distinct.size == members.size, "validator ids must be unique")
  require(members.map(_.publicKey).distinct.size == members.size, "validator public keys must be unique")

  private val membersById: Map[ValidatorId, ValidatorMember] =
    members.map(member => member.id -> member).toMap

  lazy val hash: ValidatorSetHash =
    ValidatorSetHash(HotStuffCanonicalEncoding.validatorSetHash(this))

  def member(
      validatorId: ValidatorId,
  ): Option[ValidatorMember] =
    membersById.get(validatorId)

  def contains(
      validatorId: ValidatorId,
  ): Boolean =
    membersById.contains(validatorId)

  def quorumSize: Int =
    HotStuffPolicy.quorumSize(members.size)

final case class Block(
    parent: Option[BlockId],
    payloadHash: UInt256,
) derives ByteEncoder

object Block:
  def computeId(
      block: Block,
  ): BlockId =
    BlockId(HotStuffCanonicalEncoding.hashEncoded(block))

final case class QuorumCertificateSubject(
    window: HotStuffWindow,
    proposalId: ProposalId,
    blockId: BlockId,
) derives ByteEncoder

final case class UnsignedVote(
    window: HotStuffWindow,
    voter: ValidatorId,
    targetProposalId: ProposalId,
)

final case class Vote(
    voteId: VoteId,
    window: HotStuffWindow,
    voter: ValidatorId,
    targetProposalId: ProposalId,
    signature: Signature,
) derives ByteEncoder:
  def equivocationKey: EquivocationKey =
    EquivocationKey(
      chainId = window.chainId,
      validatorId = voter,
      height = window.height,
      view = window.view,
    )

object Vote:
  def sign(
      unsigned: UnsignedVote,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Vote] =
    HotStuffCanonicalEncoding.sign(HotStuffCanonicalEncoding.voteSignBytes(unsigned), keyPair).map:
      signature =>
        val voteId =
          VoteId(
            HotStuffCanonicalEncoding.voteId(
              window = unsigned.window,
              voter = unsigned.voter,
              targetProposalId = unsigned.targetProposalId,
              signature = signature,
            )
          )
        Vote(
          voteId = voteId,
          window = unsigned.window,
          voter = unsigned.voter,
          targetProposalId = unsigned.targetProposalId,
          signature = signature,
        )

  def signBytes(
      unsigned: UnsignedVote,
  ): ByteVector =
    HotStuffCanonicalEncoding.voteSignBytes(unsigned)

  def recomputeId(
      vote: Vote,
  ): VoteId =
    VoteId(
      HotStuffCanonicalEncoding.voteId(
        window = vote.window,
        voter = vote.voter,
        targetProposalId = vote.targetProposalId,
        signature = vote.signature,
      )
    )

final case class QuorumCertificate(
    subject: QuorumCertificateSubject,
    votes: Vector[Vote],
) derives ByteEncoder

final case class UnsignedProposal(
    window: HotStuffWindow,
    proposer: ValidatorId,
    targetBlockId: BlockId,
    block: Block,
    justify: QuorumCertificate,
)

final case class Proposal(
    proposalId: ProposalId,
    window: HotStuffWindow,
    proposer: ValidatorId,
    targetBlockId: BlockId,
    block: Block,
    justify: QuorumCertificate,
    signature: Signature,
) derives ByteEncoder

object Proposal:
  def sign(
      unsigned: UnsignedProposal,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Proposal] =
    HotStuffCanonicalEncoding.sign(HotStuffCanonicalEncoding.proposalSignBytes(unsigned), keyPair).map:
      signature =>
        val proposalId =
          ProposalId(
            HotStuffCanonicalEncoding.proposalId(
              window = unsigned.window,
              proposer = unsigned.proposer,
              targetBlockId = unsigned.targetBlockId,
              block = unsigned.block,
              justify = unsigned.justify,
              signature = signature,
            )
          )
        Proposal(
          proposalId = proposalId,
          window = unsigned.window,
          proposer = unsigned.proposer,
          targetBlockId = unsigned.targetBlockId,
          block = unsigned.block,
          justify = unsigned.justify,
          signature = signature,
        )

  def signBytes(
      unsigned: UnsignedProposal,
  ): ByteVector =
    HotStuffCanonicalEncoding.proposalSignBytes(unsigned)

  def recomputeId(
      proposal: Proposal,
  ): ProposalId =
    ProposalId(
      HotStuffCanonicalEncoding.proposalId(
        window = proposal.window,
        proposer = proposal.proposer,
        targetBlockId = proposal.targetBlockId,
        block = proposal.block,
        justify = proposal.justify,
        signature = proposal.signature,
      )
    )

object HotStuffCanonicalEncoding:
  private val ProposalSignDomain: Utf8 = Utf8("sigilaris.hotstuff.proposal.sign.v1")
  private val VoteSignDomain: Utf8 = Utf8("sigilaris.hotstuff.vote.sign.v1")
  private val ProposalIdentityDomain: Utf8 = Utf8("sigilaris.hotstuff.proposal.id.v1")
  private val VoteIdentityDomain: Utf8 = Utf8("sigilaris.hotstuff.vote.id.v1")
  private val ValidatorSetDomain: Utf8 = Utf8("sigilaris.hotstuff.validator-set.v1")

  private final case class ValidatorSetHashInput(
      domain: Utf8,
      members: Vector[ValidatorMember],
  ) derives ByteEncoder

  private final case class ProposalSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: Long,
      view: Long,
      proposer: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetBlockId: BlockId,
      justifySubject: QuorumCertificateSubject,
  ) derives ByteEncoder

  private final case class VoteSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: Long,
      view: Long,
      voter: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetProposalId: ProposalId,
  ) derives ByteEncoder

  private final case class ProposalIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: Long,
      view: Long,
      proposer: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetBlockId: BlockId,
      block: Block,
      justify: QuorumCertificate,
      signature: Signature,
  ) derives ByteEncoder

  private final case class VoteIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: Long,
      view: Long,
      voter: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetProposalId: ProposalId,
      signature: Signature,
  ) derives ByteEncoder

  def hashEncoded[A: ByteEncoder](
      value: A,
  ): UInt256 =
    UInt256.unsafeFromBytesBE(ByteVector.view(CryptoOps.keccak256(value.toBytes.toArray)))

  def validatorSetHash(
      validatorSet: ValidatorSet,
  ): UInt256 =
    hashEncoded(
      ValidatorSetHashInput(
        domain = ValidatorSetDomain,
        members = validatorSet.members.sortBy(_.id.value),
      )
    )

  def proposalSignBytes(
      unsigned: UnsignedProposal,
  ): ByteVector =
    ProposalSignInput(
      domain = ProposalSignDomain,
      chainId = unsigned.window.chainId,
      height = unsigned.window.height,
      view = unsigned.window.view,
      proposer = unsigned.proposer,
      validatorSetHash = unsigned.window.validatorSetHash,
      targetBlockId = unsigned.targetBlockId,
      justifySubject = unsigned.justify.subject,
    ).toBytes

  def voteSignBytes(
      unsigned: UnsignedVote,
  ): ByteVector =
    VoteSignInput(
      domain = VoteSignDomain,
      chainId = unsigned.window.chainId,
      height = unsigned.window.height,
      view = unsigned.window.view,
      voter = unsigned.voter,
      validatorSetHash = unsigned.window.validatorSetHash,
      targetProposalId = unsigned.targetProposalId,
    ).toBytes

  def proposalId(
      window: HotStuffWindow,
      proposer: ValidatorId,
      targetBlockId: BlockId,
      block: Block,
      justify: QuorumCertificate,
      signature: Signature,
  ): UInt256 =
    hashEncoded(
      ProposalIdentityInput(
        domain = ProposalIdentityDomain,
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        proposer = proposer,
        validatorSetHash = window.validatorSetHash,
        targetBlockId = targetBlockId,
        block = block,
        justify = canonicalizeQuorumCertificate(justify),
        signature = signature,
      )
    )

  def voteId(
      window: HotStuffWindow,
      voter: ValidatorId,
      targetProposalId: ProposalId,
      signature: Signature,
  ): UInt256 =
    hashEncoded(
      VoteIdentityInput(
        domain = VoteIdentityDomain,
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        voter = voter,
        validatorSetHash = window.validatorSetHash,
        targetProposalId = targetProposalId,
        signature = signature,
      )
    )

  def sign(
      signBytes: ByteVector,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Signature] =
    val messageHash = UInt256.unsafeFromBytesBE(ByteVector.view(CryptoOps.keccak256(signBytes.toArray)))
    CryptoOps
      .sign(keyPair, messageHash.bytes.toArray)
      .leftMap(error =>
        HotStuffValidationFailure(
          reason = "signatureCreationFailed",
          detail = Some(error.toString),
        )
      )

  private def canonicalizeQuorumCertificate(
      quorumCertificate: QuorumCertificate,
  ): QuorumCertificate =
    quorumCertificate.copy(
      votes =
        quorumCertificate.votes
          // Pre-sort so duplicate-voter collapse keeps a deterministic
          // representative before we do the final canonical ordering pass.
          .sortBy(vote => (vote.voter.value, vote.voteId.toHexLower))
          .groupBy(_.voter)
          .values
          .map(_.head)
          .toVector
          .sortBy(vote => (vote.voter.value, vote.voteId.toHexLower)),
    )
