package org.sigilaris.node.jvm.runtime.application.v2

import java.util.UUID

import cats.Monad
import cats.data.EitherT
import cats.effect.kernel.Async
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.v2.*
import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.txpipeline.v2.*

trait ExactPlanStore[F[_]]:
  def get(nodePipelineId: Text): Result[F, ExactPipelineRecord]
  def snapshot: Result[F, ExactPipelineSnapshot]

/** An alias changes no signed work or stage lifecycle. Its immutable evidence
  * is selected by a no-op ExactLifecycle record in the authoritative journal.
  */
private[v2] final case class ExactAliasEvidence(
    schema: Long,
    context: DomainContext,
    owner: IdempotencyOwner,
    priorRecordDigest: Hash,
)

private[v2] object ExactAliasEvidence:
  val namespace: Text = Utf8("exact-evidence")
  val domain: Text    = Utf8("sigilaris.tx-pipeline.exact.alias.v3")
  given ByteEncoder[ExactAliasEvidence]         = ByteEncoder.derived
  given ByteDecoder[ExactAliasEvidence]         = ByteDecoder.derived
  val codec: CanonicalCodec[ExactAliasEvidence] =
    CanonicalCodec.derived(value =>
      for
        _ <- V2Validation.format(value.schema, 3L, "exactAlias.schema")
        _ <- DomainContext.validateActive(value.context)
        _ <- IdempotencyOwner.validate(value.owner)
      yield (),
    )
  def digest(value: ExactAliasEvidence): Either[CoreFailure, Hash] =
    codec.encode(value).map(Commitment.hash(domain, _))

@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
private[v2] object ExactJournalView:
  private def core[F[_]: Monad, A](
      value: Either[CoreFailure, A],
  ): Result[F, A] =
    EitherT.fromEither[F](RuntimeCheck.core(value))
  private def check[F[_]: Monad](
      condition: Boolean,
      detail: String,
  ): Result[F, Unit] =
    EitherT.fromEither[F](
      RuntimeCheck.require(condition, RuntimeFailureCode.JournalCorrupt, detail),
    )

  /** Reads every no-op update, including aliases predating later lifecycle
    * updates. The last-record projection alone cannot recover alias ownership.
    */
  def snapshot[F[_]: Monad](
      context: DomainContext,
      state: SafetyState,
      journal: DurableJournal[F],
  ): Result[F, ExactPipelineSnapshot] =
    for
      records <- EitherT.fromEither[F](
        ExactSafetyJournalReduction.records(state),
      )
      initial <- records.traverse(record =>
        core[F, Hash](ExactSubmitRequest.digest(record.request)).map(digest =>
          record.request.idempotencyKey.toList.toVector.map(key =>
            IdempotencyOwner(key, digest, record.binding.nodePipelineId),
          ),
        ),
      )
      payloads <- state.committed.traverse(record =>
        core[F, JournalPayload](JournalPayload.codec.decode(record.payload))
          .map(record.operation -> _),
      )
      aliases <- payloads
        .flatMap((operation, payload) =>
          payload.exactRecordUpdates.map(operation -> _),
        )
        .traverse((operation, update) =>
          for
            next <- core[F, ExactPipelineRecord](
              ExactPipelineRecord.codec.decode(update.canonicalNextRecord),
            )
            digest <- core[F, Hash](ExactPipelineRecord.digest(next))
            alias  <-
              if digest != update.priorRecordDigest then
                EitherT
                  .pure[F, V2RuntimeFailure](Option.empty[IdempotencyOwner])
              else
                for
                  _ <- check[F](
                    operation == JournalOperation.ExactLifecycle,
                    "exact alias is not selected by an ExactLifecycle operation",
                  )
                  bytes <- journal.readBlob(
                    ExactAliasEvidence.namespace,
                    update.evidenceDigest,
                  )
                  evidence <- core[F, ExactAliasEvidence](
                    ExactAliasEvidence.codec.decode(bytes),
                  )
                  actual  <- core[F, Hash](ExactAliasEvidence.digest(evidence))
                  request <- core[F, Hash](
                    ExactSubmitRequest.digest(next.request),
                  )
                  _ <- check[F](
                    actual == update.evidenceDigest && evidence.context == context &&
                      evidence.priorRecordDigest == update.priorRecordDigest &&
                      evidence.owner.nodePipelineId == next.binding.nodePipelineId &&
                      evidence.owner.requestDigest == request,
                    "exact alias evidence does not bind the immutable registered work",
                  )
                yield Some(evidence.owner)
          yield alias,
        )
      rows = (initial.flatten ++ aliases.flatten).distinct.sortBy(row =>
        V2Validation.textKey(row.key),
      )
      _ <- check[F](
        rows.map(_.key).distinct.sizeCompare(rows.size) == 0,
        "exact idempotency key has different retained owners",
      )
      ownership <- core[F, (Vector[StageOwner], Vector[OutputOwner])](
        ExactPipelineSnapshot.ownership(records),
      )
      result = ExactPipelineSnapshot(
        3L,
        context,
        records,
        rows,
        ownership._1,
        ownership._2,
      )
      _ <- core[F, Unit](ExactPipelineSnapshot.validate(result))
    yield result

/** Admission and aliases use the same forced journal and gate as voting.
  * Metadata time is not an expiry clock and never changes the signed deadline.
  */
