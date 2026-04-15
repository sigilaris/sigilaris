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
import org.sigilaris.core.failure.{
  FailureDiagnosticFamily,
  StructuredFailureDiagnostic,
}
import org.sigilaris.node.jvm.runtime.block.BlockHeader
import org.sigilaris.node.gossip.{ChainId, StableArtifactId}

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

/** Represents a failure during HotStuff consensus validation.
  *
  * @param reason a short identifier for the validation failure
  * @param detail optional human-readable detail about the failure
  */
final case class HotStuffValidationFailure(
    reason: String,
    detail: Option[String],
) extends StructuredFailureDiagnostic:
  override val diagnosticFamily: FailureDiagnosticFamily =
    FailureDiagnosticFamily.HotStuffValidation

/** Companion for `HotStuffValidationFailure`. */
object HotStuffValidationFailure:

  /** Creates a validation failure without detail.
    *
    * @param reason a short identifier for the failure
    * @return a validation failure with no detail
    */
  def withoutDetail(
      reason: String,
  ): HotStuffValidationFailure =
    HotStuffValidationFailure(reason = reason, detail = None)

/** A member of a validator set, pairing an identity with a cryptographic public key.
  *
  * @param id the validator's unique identity
  * @param publicKey the validator's public key used for signature verification
  */
final case class ValidatorMember(
    id: ValidatorId,
    publicKey: PublicKey,
) derives ByteEncoder

/** Errors that can occur when constructing a `ValidatorSet`. */
enum ValidatorSetError:
  /** The validator set is empty. */
  case Empty
  /** Two or more validators share the same identity. */
  case DuplicateIds
  /** Two or more validators share the same public key. */
  case DuplicatePublicKeys

/** Companion for `ValidatorSetError`. */
object ValidatorSetError:
  extension (error: ValidatorSetError)
    /** Returns a human-readable error message. */
    def message: String =
      error match
        case ValidatorSetError.Empty =>
          "validator set must not be empty"
        case ValidatorSetError.DuplicateIds =>
          "validator ids must be unique"
        case ValidatorSetError.DuplicatePublicKeys =>
          "validator public keys must be unique"

/** An ordered, deduplicated set of consensus validators with quorum calculation support. */
final class ValidatorSet private (
    private val membersById: VectorMap[ValidatorId, ValidatorMember],
):
  /** Returns all validator members in insertion order. */
  def members: Vector[ValidatorMember] =
    membersById.valuesIterator.toVector

  /** The canonical hash of this validator set, computed lazily. */
  lazy val hash: ValidatorSetHash =
    ValidatorSetHash(HotStuffCanonicalEncoding.validatorSetHash(this))

  /** Looks up a validator member by ID.
    *
    * @param validatorId the validator identity to look up
    * @return the member if present
    */
  def member(
      validatorId: ValidatorId,
  ): Option[ValidatorMember] =
    membersById.get(validatorId)

  /** Checks whether the set contains a validator with the given ID.
    *
    * @param validatorId the validator identity to check
    * @return true if the validator is present
    */
  def contains(
      validatorId: ValidatorId,
  ): Boolean =
    membersById.contains(validatorId)

  /** Returns the minimum number of votes needed for a quorum (2f+1 for 3f+1 validators). */
  def quorumSize: Int =
    HotStuffPolicy.validatedQuorumSize(membersById.size)

/** Companion for `ValidatorSet`. */
@SuppressWarnings(Array("org.wartremover.warts.Throw"))
object ValidatorSet:

  /** Constructs a validator set from the given members, validating uniqueness constraints.
    *
    * @param members the validator members
    * @return the validated validator set or an error
    */
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

  /** Constructs a validator set, throwing on validation failure. Intended for tests and bootstrap.
    *
    * @param members the validator members
    * @return the validator set
    */
  def unsafe(
      members: Vector[ValidatorMember],
  ): ValidatorSet =
    apply(members) match
      case Right(validatorSet) => validatorSet
      case Left(error) => throw new IllegalArgumentException(error.message)

/** The subject of a quorum certificate, identifying the proposal being certified.
  *
  * @param window the consensus window in which the proposal was made
  * @param proposalId the certified proposal's unique identifier
  * @param blockId the block ID targeted by the proposal
  */
final case class QuorumCertificateSubject(
    window: HotStuffWindow,
    proposalId: ProposalId,
    blockId: BlockId,
) derives ByteEncoder

/** Companion for `QuorumCertificateSubject`. */
object QuorumCertificateSubject:
  given Eq[QuorumCertificateSubject] = Eq.fromUniversalEquals

/** A vote before signing.
  *
  * @param window the consensus window
  * @param voter the voting validator
  * @param targetProposalId the proposal being voted on
  */
final case class UnsignedVote(
    window: HotStuffWindow,
    voter: ValidatorId,
    targetProposalId: ProposalId,
)

/** A signed consensus vote cast by a validator for a specific proposal.
  *
  * @param voteId unique identifier for this vote
  * @param window the consensus window
  * @param voter the voting validator
  * @param targetProposalId the proposal being voted on
  * @param signature the cryptographic signature
  */
