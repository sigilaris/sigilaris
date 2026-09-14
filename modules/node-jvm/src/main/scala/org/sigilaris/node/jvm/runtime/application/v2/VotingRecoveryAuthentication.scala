package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{Proposal, Vote}

/** Replays the original authenticated request closure, including full execution
  * plans, source signatures and complete witnesses. Repositories must retain
  * historical inputs and execute independently of the store whose gate is held.
  * Compose with the application's recovery verifier or votesOnly allowlist.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object VotingRecoveryAuthentication:
  def authenticated[F[_]: Monad](
      requests: ApplicationRequestVerifier[F],
  ): SafetyRecoveryAuthentication[F] = new SafetyRecoveryAuthentication[F]:
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](RuntimeCheck.core(value))
    private def check(value: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(value, RuntimeFailureCode.ProofInvalid, detail),
      )
    private def present[A](value: Option[A], detail: String): Result[F, A] =
      EitherT.fromOption[F](
        value,
        V2RuntimeFailure.at(RuntimeFailureCode.EvidenceMissing, detail),
      )

    private def evidence(
        journal: DurableJournal[F],
        id: Hash,
        kind: Long,
    ): Result[F, Bytes] =
      for
        bytes <- journal.readBlob(VotingEvidenceStorage.Namespace, id)
        value <- core(VotingEvidence.codec.decode(bytes))
        _     <- check(
          value.kind == kind,
          "retained voting request kind differs from durable intent",
        )
      yield value.payload

    private def claim(
        state: SafetyState,
        journal: DurableJournal[F],
        owner: Owner,
        witness: ReservationWitness,
        deadline: Height,
    ): Result[F, Unit] =
      for
        id     <- core(Owner.digest(owner))
        ref    <- core(WitnessRef.fromWitness(witness))
        stored <- present(
          state.claims.get(id),
          "verified voting owner has no retained reservation",
        )
        _ <- check(
          stored.owner == owner && stored.witness == ref && stored.lastInclusionHeight == deadline,
          "retained reservation differs from independently reexecuted complete owner",
        )
        complete <- WitnessStorage.read(journal, stored.witness)
        _        <- check(
          complete == witness,
          "retained complete witness differs from verified actual execution",
        )
      yield ()

    private def lockIntent(
        state: SafetyState,
        journal: DurableJournal[F],
        id: Hash,
        intent: VoteIntent,
    ): Result[F, Unit] =
      for
        payload  <- evidence(journal, id, 1L)
        raw      <- core(RetainedLockRequest.codec.decode(payload))
        verified <- requests.verifyLock(
          raw.subject,
          raw.descriptor,
          raw.signedTransaction,
          raw.proofs,
        )
        _ <- EitherT.fromEither[F](ExactOwnershipBinding.lock(state, verified))
        subject <- core(LockSubject.codec.encode(verified.subject))
        stored  <- present(
          state.locks.get(verified.subject.executionId),
          "verified lock has no retained lock claim",
        )
        _ <- check(
          intent.subject == subject && intent.owner.isEmpty && intent.witness.isEmpty &&
            stored.context == verified.subject.context && stored.executionId == verified.subject.executionId &&
            stored.subjectDigest == intent.subjectDigest && stored.inputIds == verified.subject.inputs &&
            stored.lastInclusionHeight == verified.subject.lastInclusionHeight,
          "durable lock intent or claim differs from authenticated signed source",
        )
      yield ()

    private def effectIntent(
        state: SafetyState,
        journal: DurableJournal[F],
        id: Hash,
        intent: VoteIntent,
    ): Result[F, Unit] =
      for
        payload  <- evidence(journal, id, 2L)
        raw      <- core(RetainedEffectRequest.codec.decode(payload))
        verified <- requests.verifyEffect(raw.subject, raw.owner, raw.witness)
        _        <- EitherT.fromEither[F](
          ExactOwnershipBinding.effect(state, verified),
        )
        subject <- core(EffectSubject.codec.encode(verified.subject))
        ref     <- core(WitnessRef.fromWitness(verified.witness))
        _       <- check(
          intent.subject == subject && intent.owner.contains(
            verified.owner,
          ) && intent.witness.contains(ref),
          "durable effect intent differs from authenticated independent execution",
        )
        _ <- claim(
          state,
          journal,
          verified.owner,
          verified.witness,
          verified.subject.lastInclusionHeight,
        )
      yield ()

    private def proposal(bytes: Bytes): Result[F, Proposal] =
      import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
      for
        decoded <- EitherT.fromEither[F](
          ByteDecoder[Proposal]
            .decode(bytes)
            .leftMap(error =>
              V2RuntimeFailure
                .at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
            ),
        )
        _ <- check(
          decoded.remainder.isEmpty && ByteEncoder[Proposal].encode(
            decoded.value,
          ) == bytes,
          "retained proposal is not canonical",
        )
      yield decoded.value

    private def consensusIntent(
        state: SafetyState,
        journal: DurableJournal[F],
        id: Hash,
        intent: ConsensusVoteIntent,
    ): Result[F, Unit] =
      for
        payload   <- evidence(journal, id, 3L)
        raw       <- core(RetainedConsensusRequest.codec.decode(payload))
        candidate <- proposal(raw.proposal)
        verified  <- requests.verifyProposal(candidate, raw.plan)
        _         <- EitherT.fromEither[F](
          ExactOwnershipBinding.consensus(state, verified),
        )
        owners <- verified.reservations.traverse(value =>
          core(Owner.digest(value.owner)),
        )
        root <- core(ExecutionPlan.computeRoot(verified.plan))
        _    <- check(
          intent.context == verified.context &&
            intent.validatorId.asString == verified.unsignedVote.voter.value &&
            intent.unsignedVoteSignBytes == Vote.signBytes(
              verified.unsignedVote,
            ) &&
            intent.proposalId == verified.proposal.proposalId.toUInt256 &&
            intent.targetBlockId == verified.proposal.targetBlockId.toUInt256 &&
            intent.planRoot == root.toUInt256 && intent.bodyRoot == verified.bodyRoot &&
            intent.validatedStateRoot == verified.validatedStateRoot && intent.ownerDigests == owners,
          "durable consensus intent differs from authenticated proposal, plan, result or complete owners",
        )
        _ <- verified.reservations.traverse_(value =>
          claim(
            state,
            journal,
            value.owner,
            value.witness,
            value.lastInclusionHeight,
          ),
        )
      yield ()

    def verify(
        state: SafetyState,
        journal: DurableJournal[F],
    ): Result[F, Unit] =
      for
        _ <- state.intents.toVector.sortBy(_._1.bytes.toHex).traverse_ {
          (id, intent) =>
            for
              actualId <- core(VoteIntent.digest(intent))
              _ <- check(actualId == id, "durable voting intent key differs")
              _ <- intent.kind match
                case VoteIntentKind.Lock =>
                  lockIntent(state, journal, id, intent)
                case VoteIntentKind.Effect =>
                  effectIntent(state, journal, id, intent)
            yield ()
        }
        _ <- state.consensusIntents.toVector
          .sortBy(_._1.bytes.toHex)
          .traverse_ { (id, intent) =>
            for
              actualId <- core(ConsensusVoteIntent.digest(intent))
              _ <- check(actualId == id, "durable consensus intent key differs")
              _ <- consensusIntent(state, journal, id, intent)
            yield ()
          }
        _ <- state.lockCertificates.toVector
          .sortBy(_._1.bytes.toHex)
          .traverse_ { (id, certificate) =>
            for
              actualId <- core(LockCertificate.id(certificate))
              _        <- check(
                actualId == id,
                "retained lock certificate key differs",
              )
              verified <- requests.verifyLockCertificate(certificate)
              _        <- EitherT.fromEither[F](
                ExactOwnershipBinding.lock(state, verified.request),
              )
              stored <- present(
                state.locks.get(verified.request.subject.executionId),
                "imported lock certificate has no retained lock claim",
              )
              subject <- core(
                LockSubject.codec.encode(verified.request.subject),
              )
              _ <- check(
                stored.context == verified.request.subject.context &&
                  stored.executionId == verified.request.subject.executionId &&
                  stored.subjectDigest == Commitment
                    .hash(LockSubject.Domain, subject) &&
                  stored.inputIds == verified.request.subject.inputs &&
                  stored.lastInclusionHeight == verified.request.subject.lastInclusionHeight,
                "imported lock claim differs from authenticated certificate source",
              )
            yield ()
          }
        _ <- state.effectCertificates.toVector
          .sortBy(_._1.bytes.toHex)
          .traverse_ { (id, certificate) =>
            for
              actualId <- core(EffectCertificate.id(certificate))
              _        <- check(
                actualId == id,
                "retained effect certificate key differs",
              )
              verified <- requests.verifyEffectCertificate(certificate)
              _        <- EitherT.fromEither[F](
                ExactOwnershipBinding.effect(state, verified.request),
              )
              ownerId <- core(Owner.digest(verified.request.owner))
              // Certificate import archives authenticated evidence. It creates
              // no local effect vote or reservation; existing local ownership
              // must still match the independently verified complete witness.
              _ <-
                if state.claims.contains(ownerId) then
                  claim(
                    state,
                    journal,
                    verified.request.owner,
                    verified.request.witness,
                    verified.request.subject.lastInclusionHeight,
                  )
                else EitherT.rightT[F, V2RuntimeFailure](())
            yield ()
          }
      yield ()
