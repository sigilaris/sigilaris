package org.sigilaris.node.jvm.runtime.application.v2

import cats.Monad
import cats.data.EitherT
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.node.txpipeline.v2.*

/** Completes exact profile validation for actual finalized application,
  * including nodes which never voted on the candidate.
  * ApplicationCommitVerifier must independently replay the same state,
  * inventory, finality and execution evidence. These checks grant neither
  * finality nor ancestry on their own.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactApplicationAuthentication:
  private def core[F[_]: Monad, A](
      value: Either[CoreFailure, A],
  ): Result[F, A] =
    EitherT.fromEither[F](RuntimeCheck.core(value))
  private def check[F[_]: Monad](
      condition: Boolean,
      detail: String,
  ): Result[F, Unit] =
    EitherT.fromEither[F](
      RuntimeCheck.require(condition, RuntimeFailureCode.ProofInvalid, detail),
    )
  private def present[F[_]: Monad, A](
      value: Option[A],
      detail: String,
  ): Result[F, A] =
    EitherT.fromOption[F](
      value,
      V2RuntimeFailure.at(RuntimeFailureCode.ProofUnavailable, detail),
    )

  private def producerOutput[F[_]: Monad](
      authenticated: VerifiedExactPlan,
      result: Bytes,
  ): Result[F, Bytes] = for
    output <- core[F, ExactOutputEvaluation](
      authenticated.producerOutput(result),
    )
    _ <- EitherT.fromEither[F](output.outcome)
  yield output.output

  private def consumer[F[_]: Monad](
      authenticated: VerifiedExactPlan,
      output: Bytes,
      result: Bytes,
  ): Result[F, Unit] =
    core[F, Either[V2RuntimeFailure, Unit]](
      authenticated.consumerAcceptance(output, result),
    )
      .flatMap(EitherT.fromEither[F](_))

  private def result[F[_]: Monad](
      batch: ApplicationBatch,
      execution: ExecutionId,
  ): Result[F, AppliedEntry] =
    present[F, AppliedEntry](
      batch.entries.find(_.executionId == execution),
      "exact application normalized stage result is absent",
    )

  private def priorProducer[F[_]: Monad](
      state: SafetyState,
      record: ExactPipelineRecord,
      batch: ApplicationBatch,
      execution: ExecutionId,
  ): Result[F, AppliedEntry] =
    for
      indexed <- present[F, AppliedIndexRecord](
        state.appliedEntries.get(execution),
        "canonical exact consumer has no prior applied producer",
      )
      prepared <- present[F, PreparedApplication](
        state.preparations.get(indexed.batchDigest),
        "canonical producer original prepared batch is unavailable",
      )
      decision <- present[F, ApplicationDecision](
        state.decisions.get(indexed.batchDigest),
        "canonical producer has no application decision",
      )
      entry <- result[F](prepared.batch, execution)
      _     <- check[F](
        indexed.context == record.binding.context && prepared.batch.context == record.binding.context &&
          indexed.blockId == decision.blockId && indexed.candidateHeight == prepared.batch.candidateHeight &&
          indexed.resultDigest == entry.resultDigest &&
          indexed.candidateHeight.toBigNat.toBigInt < batch.candidateHeight.toBigNat.toBigInt &&
          prepared.batch.candidateHeight.toBigNat.toBigInt <= record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt &&
          entry.lastInclusionHeight == record.request.signedPlan.intent.lastInclusionHeight,
        "canonical producer output is not the earlier application of this exact stage and common deadline",
      )
    yield entry

  private def verifyRecord[F[_]: Monad](
      state: SafetyState,
      record: ExactPipelineRecord,
      batch: ApplicationBatch,
      plan: ExecutionPlan,
      plans: ExactPlanAuthentication,
      manifest: ProtocolManifest,
  ): Result[F, Unit] =
    val selected = plan.waves
      .flatMap(_.entries)
      .filter(entry => record.binding.executionIds.contains(entry.executionId))
    if selected.isEmpty then EitherT.pure[F, V2RuntimeFailure](())
    else
      for
        authenticated <- core[F, VerifiedExactPlan](
          plans.verify(record.request.signedPlan, manifest),
        )
        _ <- check[F](
          authenticated.signedPlan == record.request.signedPlan && authenticated.signedPlanDigest == record.binding.signedPlanDigest,
          "exact application authenticator returned different signed work",
        )
        _ <- core[F, Unit](
          ExactIdentityBinding.verify(record.binding, authenticated.signedPlan),
        )
        _ <- check[F](
          batch.context == record.binding.context && batch.candidateHeight.toBigNat.toBigInt <= record.request.signedPlan.intent.lastInclusionHeight.toBigNat.toBigInt,
          "exact application context or inclusive deadline differs from admission",
        )
        _ <- selected.traverse_(entry =>
          EitherT.fromEither[F](
            ExactLifecycleProjection.verifyEntry(record, entry),
          ),
        )
        producer <- present[F, ExecutionId](
          record.binding.executionIds.headOption,
          "registered exact producer is absent",
        )
        consumerId <- present[F, ExecutionId](
          record.binding.executionIds.lift(1),
          "registered exact consumer is absent",
        )
        _ <- record.request.signedPlan.intent.mode match
          case ExactMode.OrderedAtomic =>
            for
              _ <- check[F](
                selected.map(_.executionId) == Vector(
                  producer,
                  consumerId,
                ) && plan.waves.exists(wave =>
                  wave.kind == WaveKind.Ordered && wave.entries
                    .sliding(2)
                    .exists(
                      _.map(_.executionId) == Vector(producer, consumerId),
                    ),
                ),
                "canonical ordered exact application must contain both consecutive stages in one ordered wave",
              )
              first  <- result[F](batch, producer)
              second <- result[F](batch, consumerId)
              output <- producerOutput[F](authenticated, first.normalizedResult)
              _ <- consumer[F](authenticated, output, second.normalizedResult)
            yield ()
          case ExactMode.CertifiedAncestor =>
            for
              _ <- check[F](
                selected.sizeIs == 1,
                "canonical ancestor producer and consumer cannot share one block",
              )
              stage <- present[F, PlanEntry](
                selected.headOption,
                "canonical ancestor exact stage is absent",
              )
              _ <-
                if stage.executionId == producer then
                  result[F](batch, producer)
                    .flatMap(entry =>
                      producerOutput[F](authenticated, entry.normalizedResult),
                    )
                    .void
                else
                  for
                    _ <- check[F](
                      stage.executionId == consumerId,
                      "unknown exact stage in canonical ancestor batch",
                    )
                    first  <- priorProducer[F](state, record, batch, producer)
                    second <- result[F](batch, consumerId)
                    output <- producerOutput[F](
                      authenticated,
                      first.normalizedResult,
                    )
                    _ <- consumer[F](
                      authenticated,
                      output,
                      second.normalizedResult,
                    )
                  yield ()
            yield ()
      yield ()

  def verify[F[_]: Monad](
      state: SafetyState,
      journal: DurableJournal[F],
      plans: ExactPlanAuthentication,
      manifest: ProtocolManifest,
  ): Result[F, Unit] = for
    records <- EitherT.fromEither[F](ExactSafetyJournalReduction.records(state))
    _       <- state.preparations.toVector
      .sortBy(_._1.bytes.toHex)
      .traverse_((digest, prepared) =>
        for
          _   <- core[F, Unit](PreparedApplication.validate(prepared))
          raw <- journal.readBlob(
            ApplicationBlobStorage.inventoryNamespace,
            prepared.preparedStateInventory,
          )
          actual <- core[F, Hash](ApplicationBlobStorage.inventoryDigest(raw))
          inventory <- core[F, PreparedStateInventory](
            PreparedStateInventory.codec.decode(raw),
          )
          root <- core[
            F,
            org.sigilaris.core.application.protocol.ExecutionPlanRoot,
          ](ExecutionPlan.computeRoot(inventory.plan))
          _ <- check[F](
            actual == prepared.preparedStateInventory && inventory.batchDigest == digest && prepared.batchDigest == digest &&
              inventory.statePayloadDigest == prepared.batch.statePayloadDigest && root.toUInt256 == prepared.batch.planRoot &&
              inventory.plan.waves
                .flatMap(_.entries)
                .map(_.executionId) == prepared.batch.entries
                .map(_.executionId),
            "exact application profile references another canonical prepared plan or batch",
          )
          _ <- records.traverse_(record =>
            verifyRecord[F](
              state,
              record,
              prepared.batch,
              inventory.plan,
              plans,
              manifest,
            ),
          )
        yield (),
      )
  yield ()