final case class Vote(
    voteId: VoteId,
    window: HotStuffWindow,
    voter: ValidatorId,
    targetProposalId: ProposalId,
    signature: Signature,
) derives ByteEncoder:
  /** Returns the equivocation key used to detect double-voting. */
  def equivocationKey: EquivocationKey =
    EquivocationKey(
      chainId = window.chainId,
      validatorId = voter,
      height = window.height,
      view = window.view,
    )

/** Companion for `Vote`, providing signing and identity operations. */
object Vote:

  /** Signs an unsigned vote using the given key pair.
    *
    * @param unsigned the vote to sign
    * @param keyPair the signer's key pair
    * @return the signed vote or a validation failure
    */
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

  /** Returns the canonical bytes to be signed for the given unsigned vote. */
  def signBytes(
      unsigned: UnsignedVote,
  ): ByteVector =
    HotStuffCanonicalEncoding.voteSignBytes(unsigned)

  /** Recomputes the vote ID from the vote's fields, for verification purposes. */
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

/** A quorum certificate aggregating sufficient votes to certify a proposal.
  *
  * @param subject the proposal subject being certified
  * @param votes the collected validator votes meeting the quorum threshold
  */
final case class QuorumCertificate(
    subject: QuorumCertificateSubject,
    votes: Vector[Vote],
) derives ByteEncoder

/** An ordered set of transaction IDs included in a consensus proposal.
  *
  * @param txIds the transaction identifiers in canonical order
  */
final case class ProposalTxSet(
    txIds: Vector[StableArtifactId],
)

/** Companion for `ProposalTxSet`, providing canonicalization and wire encoding. */
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

  /** An empty proposal transaction set. */
  val empty: ProposalTxSet = ProposalTxSet(Vector.empty)

  /** Returns a canonically ordered copy of the given transaction set. */
  def canonical(
      txSet: ProposalTxSet,
  ): ProposalTxSet =
    if isCanonical(txSet) then txSet
    else ProposalTxSet(canonicalize(txSet.txIds))

  /** Deduplicates and sorts transaction IDs into canonical order. */
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

  /** Creates a canonical proposal tx set by hashing each transaction reference.
    *
    * @tparam TxRef the transaction reference type, which must be hashable
    * @param txs the transactions to include
    * @return a canonically ordered proposal tx set
    */
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

  /** Checks whether the transaction set is already in canonical order with no duplicates. */
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

  /** Returns the first transaction ID that is not fixed-width wire compatible (not 32 bytes). */
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

/** A proposal before signing.
  *
  * @param window the consensus window
  * @param proposer the proposing validator
  * @param targetBlockId the block ID being proposed
  * @param block the block header
  * @param txSet the transaction set included in the proposal
  * @param justify the quorum certificate justifying this proposal
  */
final case class UnsignedProposal(
    window: HotStuffWindow,
    proposer: ValidatorId,
    targetBlockId: BlockId,
    block: BlockHeader,
    txSet: ProposalTxSet,
    justify: QuorumCertificate,
)

/** A signed consensus proposal emitted by a leader validator.
  *
  * @param proposalId unique identifier for this proposal
  * @param window the consensus window
  * @param proposer the proposing validator
  * @param targetBlockId the block ID being proposed
  * @param block the block header
  * @param txSet the transaction set included in the proposal
  * @param justify the quorum certificate justifying this proposal
  * @param signature the cryptographic signature of the proposer
  */
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

/** Companion for `Proposal`, providing signing and identity operations. */
object Proposal:

  /** Signs an unsigned proposal using the given key pair.
    *
    * @param unsigned the proposal to sign
    * @param keyPair the signer's key pair
    * @return the signed proposal or a validation failure
    */
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

  /** Returns the canonical bytes to be signed for the given unsigned proposal. */
  def signBytes(
      unsigned: UnsignedProposal,
  ): ByteVector =
    HotStuffCanonicalEncoding.proposalSignBytes(
      normalizeUnsignedProposal(unsigned),
    )

  /** Recomputes the proposal ID from the proposal's fields, for verification purposes. */
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

/** Canonical encoding and hashing operations for HotStuff consensus artifacts. */
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

  /** Hashes the byte-encoded form of a value using Keccak-256.
    *
    * @tparam A the value type, which must be byte-encodable
    * @param value the value to hash
    * @return the 256-bit hash
    */
  def hashEncoded[A: ByteEncoder](
      value: A,
  ): UInt256 =
    UInt256.unsafeFromBytesBE:
      ByteVector.view(CryptoOps.keccak256(value.toBytes.toArray))

  /** Computes the canonical hash of a validator set. */
  def validatorSetHash(
      validatorSet: ValidatorSet,
  ): UInt256 =
    hashEncoded:
      ValidatorSetHashInput(
        domain = ValidatorSetDomain,
        members = validatorSet.members.sortBy(_.id.value),
      )

  /** Computes the canonical bytes to be signed for a proposal. */
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

  /** Computes the canonical bytes to be signed for a vote. */
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

  /** Computes a deterministic proposal ID from all proposal fields. */
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

  /** Computes a deterministic vote ID from all vote fields. */
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

  /** Signs the given bytes using the key pair and returns the signature. */
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
