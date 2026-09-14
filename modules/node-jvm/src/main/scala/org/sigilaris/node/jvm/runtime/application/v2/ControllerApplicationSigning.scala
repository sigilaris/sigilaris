package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.{Proposal, Vote}

/** Original forced vote material and fresh key-use permission are distinct.
  * Recovery validates historical original evidence; authorize additionally
  * checks current canonical state, live reservations, remaining future
  * inclusion and every selected exact pipeline's actual successful
  * mode/output/ancestry. Repositories/publication must read independently of
  * the held safety gate.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class ControllerApplicationSigning private (
    anchor: ApplicationAnchor,
    journal: DurableJournal[IO],
    requests: ApplicationRequestVerifier[IO],
    current: Option[
      (
          JournalSafetyStore[IO],
          FinalizedApplicationRuntime[IO],
          SafetyPublication[IO],
          Option[ExactExecutionRequestVerifier[IO]],
      ),
    ],
):
  import ControllerApplicationSigning.*
  private def core[A](value: Either[CoreFailure, A]): Result[IO, A] =
    EitherT.fromEither[IO](RuntimeCheck.core(value))
  private def check(value: Boolean, detail: String): Result[IO, Unit] =
    EitherT.fromEither[IO](
      RuntimeCheck.require(value, RuntimeFailureCode.ProofInvalid, detail),
    )
  private def present[A](value: Option[A], detail: String): Result[IO, A] =
    EitherT.fromOption[IO](
      value,
      V2RuntimeFailure.at(RuntimeFailureCode.EvidenceMissing, detail),
    )
  private def state: Result[IO, SafetyState] = for
    raw     <- journal.recover
    history <- EitherT.fromEither[IO](JournalHistory.validate(raw))
    _       <- check(
      history.pending.isEmpty,
      "application key use requires a committed original voting intent",
    )
    result <- EitherT.fromEither[IO](
      history.records
        .filter(_.status == JournalStatus.Committed)
        .foldLeft[Either[V2RuntimeFailure, SafetyState]](
          Right(SafetyState.empty),
        )((acc, record) =>
          acc.flatMap(SafetyJournalReduction(_, record, anchor)),
        ),
    )
  yield result
  private def proposal(bytes: Bytes): Result[IO, Proposal] =
    import org.sigilaris.node.jvm.runtime.consensus.hotstuff.given
    for
      parsed <- EitherT.fromEither[IO](
        ByteDecoder[Proposal]
          .decode(bytes)
          .leftMap(error =>
            V2RuntimeFailure
              .at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
          ),
      )
      _ <- check(
        parsed.remainder.isEmpty && ByteEncoder[Proposal].encode(
          parsed.value,
        ) == bytes,
        "controller original proposal preimage is not canonical",
      )
    yield parsed.value
  private def original(
      request: ControllerApplicationVote,
      signer: Text,
  ): Result[IO, Original] = for
    derived <- EitherT.fromEither[IO](material(request))
    _       <- check(
      derived.context == anchor.context,
      "application signing request changed its installed context",
    )
    current <- state
    _       <- VotingRecoveryAuthentication
      .authenticated(requests)
      .verify(current, journal)
    matches <- current.intents.toVector.traverse { (digest, intent) =>
      val preimage = intent.kind match
        case VoteIntentKind.Lock =>
          LockSubject.codec
            .decode(intent.subject)
            .flatMap(LockSubject.signingPreimage)
        case VoteIntentKind.Effect =>
          EffectSubject.codec
            .decode(intent.subject)
            .flatMap(EffectSubject.signingPreimage)
      core(preimage).map(bytes =>
        Option.when(
          intent.validatorId == signer && intent.context == request.context &&
            bytes == request.canonicalPreimage,
        )(digest -> intent),
      )
    }
    locks     = matches.flatten
    consensus = current.consensusIntents.toVector.filter((_, value) =>
      value.validatorId == signer && value.context == request.context &&
        value.unsignedVoteSignBytes == request.canonicalPreimage,
    )
    _ <- check(
      locks.size + consensus.size == 1,
      "controller request has no unique original forced application intent",
    )
    original <- locks.headOption match
      case Some((digest, intent)) =>
        for
          bytes    <- journal.readBlob(VotingEvidenceStorage.Namespace, digest)
          evidence <- core(VotingEvidence.codec.decode(bytes))
          resolved <- intent.kind match
            case VoteIntentKind.Lock =>
              for
                _ <- check(
                  evidence.kind == 1L,
                  "lock intent evidence kind changed",
                )
                raw <- core(RetainedLockRequest.codec.decode(evidence.payload))
                verified <- requests.verifyLock(
                  raw.subject,
                  raw.descriptor,
                  raw.signedTransaction,
                  raw.proofs,
                )
              yield Original(current, derived, Some(verified), None, None)
            case VoteIntentKind.Effect =>
              for
                _ <- check(
                  evidence.kind == 2L,
                  "effect intent evidence kind changed",
                )
                raw <- core(
                  RetainedEffectRequest.codec.decode(evidence.payload),
                )
                verified <- requests.verifyEffect(
                  raw.subject,
                  raw.owner,
                  raw.witness,
                )
              yield Original(current, derived, None, Some(verified), None)
        yield resolved
      case None =>
        for
          selected <- present(
            consensus.headOption,
            "forced consensus intent missing",
          )
          bytes <- journal.readBlob(
            VotingEvidenceStorage.Namespace,
            selected._1,
          )
          evidence <- core(VotingEvidence.codec.decode(bytes))
          _        <- check(
            evidence.kind == 3L,
            "consensus intent evidence kind changed",
          )
          raw <- core(RetainedConsensusRequest.codec.decode(evidence.payload))
          candidate <- proposal(raw.proposal)
          verified  <- requests.verifyProposal(candidate, raw.plan)
          _         <- check(
            verified.unsignedVote.voter.value == signer.asString && Vote
              .signBytes(verified.unsignedVote) == request.canonicalPreimage,
            "controller consensus material changed its actual candidate or voter",
          )
        yield Original(current, derived, None, None, Some(verified))
  yield original

  def authenticate(
      request: ControllerApplicationVote,
      signer: Text,
  ): Result[IO, ControllerSigningMaterial] =
    original(request, signer).map(_.material)

  /** Fresh proposal/timeout/new-view key use has the same installed history and
    * finalizer gates, but carries a separately validated control request. This
    * method reads leases only and never reenters either held gate.
    */
  def authorizeControl(
      material: ControllerSigningMaterial,
      signer: Text,
  ): Result[IO, Unit] = for
    live <- present(
      current,
      "original-only controller recovery cannot authorize new control key use",
    )
    (safety, finalizer, _, _) = live
    currentState <- state
    _            <- safety.verifyActiveSigning(
      material.context,
      signer,
      material.canonicalPreimage,
      currentState.committed,
    )
    _ <- finalizer.verifyActiveSigning(
      safety,
      material.context,
      material.canonicalPreimage,
    )
  yield ()

  def authorize(
      request: ControllerApplicationVote,
      signer: Text,
  ): Result[IO, Unit] = for
    live <- present(
      current,
      "original-only controller recovery cannot authorize new key use",
    )
    (safety, finalizer, publication, exact) = live
    verified <- original(request, signer)
    _        <- safety.verifyActiveSigning(
      request.context,
      signer,
      request.canonicalPreimage,
      verified.state.committed,
    )
    _ <- finalizer.verifyActiveSigning(
      safety,
      request.context,
      request.canonicalPreimage,
    )
    finalized <- publication.finalizedHeight(request.context)
    _         <- verified.lock.traverse_ { lock =>
      for
        claim <- present(
          verified.state.locks.get(lock.subject.executionId),
          "initial lock protection disappeared",
        )
        _ <- check(
          claim.lifecycle == ClaimLifecycle.Live && finalized.toBigNat.toBigInt < lock.subject.lastInclusionHeight.toBigNat.toBigInt,
          "new lock key use is terminal or past its signed deadline",
        )
      yield ()
    }
    _ <- verified.effect.traverse_ { effect =>
      for
        _     <- publication.verifyEffectState(effect)
        id    <- core(Owner.digest(effect.owner))
        claim <- present(
          verified.state.claims.get(id),
          "effect owner protection disappeared",
        )
        _ <- check(
          claim.lifecycle == ClaimLifecycle.Live && finalized.toBigNat.toBigInt < effect.subject.lastInclusionHeight.toBigNat.toBigInt,
          "new effect key use is terminal or past its signed deadline",
        )
      yield ()
    }
    _ <- verified.consensus.traverse_ { candidate =>
      for
        _ <- publication.verifyConsensusState(candidate)
        _ <- candidate.reservations.traverse_ { owner =>
          for
            id    <- core(Owner.digest(owner.owner))
            claim <- present(
              verified.state.claims.get(id),
              "consensus owner protection disappeared",
            )
            _ <- check(
              claim.lifecycle == ClaimLifecycle.Live && finalized.toBigNat.toBigInt < owner.lastInclusionHeight.toBigNat.toBigInt,
              "new consensus key use has a terminal owner or expired signed deadline",
            )
          yield ()
        }
        _ <- exact match
          case Some(actual) =>
            actual.revalidateSigning(verified.state, journal, candidate)
          case None =>
            check(
              candidate.reservations.forall(_.exactBinding.isEmpty),
              "exact signing requires its actual mode/output/ancestry verifier",
            )
      yield ()
    }
  yield ()

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ControllerApplicationSigning:
  private final case class Original(
      state: SafetyState,
      material: ControllerSigningMaterial,
      lock: Option[VerifiedLockRequest],
      effect: Option[VerifiedEffectRequest],
      consensus: Option[VerifiedConsensusProposal],
  )
  def authenticated(
      safety: JournalSafetyStore[IO],
      finalizer: FinalizedApplicationRuntime[IO],
      requests: ApplicationRequestVerifier[IO],
      publication: SafetyPublication[IO],
      exact: Option[ExactExecutionRequestVerifier[IO]],
  ): ControllerApplicationSigning =
    new ControllerApplicationSigning(
      safety.anchor,
      safety.journal,
      requests,
      Some((safety, finalizer, publication, exact)),
    )

  /** Used during controller Resource acquisition, before any live SafetyStore
    * exists. It reauthenticates original forced sources but cannot authorize a
    * fresh or unfinished key-use operation.
    */
  def recovery(
      anchor: ApplicationAnchor,
      journal: DurableJournal[IO],
      requests: ApplicationRequestVerifier[IO],
  ): ControllerApplicationSigning =
    new ControllerApplicationSigning(anchor, journal, requests, None)

  /** The context is independently checked against the original intent. This
    * parser supplies no authority and rejects noncanonical/domain-ambiguous
    * frames before the key controller can classify a fence scope.
    */
  def material(
      value: ControllerApplicationVote,
  ): Either[V2RuntimeFailure, ControllerSigningMaterial] =
    ConsensusSignFields.codec.decode(value.canonicalPreimage) match
      case Right(fields) =>
        for _ <- RuntimeCheck.require(
            fields.chainId == value.context.chainId && fields.validatorSetHash == value.context.validatorSetHash,
            RuntimeFailureCode.DomainMismatch,
            "consensus signing frame differs from its full installed application context",
          )
        yield ControllerSigningMaterial(
          value.context,
          ControllerSigningKind.Consensus,
          Some(InclusionHeight(fields.height)),
          value.canonicalPreimage,
        )
      case Left(_) =>
        for
          domain <- ByteDecoder[Text]
            .decode(value.canonicalPreimage)
            .leftMap(error =>
              V2RuntimeFailure
                .at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
            )
          payload <- V2Codecs.bytesDecoder
            .decode(domain.remainder)
            .leftMap(error =>
              V2RuntimeFailure
                .at(RuntimeFailureCode.NonCanonicalEncoding, error.msg),
            )
          _ <- RuntimeCheck.require(
            payload.remainder.isEmpty,
            RuntimeFailureCode.NonCanonicalEncoding,
            "application signing frame has trailing bytes",
          )
          selected <-
            if domain.value == LockSubject.Domain then
              for
                subject <- RuntimeCheck.core(
                  LockSubject.codec.decode(payload.value),
                )
                encoded <- RuntimeCheck.core(
                  LockSubject.signingPreimage(subject),
                )
                _ <- RuntimeCheck.require(
                  subject.context == value.context && encoded == value.canonicalPreimage,
                  RuntimeFailureCode.DomainMismatch,
                  "lock signing frame changed its original context/preimage",
                )
              yield ControllerSigningKind.ApplicationLock
            else if domain.value == EffectSubject.Domain then
              for
                subject <- RuntimeCheck.core(
                  EffectSubject.codec.decode(payload.value),
                )
                encoded <- RuntimeCheck.core(
                  EffectSubject.signingPreimage(subject),
                )
                _ <- RuntimeCheck.require(
                  subject.context == value.context && encoded == value.canonicalPreimage,
                  RuntimeFailureCode.DomainMismatch,
                  "effect signing frame changed its original context/preimage",
                )
              yield ControllerSigningKind.ApplicationEffect
            else
              Left[V2RuntimeFailure, ControllerSigningKind](
                V2RuntimeFailure.at(
                  RuntimeFailureCode.InvalidRequest,
                  "unsupported application signing domain",
                ),
              )
        yield ControllerSigningMaterial(
          value.context,
          selected,
          None,
          value.canonicalPreimage,
        )
