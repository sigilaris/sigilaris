package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.ApplicationValidatorId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.crypto.{CryptoOps, KeyPair, Signature}
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{
  HotStuffCanonicalEncoding,
  UnsignedVote,
  Vote,
  VoteId,
}

trait ApplicationVoteSigner[F[_]]:
  def validatorId: ApplicationValidatorId
  def sign(context: DomainContext, canonicalPreimage: ByteVector): F[ByteVector]

object ApplicationVoteSigner:
  /** The configured authority owns this key. The voting runtime independently
    * verifies the returned signature against its trusted validator history.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def secp256k1[F[_]: Async](
      id: ApplicationValidatorId,
      keyPair: KeyPair,
  ): ApplicationVoteSigner[F] =
    new ApplicationVoteSigner[F]:
      def validatorId: ApplicationValidatorId = id
      def sign(
          context: DomainContext,
          canonicalPreimage: ByteVector,
      ): F[ByteVector] = Async[F].delay {
        CryptoOps.sign(
          keyPair,
          CryptoOps.keccak256(canonicalPreimage.toArray),
        ) match
          case Right(signature) =>
            signature.v.toLong.toBytes ++ signature.r.toBytes ++ signature.s.toBytes
          case Left(_) =>
            throw new IllegalStateException("application vote signing failed")
      }

type PreparedConsensusVote = DurableApplicationVoting.PreparedConsensusVote

trait DurableApplicationVoting[F[_]]:
  def voteLock(request: VerifiedLockRequest): Result[F, LockVote]
  def voteEffect(request: VerifiedEffectRequest): Result[F, EffectVote]
  def prepareConsensusVote(
      request: VerifiedConsensusProposal,
  ): Result[F, PreparedConsensusVote]
  def signConsensusVote(prepared: PreparedConsensusVote): Result[F, Vote]
  def importLock(certificate: VerifiedLockCertificate): Result[F, Unit]
  def importEffect(certificate: VerifiedEffectCertificate): Result[F, Unit]
  def recover: Result[F, VotingRecovery]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object DurableApplicationVoting:
  final class PreparedConsensusVote private[DurableApplicationVoting] (
      val intent: ConsensusVoteIntent,
      val intentDigest: Hash,
      val unsignedVote: UnsignedVote,
  )

  def fromStore[F[_]: Async](
      store: SafetyStore[F],
      signer: ApplicationVoteSigner[F],
      authentication: ArtifactAuthentication,
  ): DurableApplicationVoting[F] =
    new DurableApplicationVoting[F]:
      private val validatorId = signer.validatorId
      private def core[A](result: Either[CoreFailure, A]): Result[F, A] =
        EitherT.fromEither[F](result.leftMap(V2RuntimeFailure.fromCore))
      private def check(condition: Boolean, detail: String): Result[F, Unit] =
        EitherT.fromEither[F](
          RuntimeCheck.require(
            condition,
            RuntimeFailureCode.InvalidRequest,
            detail,
          ),
        )

      private def verifySignature(
          context: DomainContext,
          preimage: Bytes,
          bytes: Bytes,
      ): Either[V2RuntimeFailure, ValidatorSignature] =
        val vote = ValidatorSignature(validatorId.value, bytes)
        for
          _          <- RuntimeCheck.core(ValidatorSignature.validate(vote))
          validators <- RuntimeCheck.core(
            authentication.historicalValidators(context),
          )
          _ <- RuntimeCheck.require(
            validators.contains(validatorId.value),
            RuntimeFailureCode.InvalidSignature,
            "vote signer is not in authenticated validator history",
          )
          _ <- RuntimeCheck.core(
            authentication.verifySignature(
              context,
              validatorId.value,
              preimage,
              bytes,
            ),
          )
        yield vote

      private def signed(
          intent: VoteIntent,
          preimage: Bytes,
      ): Result[F, ValidatorSignature] =
        for
          digest <- core(VoteIntent.digest(intent))
          result <- store.withSigningPermission(digest)(
            signer
              .sign(intent.context, preimage)
              .map(bytes => verifySignature(intent.context, preimage, bytes)),
          )
          signature <- EitherT.fromEither[F](result)
        yield signature

      def voteLock(request: VerifiedLockRequest): Result[F, LockVote] =
        for
          intent <- store.claimLock(request, validatorId.value)
          bytes  <- core(LockSubject.codec.encode(request.subject))
          _      <- check(
            intent.kind == VoteIntentKind.Lock && intent.subject == bytes && intent.context == request.subject.context && intent.validatorId == validatorId.value,
            "durable lock intent differs from requested signer/subject",
          )
          preimage  <- core(LockSubject.signingPreimage(request.subject))
          signature <- signed(intent, preimage)
        yield LockVote(request.subject, signature)

      def voteEffect(request: VerifiedEffectRequest): Result[F, EffectVote] =
        for
          intent <- store.claimEffect(request, validatorId.value)
          bytes  <- core(EffectSubject.codec.encode(request.subject))
          _      <- check(
            intent.kind == VoteIntentKind.Effect && intent.subject == bytes && intent.context == request.subject.context && intent.validatorId == validatorId.value && intent.owner
              .contains(request.owner),
            "durable effect intent differs from requested signer/subject/owner",
          )
          preimage  <- core(EffectSubject.signingPreimage(request.subject))
          signature <- signed(intent, preimage)
        yield EffectVote(request.subject, signature)

      private def validatePrepared(
          intent: ConsensusVoteIntent,
          unsigned: UnsignedVote,
      ): Result[F, Hash] =
        for
          digest <- core(ConsensusVoteIntent.digest(intent))
          _      <- check(
            intent.validatorId == validatorId.value && unsigned.voter.value == validatorId.value.asString && intent.unsignedVoteSignBytes == Vote
              .signBytes(
                unsigned,
              ) && intent.proposalId == unsigned.targetProposalId.toUInt256 && unsigned.window.chainId.value == intent.context.chainId.asString && unsigned.window.validatorSetHash.toUInt256 == intent.context.validatorSetHash,
            "prepared consensus vote differs from durable signer/context/target/preimage",
          )
        yield digest

      def prepareConsensusVote(
          request: VerifiedConsensusProposal,
      ): Result[F, PreparedConsensusVote] =
        for
          intent <- store.claimConsensus(request, validatorId.value)
          digest <- validatePrepared(intent, request.unsignedVote)
          root   <- core(ExecutionPlan.computeRoot(request.plan))
          owners <- core(
            request.reservations.traverse(value => Owner.digest(value.owner)),
          )
          _ <- check(
            intent.context == request.context && intent.targetBlockId == request.proposal.targetBlockId.toUInt256 && intent.planRoot == root.toUInt256 && intent.bodyRoot == request.bodyRoot && intent.validatedStateRoot == request.validatedStateRoot && intent.ownerDigests == owners,
            "prepared consensus intent differs from complete executed candidate",
          )
        yield new PreparedConsensusVote(intent, digest, request.unsignedVote)

      def signConsensusVote(prepared: PreparedConsensusVote): Result[F, Vote] =
        for
          digest <- validatePrepared(prepared.intent, prepared.unsignedVote)
          _      <- check(
            digest == prepared.intentDigest,
            "prepared consensus intent digest mismatch",
          )
          result <- store.withSigningPermission(digest)(
            signer
              .sign(
                prepared.intent.context,
                prepared.intent.unsignedVoteSignBytes,
              )
              .map(bytes =>
                verifySignature(
                  prepared.intent.context,
                  prepared.intent.unsignedVoteSignBytes,
                  bytes,
                ),
              ),
          )
          canonical <- EitherT.fromEither[F](result)
          signature = Signature(
            BigInt(1, canonical.signature.take(8L).toArray).toInt,
            UInt256.unsafeFromBytesBE(canonical.signature.slice(8L, 40L)),
            UInt256.unsafeFromBytesBE(canonical.signature.drop(40L)),
          )
          unsigned = prepared.unsignedVote
          id       = VoteId(
            HotStuffCanonicalEncoding.voteId(
              unsigned.window,
              unsigned.voter,
              unsigned.targetProposalId,
              signature,
            ),
          )
        yield Vote(
          id,
          unsigned.window,
          unsigned.voter,
          unsigned.targetProposalId,
          signature,
        )

      def importLock(certificate: VerifiedLockCertificate): Result[F, Unit] =
        store.importLock(certificate)
      def importEffect(
          certificate: VerifiedEffectCertificate,
      ): Result[F, Unit]                     = store.importEffect(certificate)
      def recover: Result[F, VotingRecovery] = store.recover
