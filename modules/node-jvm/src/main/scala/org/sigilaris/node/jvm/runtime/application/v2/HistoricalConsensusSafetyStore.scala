package org.sigilaris.node.jvm.runtime.application.v2

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import scodec.bits.ByteVector

import org.sigilaris.core.application.protocol.InclusionHeight
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.node.jvm.runtime.consensus.hotstuff.*

/** Original safety state for an explicitly supported legacy deployment. It is
  * created at the deployment's independently authenticated birth checkpoint,
  * never reconstructed from a transition-time pacemaker snapshot. The actual
  * controller may use a vote only after beforeVote forced its complete record.
  */
sealed trait HistoricalConsensusSafetyStore:
  def profile: HistoricalSafetyProfile
  def beforeVote(proposal: Proposal): Result[IO, Bytes]
  def observe(
      proposal: Proposal,
      certificate: QuorumCertificate,
  ): Result[IO, Unit]
  def fence(boundary: Height): Result[IO, Unit]
  def authenticateVote(
      request: Bytes,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerSigningMaterial]
  def authenticateProposal(
      proposal: Proposal,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, ControllerSigningMaterial]
  def authorizeProposal(
      proposal: Proposal,
      signerId: Text,
      publicKey: Bytes,
  ): Result[IO, Unit]
  def recover: Result[IO, HistoricalConsensusSafety.VerifiedSafety]
  def archive: Result[IO, HistoricalSafetyArchive]

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object HistoricalConsensusSafetyStore:
  /** Install at source-runtime construction time and expose only this view to
    * all original metadata writers. Controller recovery uses the underlying
    * read-only authentication methods; mutations share the stopped-group gate.
    */
  def coordinated(
      original: HistoricalConsensusSafetyStore,
      controller: FenceController,
      lifecycle: ConsistencyMutationGate,
  ): Result[IO, HistoricalConsensusSafetyStore] =
    lifecycle
      .verifyController(controller, original.profile.context)
      .as(
        new HistoricalConsensusSafetyStore:
          val profile: HistoricalSafetyProfile = original.profile
          def beforeVote(proposal: Proposal): Result[IO, Bytes] =
            lifecycle.mutate(original.beforeVote(proposal))
          def observe(
              proposal: Proposal,
              certificate: QuorumCertificate,
          ): Result[IO, Unit] =
            lifecycle.mutate(original.observe(proposal, certificate))
          def fence(boundary: Height): Result[IO, Unit] =
            lifecycle.mutate(original.fence(boundary))
          def authenticateVote(
              request: Bytes,
              signerId: Text,
              publicKey: Bytes,
          ): Result[IO, ControllerSigningMaterial] =
            original.authenticateVote(request, signerId, publicKey)
          def authenticateProposal(
              proposal: Proposal,
              signerId: Text,
              publicKey: Bytes,
          ): Result[IO, ControllerSigningMaterial] =
            original.authenticateProposal(proposal, signerId, publicKey)
          def authorizeProposal(
              proposal: Proposal,
              signerId: Text,
              publicKey: Bytes,
          ): Result[IO, Unit] =
            original.authorizeProposal(proposal, signerId, publicKey)
          def recover: Result[IO, HistoricalConsensusSafety.VerifiedSafety] =
            original.recover
          def archive: Result[IO, HistoricalSafetyArchive] = original.archive,
      )

  def resource(
      path: Path,
      installed: HistoricalSafetyProfile,
      consensus: HistoricalConsensusAuthentication[IO],
      history: HistoricalConsensusRepository[IO],
      validators: ValidatorSetLookup[IO],
      maximumBytes: Long,
      faults: JournalFaultInjector[IO],
  ): Resource[IO, HistoricalConsensusSafetyStore] =
    def required[A](run: Result[IO, A]): IO[A] = run.value.flatMap(
      _.fold(error => IO.raiseError(new JournalOpenException(error)), IO.pure),
    )
    Resource
      .eval(
        required(
          EitherT.fromEither[IO](
            RuntimeCheck.core(HistoricalSafetyProfile.digest(installed)),
          ),
        ),
      )
      .flatMap { identity =>
        CanonicalAppendLog
          .resource(path, identity.bytes, maximumBytes, faults)
          .flatMap { log =>
            Resource
              .eval((Semaphore[IO](1L), Ref.of[IO, Boolean](false)).tupled)
              .flatMap { (gate, ready) =>
                val store = new HistoricalConsensusSafetyStore:
                  val profile: HistoricalSafetyProfile = installed
                  private def core[A](
                      value: Either[CoreFailure, A],
                  ): Result[IO, A] =
                    EitherT.fromEither[IO](RuntimeCheck.core(value))
                  private def check(condition: Boolean, detail: String)
                      : Result[IO, Unit] = EitherT.fromEither[IO](
                    RuntimeCheck.require(
                      condition,
                      RuntimeFailureCode.ProofInvalid,
                      detail,
                    ),
                  )
                  private def current
                      : Result[IO, HistoricalConsensusSafety.VerifiedSafety] =
                    for
                      bytes <- log.recover
                      value <- HistoricalConsensusSafety.verify(
                        profile,
                        bytes,
                        consensus,
                        history,
                        validators,
                      )
                    yield value
                  private def under[A](
                      recovering: Boolean,
                  )(run: Result[IO, A]): Result[IO, A] = EitherT(
                    gate.permit.use(_ =>
                      IO.uncancelable(_ =>
                        ready.get.flatMap { enabled =>
                          if !enabled && !recovering then
                            IO.pure(
                              Left(
                                V2RuntimeFailure.at(
                                  RuntimeFailureCode.RecoveryRequired,
                                  "original historical safety requires recovery",
                                ),
                              ),
                            )
                          else
                            run.value.attempt.flatMap {
                              case Right(Right(value)) => IO.pure(Right(value))
                              case Right(Left(error))  =>
                                if error.code == RuntimeFailureCode.StorageUnknown || error.code == RuntimeFailureCode.JournalCorrupt || error.code == RuntimeFailureCode.RecoveryRequired
                                then ready.set(false).as(Left(error))
                                else IO.pure(Left(error))
                              case Left(_) =>
                                ready
                                  .set(false)
                                  .as(
                                    Left(
                                      V2RuntimeFailure.at(
                                        RuntimeFailureCode.StorageUnknown,
                                        "historical pre-sign safety outcome is unknown",
                                      ),
                                    ),
                                  )
                            }
                        },
                      ),
                    ),
                  )
                  private def append(
                      before: HistoricalConsensusSafety.VerifiedSafety,
                      operation: HistoricalSafetyOperation,
                      proposal: Option[Proposal],
                      certificate: Option[QuorumCertificate],
                      boundary: Option[Height],
                      preimage: Bytes,
                  ): Result[IO, Bytes] = for
                    _ <- check(
                      before.state.sequence < Long.MaxValue,
                      "historical safety sequence exhausted",
                    )
                    record = HistoricalSafetyRecord(
                      1L,
                      before.state.sequence + 1L,
                      before.state.digest,
                      before.profileDigest,
                      operation,
                      proposal,
                      certificate,
                      boundary,
                      preimage,
                    )
                    bytes <- core(HistoricalSafetyRecord.codec.encode(record))
                    prior <- before.state.originalRecords.traverse(row =>
                      core(HistoricalSafetyRecord.codec.encode(row)),
                    )
                    _ <- HistoricalConsensusSafety.verify(
                      profile,
                      prior :+ bytes,
                      consensus,
                      history,
                      validators,
                    )
                    _ <- log.append(bytes)
                  yield bytes
                  def beforeVote(proposal: Proposal): Result[IO, Bytes] =
                    under(false) {
                      for
                        before <- current
                        _      <- check(
                          before.state.fenceBoundary.forall(
                            _.toBigNat.toBigInt > proposal.window.height.toBigNat.toBigInt,
                          ),
                          "historical boundary prohibits this vote",
                        )
                        existing = before.state.voteIntents.find(
                          _.proposal.contains(proposal),
                        )
                        bytes <- existing.fold(
                          append(
                            before,
                            HistoricalSafetyOperation.VoteIntent,
                            Some(proposal),
                            None,
                            None,
                            Vote.signBytes(
                              UnsignedVote(
                                proposal.window,
                                ValidatorId.unsafe(profile.voter.asString),
                                proposal.proposalId,
                              ),
                            ),
                          ),
                        )(row => core(HistoricalSafetyRecord.codec.encode(row)))
                      yield bytes
                    }
                  def observe(
                      proposal: Proposal,
                      certificate: QuorumCertificate,
                  ): Result[IO, Unit] = under(false) {
                    current
                      .flatMap(before =>
                        append(
                          before,
                          HistoricalSafetyOperation.ObserveQc,
                          Some(proposal),
                          Some(certificate),
                          None,
                          ByteVector.empty,
                        ),
                      )
                      .void
                  }
                  def fence(boundary: Height): Result[IO, Unit] = under(false) {
                    current
                      .flatMap(before =>
                        append(
                          before,
                          HistoricalSafetyOperation.Fence,
                          None,
                          None,
                          Some(boundary),
                          ByteVector.empty,
                        ),
                      )
                      .void
                  }
                  def authenticateVote(
                      request: Bytes,
                      signerId: Text,
                      publicKey: Bytes,
                  ): Result[IO, ControllerSigningMaterial] = under(false) {
                    for
                      record <- core(
                        HistoricalSafetyRecord.codec.decode(request),
                      )
                      before <- current
                      _      <- check(
                        signerId == profile.voter && record.operation == HistoricalSafetyOperation.VoteIntent && before.state.voteIntents
                          .contains(record),
                        "controller vote lacks its original forced safe-vote record",
                      )
                      proposal <- EitherT.fromOption[IO](
                        record.proposal,
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.ProofInvalid,
                          "original vote proposal missing",
                        ),
                      )
                      set <- EitherT(
                        validators.validatorSetFor(proposal.window),
                      ).leftMap(error =>
                        V2RuntimeFailure
                          .at(RuntimeFailureCode.ProofUnavailable, error.reason),
                      )
                      member <- EitherT.fromOption[IO](
                        set.members.find(_.id.value == signerId.asString),
                        V2RuntimeFailure.at(
                          RuntimeFailureCode.ProofInvalid,
                          "historical voter is not a validator",
                        ),
                      )
                      _ <- check(
                        ByteEncoder[org.sigilaris.core.crypto.PublicKey]
                          .encode(member.publicKey) == publicKey,
                        "controller key differs from original historical validator",
                      )
                      _ <- check(
                        before.state.fenceBoundary.forall(
                          _.toBigNat.toBigInt > proposal.window.height.toBigNat.toBigInt,
                        ),
                        "historical fence prohibits reissuing this old vote",
                      )
                    yield ControllerSigningMaterial(
                      profile.context,
                      ControllerSigningKind.Consensus,
                      Some(InclusionHeight(proposal.window.height.toBigNat)),
                      record.signBytes,
                    )
                  }
                  def authenticateProposal(
                      proposal: Proposal,
                      signerId: Text,
                      publicKey: Bytes,
                  ): Result[IO, ControllerSigningMaterial] = for
                    bytes <- under(false) {
                      for
                        before <- current
                        record <- EitherT.fromOption[IO](
                          before.state.voteIntents
                            .find(_.proposal.contains(proposal)),
                          V2RuntimeFailure.at(
                            RuntimeFailureCode.ProofUnavailable,
                            "actual proposal has no forced original safe-vote record",
                          ),
                        )
                        encoded <- core(
                          HistoricalSafetyRecord.codec.encode(record),
                        )
                      yield encoded
                    }
                    material <- authenticateVote(bytes, signerId, publicKey)
                  yield material
                  def authorizeProposal(
                      proposal: Proposal,
                      signerId: Text,
                      publicKey: Bytes,
                  ): Result[IO, Unit] = for
                    _ <- authenticateProposal(proposal, signerId, publicKey)
                    _ <- under(false) {
                      current.flatMap(before =>
                        check(
                          before.state.voterWatermark.contains(proposal.window),
                          "a newer original safe-vote decision superseded this unfinished key use",
                        ),
                      )
                    }
                  yield ()
                  def recover
                      : Result[IO, HistoricalConsensusSafety.VerifiedSafety] =
                    under(true) {
                      current.flatTap(_ => EitherT.liftF(ready.set(true)))
                    }
                  def archive: Result[IO, HistoricalSafetyArchive] =
                    under(false) {
                      current
                        .flatMap(
                          _.state.originalRecords.traverse(row =>
                            core(HistoricalSafetyRecord.codec.encode(row)),
                          ),
                        )
                        .map(HistoricalSafetyArchive(profile, _))
                    }
                Resource.eval(required(store.recover)).as(store)
              }
          }
      }
