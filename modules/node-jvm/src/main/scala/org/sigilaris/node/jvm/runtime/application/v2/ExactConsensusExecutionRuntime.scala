package org.sigilaris.node.jvm.runtime.application.v2

import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.*

type PreparedExactCandidate =
  ExactConsensusExecutionRuntime.PreparedExactCandidate

final case class ExactPipelineRecovery(
    snapshot: ExactPipelineSnapshot,
    journalSequence: Long,
    safetyInventoryDigest: Hash,
    indexDigest: Hash,
)

trait ExactConsensusExecutionRuntime[F[_]]:
  def admit(request: ExactSubmitRequest): Result[F, ExactPipelineRecord]
  def executeOrdered(
      request: VerifiedOrderedExecution,
  ): Result[F, PreparedExactCandidate]
  def executeProducer(
      request: VerifiedProducerExecution,
  ): Result[F, PreparedExactCandidate]
  def executeConsumer(
      request: VerifiedConsumerExecution,
  ): Result[F, PreparedExactCandidate]
  def recover: Result[F, ExactPipelineRecovery]

/** Candidate preparation retains safety promises and immutable working results.
  * Actual finality later enters RecoverableApplicationStore; candidate
  * execution cannot use its canonical overlap exception or mark an application
  * field.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
object ExactConsensusExecutionRuntime:
  final class PreparedExactCandidate private[ExactConsensusExecutionRuntime] (
      val record: ExactPipelineRecord,
      val verifiedProposal: VerifiedConsensusProposal,
      val consensusIntentDigest: Hash,
      val executionIds: Vector[ExecutionId],
  ):
    def workingStateRoot: Hash           = verifiedProposal.validatedStateRoot
    def normalizedResults: Vector[Bytes] = verifiedProposal.normalizedResults

  def journaled[F[_]: Async](
      store: JournalExactPlanStore[F],
      requests: ExactExecutionRequestVerifier[F],
      validatorId: Text,
  ): ExactConsensusExecutionRuntime[F] = new ExactConsensusExecutionRuntime[F]:
    private val safety = store.safety
    private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
      EitherT.fromEither[F](RuntimeCheck.core(value))
    private def check(condition: Boolean, detail: String): Result[F, Unit] =
      EitherT.fromEither[F](
        RuntimeCheck.require(
          condition,
          RuntimeFailureCode.InvalidRequest,
          detail,
        ),
      )

    def admit(request: ExactSubmitRequest): Result[F, ExactPipelineRecord] =
      store.admit(request)

    private def execute(
        registered: ExactPipelineRecord,
        proposal: VerifiedConsensusProposal,
        locks: Vector[VerifiedLockCertificate],
        effects: Vector[VerifiedEffectCertificate],
        outcome: Either[V2RuntimeFailure, Unit],
    ): Result[F, PreparedExactCandidate] =
      for
        // Certificate archival grants no local voting permission. The ordinary
        // reciprocal acquisition below still checks all retained constraints.
        _            <- locks.traverse_(safety.importLock)
        _            <- effects.traverse_(safety.importEffect)
        intent       <- safety.claimConsensus(proposal, validatorId)
        intentDigest <- core(ConsensusVoteIntent.digest(intent))
        selected     <- safety.transaction(state =>
          for
            records <- EitherT.fromEither[F](
              ExactSafetyJournalReduction.records(state),
            )
            current <- EitherT.fromOption[F](
              records.find(
                _.binding.nodePipelineId == registered.binding.nodePipelineId,
              ),
              V2RuntimeFailure.at(
                RuntimeFailureCode.RecoveryRequired,
                "exact execution lost its registered admission",
              ),
            )
            _ <- check(
              current.binding == registered.binding && current.request == registered.request,
              "verified exact execution differs from its current immutable registration",
            )
            entries = proposal.plan.waves
              .flatMap(_.entries)
              .filter(entry =>
                current.binding.executionIds.contains(entry.executionId),
              )
            ids = entries.map(_.executionId)
            _ <- safety.revokeExactReadinessUnderGate(intentDigest, ids)
            _ <- check(
              ids.nonEmpty && ids.forall(current.binding.executionIds.contains),
              "candidate includes an unowned exact execution",
            )
            _ <- check(
              proposal.reservations
                .filter(reservation =>
                  ids.contains(reservation.owner.executionId),
                )
                .forall(reservation =>
                  reservation.owner.context == current.binding.context &&
                    reservation.lastInclusionHeight == current.request.signedPlan.intent.lastInclusionHeight,
                ),
              "exact reservation context or common deadline differs from its signed plan",
            )
            stages <- current.stages.traverse(stage =>
              entries.find(_.executionId == stage.executionId) match
                case None        => EitherT.pure[F, V2RuntimeFailure](stage)
                case Some(entry) =>
                  for
                    _ <- check(
                      stage.firstApplicationBlock.isEmpty &&
                        stage.lifecycle != ExactStageLifecycle.ExpiredUnapplied,
                      "terminal exact execution cannot return to candidate reservation",
                    )
                    reservation <- EitherT.fromOption[F](
                      proposal.reservations.find(
                        _.owner.executionId == stage.executionId,
                      ),
                      V2RuntimeFailure.at(
                        RuntimeFailureCode.IncompleteWitness,
                        "exact candidate has no complete stage reservation",
                      ),
                    )
                    owner <- core(Owner.digest(reservation.owner))
                    _     <- check(
                      state.claims
                        .get(owner)
                        .exists(claim =>
                          claim.lifecycle == ClaimLifecycle.Live &&
                            claim.lastInclusionHeight == current.request.signedPlan.intent.lastInclusionHeight,
                        ),
                      "exact candidate reservation ceased to be live before lifecycle publication",
                    )
                    certificates = entry.source match
                      case PlanSource.ConsensusTransaction(_, _, lock) =>
                        (lock, Option.empty[Hash])
                      case PlanSource
                            .CertifiedFastExecution(_, _, lock, effect) =>
                        (Some(lock), Some(effect))
                  yield stage.copy(
                    lifecycle = outcome.fold(
                      _ => ExactStageLifecycle.Failed,
                      _ => ExactStageLifecycle.Reserved,
                    ),
                    inputLockCertificateId =
                      stage.inputLockCertificateId.orElse(certificates._1),
                    effectCertificateId =
                      stage.effectCertificateId.orElse(certificates._2),
                  ),
            )
            next = current.copy(stages = stages)
            _ <-
              if current == next then EitherT.pure[F, V2RuntimeFailure](())
              else
                for
                  update <- core(
                    ExactJournalRecords.update(current, next, intentDigest),
                  )
                  _ <- safety.persist(
                    state,
                    JournalOperation.ExactLifecycle,
                    JournalPayload.empty
                      .copy(exactRecordUpdates = Vector(update)),
                  )
                yield ()
          yield new PreparedExactCandidate(next, proposal, intentDigest, ids),
        )
        _ <- EitherT.fromEither[F](outcome)
        _ <- safety.authorizeExactCandidate(selected)
      yield selected

    def executeOrdered(
        request: VerifiedOrderedExecution,
    ): Result[F, PreparedExactCandidate] =
      requests
        .ordered(request.record.binding.nodePipelineId, request.candidate)
        .flatMap(fresh =>
          check(
            fresh.record.binding == request.record.binding &&
              fresh.verifiedProposal.proposal == request.verifiedProposal.proposal,
            "ordered exact candidate changed since verification",
          ) *>
            execute(
              fresh.record,
              fresh.verifiedProposal,
              fresh.lockCertificates,
              fresh.effectCertificates,
              fresh.outcome,
            ),
        )

    def executeProducer(
        request: VerifiedProducerExecution,
    ): Result[F, PreparedExactCandidate] =
      requests
        .producer(request.record.binding.nodePipelineId, request.candidate)
        .flatMap(fresh =>
          check(
            fresh.record.binding == request.record.binding &&
              fresh.verifiedProposal.proposal == request.verifiedProposal.proposal,
            "producer exact candidate changed since verification",
          ) *>
            execute(
              fresh.record,
              fresh.verifiedProposal,
              fresh.lockCertificates,
              fresh.effectCertificates,
              fresh.outcome,
            ),
        )

    def executeConsumer(
        request: VerifiedConsumerExecution,
    ): Result[F, PreparedExactCandidate] =
      requests
        .consumer(request.record.binding.nodePipelineId, request.candidate)
        .flatMap(fresh =>
          check(
            fresh.record.binding == request.record.binding &&
              fresh.verifiedProposal.proposal == request.verifiedProposal.proposal,
            "consumer exact candidate changed since verification",
          ) *>
            execute(
              fresh.record,
              fresh.verifiedProposal,
              fresh.lockCertificates,
              fresh.effectCertificates,
              fresh.outcome,
            ),
        )

    def recover: Result[F, ExactPipelineRecovery] =
      safety.recover *> safety.transaction(state =>
        for
          snapshot <- ExactJournalView
            .snapshot(safety.context, state, safety.journal)
          head  <- EitherT.fromEither[F](SafetyJournalReduction.head(state))
          index <- core(ConflictIndexRow.inventoryDigest(state.index))
        yield ExactPipelineRecovery(
          snapshot,
          state.sequence,
          SafetyInventory.digest(head, index),
          index,
        ),
      )
