package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.util.Arrays

import scala.collection.immutable.{HashSet, VectorMap}

import cats.Eq
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.codec.OrderedCodec.orderedByteVector
import org.sigilaris.core.crypto.{
  CryptoOps,
  Hash,
  KeyPair,
  PublicKey,
  Signature,
}
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.node.jvm.runtime.block.BlockHeader
import org.sigilaris.node.jvm.runtime.gossip.{ChainId, StableArtifactId}

given ByteEncoder[ChainId] =
  ByteEncoder[Utf8].contramap(chainId => Utf8(chainId.value))
given ByteEncoder[StableArtifactId] =
  ByteEncoder[ByteVector].contramap(_.bytes)
given ByteEncoder[HotStuffWindow] = ByteEncoder.derived
given ByteEncoder[Signature] = signature =>
  ByteEncoder[Long].encode(signature.v.toLong) ++
    signature.r.bytes ++ signature.s.bytes
given [A: ByteEncoder]: ByteEncoder[Vector[A]] =
  ByteEncoder[List[A]].contramap(_.toList)

final case class HotStuffValidationFailure(
    reason: String,
    detail: Option[String],
)

object HotStuffValidationFailure:
  def withoutDetail(
      reason: String,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(reason = reason, detail = None)

final case class ValidatorMember(
    id: ValidatorId,
    publicKey: PublicKey,
) derives ByteEncoder

enum ValidatorSetError:
  case Empty
  case DuplicateIds
  case DuplicatePublicKeys

object ValidatorSetError:
  extension (error: ValidatorSetError)
    def message: String =
      error match
        case ValidatorSetError.Empty =>
          "validator set must not be empty"
        case ValidatorSetError.DuplicateIds =>
          "validator ids must be unique"
        case ValidatorSetError.DuplicatePublicKeys =>
          "validator public keys must be unique"

final class ValidatorSet private (
    private val membersById: VectorMap[ValidatorId, ValidatorMember],
):
  def members: Vector[ValidatorMember] =
    membersById.valuesIterator.toVector

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
    HotStuffPolicy.quorumSize(membersById.size)

@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ValidatorSet:
  def apply(
      members: Vector[ValidatorMember],
  ): Either[ValidatorSetError, ValidatorSet] =
    for
      _ <- Either.cond(
        members.nonEmpty,
        (),
        ValidatorSetError.Empty,
      )
      _ <- Either.cond(
        members.map(_.id).distinct.sizeCompare(members) match
          case 0 => true
          case _ => false
        ,
        (),
        ValidatorSetError.DuplicateIds,
      )
      _ <- Either.cond(
        members.map(_.publicKey).distinct.sizeCompare(members) match
          case 0 => true
          case _ => false
        ,
        (),
        ValidatorSetError.DuplicatePublicKeys,
      )
    yield new ValidatorSet(
      VectorMap.from(
        members.iterator.map(member => member.id -> member),
      ),
    )

  def unsafe(
      members: Vector[ValidatorMember],
  ): ValidatorSet =
    apply(members) match
      case Right(validatorSet) => validatorSet
      case Left(error) => throw new IllegalArgumentException(error.message)

final case class QuorumCertificateSubject(
    window: HotStuffWindow,
    proposalId: ProposalId,
    blockId: BlockId,
) derives ByteEncoder

object QuorumCertificateSubject:
  given Eq[QuorumCertificateSubject] = Eq.fromUniversalEquals

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
    HotStuffCanonicalEncoding
      .sign(HotStuffCanonicalEncoding.voteSignBytes(unsigned), keyPair)
      .map: signature =>
        val voteId =
          VoteId:
            HotStuffCanonicalEncoding.voteId(
              window = unsigned.window,
              voter = unsigned.voter,
              targetProposalId = unsigned.targetProposalId,
              signature = signature,
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
    VoteId:
      HotStuffCanonicalEncoding.voteId(
        window = vote.window,
        voter = vote.voter,
        targetProposalId = vote.targetProposalId,
        signature = vote.signature,
      )

final case class QuorumCertificate(
    subject: QuorumCertificateSubject,
    votes: Vector[Vote],
) derives ByteEncoder

final case class ProposalTxSet(
    txIds: Vector[StableArtifactId],
)

object ProposalTxSet:
  given Eq[ProposalTxSet] = Eq.by(_.txIds)
  given ByteEncoder[ProposalTxSet] =
    txSet =>
      val firstUnsupported = firstUnsupportedTxId(txSet)
      // Proposal.sign/validation reject unsupported ids before this encoder runs,
      // but ByteEncoder cannot surface a structured failure so keep a defensive
      // invariant here in case a non-wire-compatible tx-set reaches snapshot I/O.
      require(
        firstUnsupported.isEmpty,
        "proposal tx-set contains tx ids that are not fixed-width wire compatible" +
          firstUnsupported.fold("")(txId =>
            ": firstUnsupportedTxIdSize=" + txId.bytes.size.toString,
          ),
      )
      // Preserve the legacy fixed-width wire format:
      // [count][32-byte tx id][32-byte tx id]...
      txSet.txIds.foldLeft(
        ByteEncoder[ByteEncoder.BigNat]
          .encode(BigInt(txSet.txIds.size).refineUnsafe[Positive0]),
      ):
        case (encoded, txId) =>
          encoded ++ txId.bytes

  val empty: ProposalTxSet = ProposalTxSet(Vector.empty)

  def canonical(
      txSet: ProposalTxSet,
  ): ProposalTxSet =
    if isCanonical(txSet) then txSet
    else ProposalTxSet(canonicalize(txSet.txIds))

  def canonicalize(
      txIds: Iterable[StableArtifactId],
  ): Vector[StableArtifactId] =
    val (_, deduped) =
      txIds.iterator.foldLeft(
        (HashSet.empty[StableArtifactId], Vector.empty[StableArtifactId]),
      ):
        case ((seen, acc), txId) if seen.contains(txId) =>
          (seen, acc)
        case ((seen, acc), txId) =>
          ((seen + txId), (acc :+ txId))
    deduped
      .sortWith: (left, right) =>
        compareCanonicalOrder(left, right) < 0

  def fromTxs[TxRef: Hash](
      txs: Iterable[TxRef],
  ): ProposalTxSet =
    ProposalTxSet(
      canonicalize(
        txs.iterator
          .map: tx =>
            StableArtifactId.unsafeFromBytes(tx.toHash.toUInt256.bytes)
          .toVector,
      ),
    )

  def isCanonical(
      txSet: ProposalTxSet,
  ): Boolean =
    txSet.txIds.iterator
      .foldLeft(
        (Option.empty[StableArtifactId], true),
      ):
        case ((previous, false), _) =>
          (previous, false)
        case ((None, true), txId) =>
          (txId.some, true)
        case ((Some(previous), true), txId) =>
          (txId.some, compareCanonicalOrder(previous, txId) < 0)
      ._2

  def firstUnsupportedTxId(
      txSet: ProposalTxSet,
  ): Option[StableArtifactId] =
    // General gossip artifact ids are variable-width, but consensus proposal
    // snapshots and wire payloads need a fixed-width tx-set encoding so
    // non-empty proposal tx-sets are restricted to 32-byte hash ids.
    txSet.txIds.find(_.bytes.size =!= UInt256.Size.toLong)

  private def compareCanonicalOrder(
      left: StableArtifactId,
      right: StableArtifactId,
  ): Int =
    Arrays.compareUnsigned(
      left.bytes.toArray,
      right.bytes.toArray,
    )

final case class UnsignedProposal(
    window: HotStuffWindow,
    proposer: ValidatorId,
    targetBlockId: BlockId,
    block: BlockHeader,
    txSet: ProposalTxSet,
    justify: QuorumCertificate,
)

final case class Proposal(
    proposalId: ProposalId,
    window: HotStuffWindow,
    proposer: ValidatorId,
    targetBlockId: BlockId,
    block: BlockHeader,
    txSet: ProposalTxSet,
    justify: QuorumCertificate,
    signature: Signature,
) derives ByteEncoder

object Proposal:
  def sign(
      unsigned: UnsignedProposal,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Proposal] =
    val canonicalUnsigned = normalizeUnsignedProposal(unsigned)
    ProposalTxSet
      .firstUnsupportedTxId(canonicalUnsigned.txSet)
      .fold(().asRight[HotStuffValidationFailure]): txId =>
        HotStuffValidationFailure(
          reason = "proposalTxIdUnsupported",
          detail = Some(txId.toHexLower),
        ).asLeft[Unit]
      .flatMap: _ =>
        HotStuffCanonicalEncoding
          .sign(
            HotStuffCanonicalEncoding.proposalSignBytes(canonicalUnsigned),
            keyPair,
          )
          .map: signature =>
            val proposalId =
              ProposalId:
                HotStuffCanonicalEncoding.proposalId(
                  window = canonicalUnsigned.window,
                  proposer = canonicalUnsigned.proposer,
                  targetBlockId = canonicalUnsigned.targetBlockId,
                  block = canonicalUnsigned.block,
                  txSet = canonicalUnsigned.txSet,
                  justify = canonicalUnsigned.justify,
                  signature = signature,
                )
            Proposal(
              proposalId = proposalId,
              window = canonicalUnsigned.window,
              proposer = canonicalUnsigned.proposer,
              targetBlockId = canonicalUnsigned.targetBlockId,
              block = canonicalUnsigned.block,
              txSet = canonicalUnsigned.txSet,
              justify = canonicalUnsigned.justify,
              signature = signature,
            )

  def signBytes(
      unsigned: UnsignedProposal,
  ): ByteVector =
    HotStuffCanonicalEncoding.proposalSignBytes(
      normalizeUnsignedProposal(unsigned),
    )

  def recomputeId(
      proposal: Proposal,
  ): ProposalId =
    val canonicalProposal = normalizeProposal(proposal)
    ProposalId:
      HotStuffCanonicalEncoding.proposalId(
        window = canonicalProposal.window,
        proposer = canonicalProposal.proposer,
        targetBlockId = canonicalProposal.targetBlockId,
        block = canonicalProposal.block,
        txSet = canonicalProposal.txSet,
        justify = canonicalProposal.justify,
        signature = canonicalProposal.signature,
      )

  private def normalizeUnsignedProposal(
      unsigned: UnsignedProposal,
  ): UnsignedProposal =
    unsigned.copy(txSet = ProposalTxSet.canonical(unsigned.txSet))

  private def normalizeProposal(
      proposal: Proposal,
  ): Proposal =
    proposal.copy(txSet = ProposalTxSet.canonical(proposal.txSet))

object HotStuffCanonicalEncoding:
  private val ProposalSignDomain: Utf8 = Utf8:
    "sigilaris.hotstuff.proposal.sign.v1"
  private val VoteSignDomain: Utf8 = Utf8("sigilaris.hotstuff.vote.sign.v1")
  private val ProposalIdentityDomain: Utf8 = Utf8:
    "sigilaris.hotstuff.proposal.id.v1"
  private val VoteIdentityDomain: Utf8 = Utf8("sigilaris.hotstuff.vote.id.v1")
  private val ValidatorSetDomain: Utf8 = Utf8:
    "sigilaris.hotstuff.validator-set.v1"

  private final case class ValidatorSetHashInput(
      domain: Utf8,
      members: Vector[ValidatorMember],
  ) derives ByteEncoder

  private final case class ProposalSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      proposer: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetBlockId: BlockId,
      txSet: ProposalTxSet,
      justifySubject: QuorumCertificateSubject,
  ) derives ByteEncoder

  private final case class VoteSignInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      voter: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetProposalId: ProposalId,
  ) derives ByteEncoder

  private final case class ProposalIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      proposer: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetBlockId: BlockId,
      block: BlockHeader,
      txSet: ProposalTxSet,
      justify: QuorumCertificate,
      signature: Signature,
  ) derives ByteEncoder

  private final case class VoteIdentityInput(
      domain: Utf8,
      chainId: ChainId,
      height: HotStuffHeight,
      view: HotStuffView,
      voter: ValidatorId,
      validatorSetHash: ValidatorSetHash,
      targetProposalId: ProposalId,
      signature: Signature,
  ) derives ByteEncoder

  def hashEncoded[A: ByteEncoder](
      value: A,
  ): UInt256 =
    UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(value.toBytes.toArray))

  def validatorSetHash(
      validatorSet: ValidatorSet,
  ): UInt256 =
    hashEncoded:
      ValidatorSetHashInput(
        domain = ValidatorSetDomain,
        members = validatorSet.members.sortBy(_.id.value),
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
      txSet = unsigned.txSet,
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
      block: BlockHeader,
      txSet: ProposalTxSet,
      justify: QuorumCertificate,
      signature: Signature,
  ): UInt256 =
    hashEncoded:
      ProposalIdentityInput(
        domain = ProposalIdentityDomain,
        chainId = window.chainId,
        height = window.height,
        view = window.view,
        proposer = proposer,
        validatorSetHash = window.validatorSetHash,
        targetBlockId = targetBlockId,
        block = block,
        txSet = txSet,
        justify = canonicalizeQuorumCertificate(justify),
        signature = signature,
      )

  def voteId(
      window: HotStuffWindow,
      voter: ValidatorId,
      targetProposalId: ProposalId,
      signature: Signature,
  ): UInt256 =
    hashEncoded:
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

  def sign(
      signBytes: ByteVector,
      keyPair: KeyPair,
  ): Either[HotStuffValidationFailure, Signature] =
    val messageHash = UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(signBytes.toArray))
    CryptoOps
      .sign(keyPair, messageHash.bytes.toArray)
      .leftMap(error =>
        HotStuffValidationFailure(
          reason = "signatureCreationFailed",
          detail = Some(error.toString),
        ),
      )

  private def canonicalizeQuorumCertificate(
      quorumCertificate: QuorumCertificate,
  ): QuorumCertificate =
    quorumCertificate.copy(
      votes = quorumCertificate.votes
        // QC canonicalization needs a deterministic vote ordering for hashing
        // and encoding. If a caller hands us repeated votes for the same
        // voter, we collapse them here only to stabilize the canonical byte
        // form; admissibility still comes from assembler/validator checks,
        // which reject duplicate-validator vote sets.
        // Pre-sort so duplicate-voter collapse keeps a deterministic
        // representative before we do the final canonical ordering pass.
        .sortBy(vote => (vote.voter.value, vote.voteId.toHexLower))
        .groupBy(_.voter)
        .valuesIterator
        .flatMap(_.headOption)
        .toVector
        .sortBy(vote => (vote.voter.value, vote.voteId.toHexLower)),
    )
