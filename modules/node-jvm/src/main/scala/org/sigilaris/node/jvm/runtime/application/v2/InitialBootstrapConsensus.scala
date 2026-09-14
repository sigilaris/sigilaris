package org.sigilaris.node.jvm.runtime.application.v2

import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.application.protocol.v2.V2Codecs.bytesEncoder
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.crypto.{PublicKey, Signature}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.gossip.ChainId
import org.sigilaris.node.jvm.runtime.block.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given

/** A deterministic G header and initial vote subject; no Proposal or finality
  * proof is fabricated for G. This data alone permits neither installation nor
  * an ordinary first-parent lookup.
  */
final case class InitialBootstrapGenesis(
    context: DomainContext,
    bundleDigest: Hash,
    header: BlockHeader,
    blockId: BlockId,
    validators: ValidatorSet,
    subject: BootstrapSubject,
    quorumSubject: QuorumCertificateSubject,
)

type AuthenticatedBootstrapCertificate =
  InitialBootstrapConsensus.AuthenticatedBootstrapCertificate

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object InitialBootstrapConsensus:
  final class AuthenticatedBootstrapCertificate private[InitialBootstrapConsensus] (
      val bootstrap: VerifiedBootstrap,
      val genesis: InitialBootstrapGenesis,
      val certificate: BootstrapCertificate,
      val quorum: QuorumCertificate,
  )

  private def core[A](
      value: Either[CoreFailure, A],
  ): Either[V2RuntimeFailure, A] = value.leftMap(V2RuntimeFailure.fromCore)
  private def check(
      condition: Boolean,
      detail: String,
  ): Either[V2RuntimeFailure, Unit] =
    RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail)
  private def textFailure(value: String): V2RuntimeFailure =
    V2RuntimeFailure.at(RuntimeFailureCode.InvalidRequest, value)
  private def consensusFailure(
      value: HotStuffValidationFailure,
  ): V2RuntimeFailure =
    V2RuntimeFailure.at(RuntimeFailureCode.ProofInvalid, value.reason)

  def genesis(
      bootstrap: VerifiedBootstrap,
  ): Either[V2RuntimeFailure, InitialBootstrapGenesis] =
    val signed = bootstrap.source.bundle
    val bundle = signed.bundle
    for
      digest <- core(SignedBootstrapBundle.digest(signed))
      _      <- check(
        digest == bootstrap.digest && digest == bootstrap.source.bundleDigest && bootstrap.source.closure.source == bundle.source,
        "initial G source differs from the original verified bootstrap",
      )
      _       <- core(DomainContext.validateActive(bundle.target))
      members <- bundle.validators.traverse(member =>
        for
          id <- ValidatorId
            .parse(member.validatorId.asString)
            .leftMap(textFailure)
          key <- PublicKey
            .fromByteArray(member.publicKey.toArray)
            .leftMap(error => textFailure(error.msg))
        yield ValidatorMember(id, key),
      )
      validators <- ValidatorSet(members).leftMap(error =>
        textFailure(error.message),
      )
      _ <- check(
        validators.hash.toUInt256 == bundle.target.validatorSetHash,
        "initial G validators differ from the fixed bundle domain",
      )
      chain <- ChainId
        .parse(bundle.target.chainId.asString)
        .leftMap(textFailure)
      window <- HotStuffWindow
        .fromLongs(chain, 0L, 0L, validators.hash)
        .leftMap(textFailure)
      timestamp <- BlockTimestamp
        .fromEpochMillis(bundle.genesisTimestampMillis)
        .leftMap(textFailure)
      bodyRoot <- BlockBody
        .computeBodyRoot(BlockBody[Hash, Hash, Bytes](Set.empty))
        .leftMap(error => textFailure(error.reason))
      planRoot <- core(ExecutionPlan.computeRoot(ExecutionPlan.empty))
      header = BlockHeader(
        None,
        BlockHeight.Genesis,
        StateRoot(bundle.source.stateRoot),
        bodyRoot,
        timestamp,
        BlockHeaderVersion.V2,
        Some(planRoot),
      )
      blockId = BlockHeader.computeId(header)
      subject = BootstrapSubject(1L, digest, blockId.toUInt256)
      subjectId <- core(BootstrapSubject.digest(subject))
    yield InitialBootstrapGenesis(
      bundle.target,
      digest,
      header,
      blockId,
      validators,
      subject,
      QuorumCertificateSubject(window, ProposalId(subjectId), blockId),
    )

  def unsigned(
      bootstrap: VerifiedBootstrap,
      validator: Text,
  ): Either[V2RuntimeFailure, UnsignedVote] = for
    initial <- genesis(bootstrap)
    voter   <- ValidatorId.parse(validator.asString).leftMap(textFailure)
    _       <- check(
      initial.validators.contains(voter),
      "initial bootstrap voter is outside the fixed validator set",
    )
  yield UnsignedVote(
    initial.quorumSubject.window,
    voter,
    initial.quorumSubject.proposalId,
  )

  /** Existing HotStuff vote framing, after the target journal has forced its
    * original BootstrapVoteIntent and the key-owning controller has signed it.
    */
  def attachSignature(
      bootstrap: VerifiedBootstrap,
      validator: Text,
      raw: Bytes,
  ): Either[V2RuntimeFailure, Vote] = for
    initial <- genesis(bootstrap)
    draft   <- unsigned(bootstrap, validator)
    _ <- core(ValidatorSignature.validate(ValidatorSignature(validator, raw)))
    signature = Signature(
      BigInt(1, raw.take(8L).toArray).toInt,
      UInt256.unsafeFromBytesBE(raw.slice(8L, 40L)),
      UInt256.unsafeFromBytesBE(raw.drop(40L)),
    )
    pending = Vote(
      VoteId(UInt256.unsafeFromBigIntUnsigned(BigInt(0))),
      draft.window,
      draft.voter,
      draft.targetProposalId,
      signature,
    )
    vote = pending.copy(voteId = Vote.recomputeId(pending))
    _ <- HotStuffValidator
      .validateVote(
        vote,
        initial.validators,
        Some(draft.window),
        Some(draft.targetProposalId),
      )
      .leftMap(consensusFailure)
  yield vote

  def assemble(
      bootstrap: VerifiedBootstrap,
      votes: Vector[Vote],
  ): Either[V2RuntimeFailure, BootstrapCertificate] = for
    initial <- genesis(bootstrap)
    quorum  <- QuorumCertificateAssembler
      .assemble(initial.quorumSubject, votes, initial.validators)
      .leftMap(consensusFailure)
  yield BootstrapCertificate(
    1L,
    initial.bundleDigest,
    initial.blockId.toUInt256,
    ByteEncoder[QuorumCertificate].encode(quorum),
  )

  def authenticate(
      bootstrap: VerifiedBootstrap,
      certificate: BootstrapCertificate,
  ): Either[V2RuntimeFailure, AuthenticatedBootstrapCertificate] = for
    initial <- genesis(bootstrap)
    _       <- core(BootstrapCertificate.validate(certificate))
    _       <- check(
      certificate.bundleDigest == initial.bundleDigest && certificate.genesisBlockId == initial.blockId.toUInt256,
      "initial certificate changed the original bundle or G",
    )
    decoded <- ByteDecoder[QuorumCertificate]
      .decode(certificate.quorum)
      .leftMap(_ =>
        V2RuntimeFailure.at(
          RuntimeFailureCode.NonCanonicalEncoding,
          "initial quorum decoding failed",
        ),
      )
    _ <- check(
      decoded.remainder.isEmpty && ByteEncoder[QuorumCertificate].encode(
        decoded.value,
      ) == certificate.quorum,
      "initial quorum bytes are not exact canonical HotStuff encoding",
    )
    _ <- check(
      decoded.value.subject == initial.quorumSubject,
      "initial quorum is not bound to the exact bundle/G subject and window zero",
    )
    canonical <- QuorumCertificateAssembler
      .assemble(initial.quorumSubject, decoded.value.votes, initial.validators)
      .leftMap(consensusFailure)
    _ <- check(
      canonical == decoded.value,
      "initial quorum contains duplicate or noncanonical votes",
    )
  yield new AuthenticatedBootstrapCertificate(
    bootstrap,
    initial,
    certificate,
    canonical,
  )