@SuppressWarnings(
  Array("org.wartremover.warts.Equals", "org.wartremover.warts.Nothing"),
)
final class JournalExactPlanStore[F[_]: Async] private (
    private[v2] val safety: JournalSafetyStore[F],
    authentication: ExactPlanAuthentication,
    manifest: ProtocolManifest,
) extends ExactPlanStore[F]:
  private def core[A](value: Either[CoreFailure, A]): Result[F, A] =
    EitherT.fromEither[F](RuntimeCheck.core(value))
  private def check(
      condition: Boolean,
      code: RuntimeFailureCode,
      detail: String,
  ): Result[F, Unit] =
    EitherT.fromEither[F](RuntimeCheck.require(condition, code, detail))

  def snapshot: Result[F, ExactPipelineSnapshot] = safety.transaction(state =>
    ExactJournalView.snapshot(safety.context, state, safety.journal),
  )

  def get(nodePipelineId: Text): Result[F, ExactPipelineRecord] =
    snapshot.flatMap(value =>
      EitherT.fromOption[F](
        value.records.find(_.binding.nodePipelineId == nodePipelineId),
        V2RuntimeFailure.at(
          RuntimeFailureCode.ProofUnavailable,
          "registered exact pipeline is unavailable",
        ),
      ),
    )

  def admit(request: ExactSubmitRequest): Result[F, ExactPipelineRecord] =
    for
      _             <- core(ExactSubmitRequest.validate(request))
      authenticated <- core(authentication.verify(request.signedPlan, manifest))
      signedDigest  <- core(SignedExactPlan.digest(request.signedPlan))
      _             <- check(
        authenticated.signedPlan == request.signedPlan && authenticated.signedPlanDigest == signedDigest,
        RuntimeFailureCode.InvalidRequest,
        "exact authentication returned another signed plan",
      )
      _ <- check(
        request.signedPlan.intent.context == safety.context,
        RuntimeFailureCode.DomainMismatch,
        "exact admission differs from the installed domain",
      )
      requestDigest <- core(ExactSubmitRequest.digest(request))
      result        <- safety.transaction(state =>
        for
          current <- ExactJournalView.snapshot(
            safety.context,
            state,
            safety.journal,
          )
          _ <- request.idempotencyKey.traverse_(key =>
            check(
              current.idempotency
                .find(_.key == key)
                .forall(_.requestDigest == requestDigest),
              RuntimeFailureCode.Conflict,
              "idempotency key already belongs to different signed work",
            ),
          )
          existing <- current.records.traverse(record =>
            core(ExactSubmitRequest.digest(record.request)).map(_ -> record),
          )
          record <- existing.find(_._1 == requestDigest) match
            case Some((_, existingRecord)) =>
              request.idempotencyKey match
                case Some(key) if !current.idempotency.exists(_.key == key) =>
                  for
                    prior <- core(ExactPipelineRecord.digest(existingRecord))
                    alias = ExactAliasEvidence(
                      3L,
                      safety.context,
                      IdempotencyOwner(
                        key,
                        requestDigest,
                        existingRecord.binding.nodePipelineId,
                      ),
                      prior,
                    )
                    digest <- core(ExactAliasEvidence.digest(alias))
                    bytes  <- core(ExactAliasEvidence.codec.encode(alias))
                    _      <- safety.retainBlob(
                      ExactAliasEvidence.namespace,
                      digest,
                      bytes,
                    )
                    update <- core(
                      ExactJournalRecords.update(
                        existingRecord,
                        existingRecord,
                        digest,
                      ),
                    )
                    _ <- safety.persist(
                      state,
                      JournalOperation.ExactLifecycle,
                      JournalPayload.empty.copy(exactRecordUpdates =
                        Vector(update),
                      ),
                    )
                  yield existingRecord
                case _ => EitherT.pure[F, V2RuntimeFailure](existingRecord)
            case None =>
              for
                _ <- safety.verifyNewExactDeadline(
                  request.signedPlan.intent.lastInclusionHeight,
                )
                _ <- check(
                  state.sequence < Long.MaxValue,
                  RuntimeFailureCode.CapacityUnavailable,
                  "exact admission sequence exhausted",
                )
                nodeId <- EitherT.liftF(
                  Async[F].delay(Utf8("exact-" + UUID.randomUUID().toString)),
                )
                millis   <- EitherT.liftF(Async[F].realTime.map(_.toMillis))
                accepted <- core(
                  ExactPipelineRecord.accepted(nodeId, request, millis),
                )
                selected = accepted.copy(admissionJournalSequence =
                  Some(state.sequence + 1L),
                )
                _ <- core(
                  ExactPipelineSnapshot.ownership(
                    (current.records :+ selected).sortBy(record =>
                      V2Validation.textKey(record.binding.nodePipelineId),
                    ),
                  ),
                )
                registration <- core(ExactJournalRecords.registration(selected))
                _            <- safety.persist(
                  state,
                  JournalOperation.ExactRegistration,
                  JournalPayload.empty.copy(exactRegistration =
                    Some(registration),
                  ),
                )
              yield selected
        yield record,
      )
    yield result

object JournalExactPlanStore:
  def journaled[F[_]: Async](
      safety: JournalSafetyStore[F],
      authentication: ExactPlanAuthentication,
      manifest: ProtocolManifest,
  ): JournalExactPlanStore[F] =
    new JournalExactPlanStore(safety, authentication, manifest)
