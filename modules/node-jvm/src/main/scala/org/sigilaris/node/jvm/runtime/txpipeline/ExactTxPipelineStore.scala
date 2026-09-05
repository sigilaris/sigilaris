package org.sigilaris.node.jvm.runtime.txpipeline

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import cats.data.EitherT
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.application.protocol.ExecutionId
import org.sigilaris.node.txpipeline.*

trait ExactTxPipelineStore[F[_]]:
  def createOrReplay(
      record: ExactTxPipelineRecord,
  ): EitherT[F, ExactTxPipelineStoreFailure, ExactTxPipelineCreateOutcome]

  def get(
      pipelineId: TxPipelineId,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]]

  def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]]

  def getByRequestIdentity(
      requestIdentity: ExactPipelineRequestIdentity,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]]

  /** Returns a page in implementation-specific order. SwayDB uses serialized
    * key order; the in-memory store uses pipeline-id string order. Callers must
    * not assume the same ordering across stores or snapshot isolation between
    * pages while writes are in progress. Startup reconciliation runs before
    * admission and is order-independent.
    */
  def list(
      offset: Int,
      limit: Int,
  ): EitherT[F, ExactTxPipelineStoreFailure, Vector[ExactTxPipelineRecord]]

  /** Counts admission rows that have not crossed into the application journal.
    *
    * Implementations must perform one bounded primary-key scan rather than
    * repeatedly decoding and sorting every record page.
    */
  def countUnjournaled(
      journaledPipelineIds: Set[TxPipelineId],
      stopAt: Int,
  ): EitherT[F, ExactTxPipelineStoreFailure, Int]

final case class ExactTxPipelineCreateOutcome(
    record: ExactTxPipelineRecord,
    created: Boolean,
)

final case class ExactPipelineRequestIdentity(value: String)

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object ExactPipelineRequestIdentity:
  private val Domain = "sigilaris.tx-pipeline.exact.request-identity.v1"

  def fromRequest(
      request: ExactPipelineNormalizedRequest,
  ): ExactPipelineRequestIdentity =
    digest(
      request.profileId,
      request.profileVersion,
      ExactPipelineCanonical.signedPlanDigest(
        request.signedApplicationPlan,
      ),
      request.transactions.map(_.payload),
    )

  def fromRecord(record: ExactTxPipelineRecord): ExactPipelineRequestIdentity =
    digest(
      record.verifiedPlan.profileId,
      record.verifiedPlan.profileVersion,
      record.verifiedPlan.signedPlanDigest,
      record.genericProjection.stages.flatMap(_.transactions).map(_.payload),
    )

  private def digest(
      profileId: DependencyProfileId,
      profileVersion: DependencyProfileVersion,
      signedPlanDigest: ExactPipelineDigest,
      payloads: Vector[TxPipelineTransactionPayload],
  ): ExactPipelineRequestIdentity =
    val fields: Vector[String] = Vector(
      Domain,
      profileId.value,
      profileVersion.value.toString,
      signedPlanDigest.value,
    ) ++ payloads.map(_.value)
    val canonical = fields
      .map: value =>
        val bytes = value.getBytes(StandardCharsets.UTF_8)
        s"${bytes.length}:$value"
      .mkString("|")
      .getBytes(StandardCharsets.UTF_8)
    ExactPipelineRequestIdentity(
      TxPipelineSha256.hex(
        MessageDigest.getInstance("SHA-256").digest(canonical),
      ),
    )

@SuppressWarnings(Array("org.wartremover.warts.Any"))
enum ExactTxPipelineStoreFailure(val reason: String):
  case PipelineConflict(pipelineId: TxPipelineId)
      extends ExactTxPipelineStoreFailure("exactPipelineBindingMismatch")
  case IdempotencyConflict(
      idempotencyKey: TxPipelineIdempotencyKey,
      existingPipelineId: TxPipelineId,
  ) extends ExactTxPipelineStoreFailure("exactPipelineBindingMismatch")
  case ApplicationPipelineConflict(
      applicationPipelineId: ApplicationPipelineId,
  ) extends ExactTxPipelineStoreFailure("applicationPipelineIdentityMismatch")
  case ExecutionConflict(executionId: ExecutionId)
      extends ExactTxPipelineStoreFailure("crossPipelineDependencyReuse")
  case ReferenceConflict(commitment: OpaqueReferenceCommitment)
      extends ExactTxPipelineStoreFailure("crossPipelineDependencyReuse")
  case DecodeFailed(detail: String)
      extends ExactTxPipelineStoreFailure("incompatibleStoreSchema")

  def diagnosticDetail: String =
    this match
      case PipelineConflict(pipelineId) =>
        s"pipeline ${pipelineId.value} already owns this exact identity"
      case IdempotencyConflict(key, existingPipelineId) =>
        s"idempotency key ${key.value} belongs to ${existingPipelineId.value}"
      case ApplicationPipelineConflict(applicationPipelineId) =>
        s"application pipeline ${applicationPipelineId.value} is already owned"
      case ExecutionConflict(executionId) =>
        s"execution ${executionId.toHexLower} is already owned"
      case ReferenceConflict(commitment) =>
        s"reference commitment ${commitment.value} is already owned"
      case DecodeFailed(detail) => detail

private[jvm] final case class ExactTxPipelineIndexes(
    pipelineIds: Set[TxPipelineId],
    byRequest: Map[ExactPipelineRequestIdentity, TxPipelineId],
    byApplication: Map[ApplicationPipelineId, TxPipelineId],
    byExecution: Map[ExecutionId, TxPipelineId],
    byReference: Map[OpaqueReferenceCommitment, TxPipelineId],
    embeddedIdempotency: Map[
      TxPipelineIdempotencyKey,
      TxPipelineIdempotencyBinding,
    ],
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
private[jvm] object ExactTxPipelineIndexes:
  val empty: ExactTxPipelineIndexes = ExactTxPipelineIndexes(
    Set.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
  )

  def build(
      records: Vector[ExactTxPipelineRecord],
  ): Either[ExactTxPipelineStoreFailure, ExactTxPipelineIndexes] =
    records.foldLeft(
      Right(empty): Either[
        ExactTxPipelineStoreFailure,
        ExactTxPipelineIndexes,
      ],
    )((accumulated, record) => accumulated.flatMap(_.add(record)))

  extension (indexes: ExactTxPipelineIndexes)
    def add(
        record: ExactTxPipelineRecord,
    ): Either[ExactTxPipelineStoreFailure, ExactTxPipelineIndexes] =
      val pipelineId      = record.genericProjection.pipelineId
      val requestId       = ExactPipelineRequestIdentity.fromRecord(record)
      val embeddedBinding = record.genericProjection.idempotencyKey.map: key =>
        key -> TxPipelineIdempotencyBinding(
          pipelineId,
          record.genericProjection.canonicalPayloadHash,
        )
      for
        _ <- ExactTxPipelineStoreValidation.validateDescriptor(record)
        _ <- Either.cond(
          !indexes.pipelineIds.contains(pipelineId),
          (),
          ExactTxPipelineStoreFailure.PipelineConflict(pipelineId),
        )
        _ <- ownerAvailable(indexes.byRequest.get(requestId), pipelineId)
        _ <- indexes.byApplication
          .get(record.verifiedPlan.applicationPipelineId)
          .filter(_ != pipelineId)
          .fold[Either[ExactTxPipelineStoreFailure, Unit]](Right(()))(_ =>
            Left(
              ExactTxPipelineStoreFailure.ApplicationPipelineConflict(
                record.verifiedPlan.applicationPipelineId,
              ),
            ),
          )
        _ <- record.verifiedPlan.orderedExecutionIds.traverse_ { executionId =>
          indexes.byExecution
            .get(executionId)
            .filter(_ != pipelineId)
            .fold[Either[ExactTxPipelineStoreFailure, Unit]](Right(()))(_ =>
              Left(ExactTxPipelineStoreFailure.ExecutionConflict(executionId)),
            )
        }
        _ <- indexes.byReference
          .get(record.verifiedPlan.referenceCommitment)
          .filter(_ != pipelineId)
          .fold[Either[ExactTxPipelineStoreFailure, Unit]](Right(()))(_ =>
            Left(
              ExactTxPipelineStoreFailure.ReferenceConflict(
                record.verifiedPlan.referenceCommitment,
              ),
            ),
          )
        _ <- embeddedBinding match
          case None                 => Right(())
          case Some((key, binding)) =>
            indexes.embeddedIdempotency.get(key) match
              case None           => Right(())
              case Some(existing) =>
                Either.cond(
                  existing == binding,
                  (),
                  ExactTxPipelineStoreFailure.IdempotencyConflict(
                    key,
                    existing.pipelineId,
                  ),
                )
      yield indexes.copy(
        pipelineIds = indexes.pipelineIds + pipelineId,
        byRequest = indexes.byRequest.updated(requestId, pipelineId),
        byApplication = indexes.byApplication.updated(
          record.verifiedPlan.applicationPipelineId,
          pipelineId,
        ),
        byExecution = indexes.byExecution ++
          record.verifiedPlan.orderedExecutionIds.map(_ -> pipelineId),
        byReference = indexes.byReference.updated(
          record.verifiedPlan.referenceCommitment,
          pipelineId,
        ),
        embeddedIdempotency = (embeddedBinding match
          case None                 => indexes.embeddedIdempotency
          case Some((key, binding)) =>
            indexes.embeddedIdempotency.updated(key, binding)
        ),
      )

  private def ownerAvailable(
      existing: Option[TxPipelineId],
      pipelineId: TxPipelineId,
  ): Either[ExactTxPipelineStoreFailure, Unit] =
    existing.filter(_ != pipelineId) match
      case None                => Right(())
      case Some(existingOwner) =>
        Left(ExactTxPipelineStoreFailure.PipelineConflict(existingOwner))

private[jvm] final case class ExactTxPipelineIndexedCreate(
    outcome: ExactTxPipelineCreateOutcome,
    idempotencyBinding: Option[TxPipelineIdempotencyBinding],
    indexes: ExactTxPipelineIndexes,
)

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
object ExactTxPipelineStoreValidation:
  private[jvm] def resolveIdempotencyRecord(
      binding: TxPipelineIdempotencyBinding,
      record: Option[ExactTxPipelineRecord],
  ): Either[ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    record match
      case None => Right(None)
      case Some(value)
          if value.genericProjection.canonicalPayloadHash ==
            binding.canonicalPayloadHash =>
        Right(Some(value))
      case Some(_) =>
        Left(
          ExactTxPipelineStoreFailure.DecodeFailed(
            s"exact idempotency hash mismatch for ${binding.pipelineId.value}",
          ),
        )

  def createOrReplay(
      records: Vector[ExactTxPipelineRecord],
      idempotency: Map[TxPipelineIdempotencyKey, TxPipelineIdempotencyBinding],
      candidate: ExactTxPipelineRecord,
  ): Either[
    ExactTxPipelineStoreFailure,
    (ExactTxPipelineCreateOutcome, Option[TxPipelineIdempotencyBinding]),
  ] =
    ExactTxPipelineIndexes
      .build(records)
      .flatMap(indexes =>
        createOrReplayIndexed(
          indexes,
          idempotency,
          records.find(
            _.genericProjection.pipelineId ==
              candidate.genericProjection.pipelineId,
          ),
          candidate,
        ),
      )
      .map(result => result.outcome -> result.idempotencyBinding)

  private[jvm] def createOrReplayIndexed(
      indexes: ExactTxPipelineIndexes,
      idempotency: Map[TxPipelineIdempotencyKey, TxPipelineIdempotencyBinding],
      existing: Option[ExactTxPipelineRecord],
      candidate: ExactTxPipelineRecord,
  ): Either[ExactTxPipelineStoreFailure, ExactTxPipelineIndexedCreate] =
    for
      _ <- validateDescriptor(candidate)
      idempotencyBinding = candidate.genericProjection.idempotencyKey.map:
        key =>
          key -> TxPipelineIdempotencyBinding(
            candidate.genericProjection.pipelineId,
            candidate.genericProjection.canonicalPayloadHash,
          )
      _ <- validateIdempotency(idempotency, idempotencyBinding)
      _ <- validateIdempotency(
        indexes.embeddedIdempotency,
        idempotencyBinding,
      )
      result <- existing match
        case Some(value) =>
          for
            _ <- Either.cond(
              indexes.pipelineIds.contains(
                candidate.genericProjection.pipelineId,
              ),
              (),
              ExactTxPipelineStoreFailure.DecodeFailed(
                s"exact primary ${candidate.genericProjection.pipelineId.value} is missing from its derived index",
              ),
            )
            outcome <- Either.cond(
              sameSubmission(value, candidate),
              ExactTxPipelineCreateOutcome(value, created = false),
              ExactTxPipelineStoreFailure.PipelineConflict(
                candidate.genericProjection.pipelineId,
              ),
            )
          yield outcome -> indexes
        case None =>
          indexes
            .add(candidate)
            .map(
              ExactTxPipelineCreateOutcome(candidate, created = true) -> _,
            )
      (outcome, nextIndexes) = result
    yield ExactTxPipelineIndexedCreate(
      outcome,
      idempotencyBinding.map(_._2),
      nextIndexes,
    )

  def validateDescriptor(
      candidate: ExactTxPipelineRecord,
  ): Either[ExactTxPipelineStoreFailure, Unit] =
    for
      _ <- ExactTxPipelineRecord
        .validate(candidate)
        .leftMap(ExactTxPipelineStoreFailure.DecodeFailed.apply)
      _ <- VerifiedExactDependencyPlan
        .validateCommitments(candidate.verifiedPlan)
        .leftMap(ExactTxPipelineStoreFailure.DecodeFailed.apply)
      _ <- ExactPipelineIdentityBinding
        .validate(candidate.identityBinding)
        .leftMap(ExactTxPipelineStoreFailure.DecodeFailed.apply)
      stages       = candidate.genericProjection.stages
      transactions = stages.flatMap(_.transactions)
      _ <- Either.cond(
        stages.sizeCompare(1) == 0 &&
          stages.headOption.exists(_.stageIndex == 0) &&
          transactions.map(_.transactionIndex) == Vector(0, 1),
        (),
        ExactTxPipelineStoreFailure.DecodeFailed(
          "exact pipeline projection must contain one canonical stage with two transactions",
        ),
      )
    yield ()

  def sameSubmission(
      existing: ExactTxPipelineRecord,
      candidate: ExactTxPipelineRecord,
  ): Boolean =
    existing.genericProjection.canonicalPayloadHash ==
      candidate.genericProjection.canonicalPayloadHash &&
      immutableTransactions(existing) == immutableTransactions(candidate) &&
      existing.verifiedPlan == candidate.verifiedPlan &&
      existing.identityBinding == candidate.identityBinding

  private def immutableTransactions(
      record: ExactTxPipelineRecord,
  ): Vector[(Int, Int, TxPipelineTransactionPayload, TxPipelineTxHash)] =
    record.genericProjection.stages.flatMap: stage =>
      stage.transactions.map: transaction =>
        (
          stage.stageIndex,
          transaction.transactionIndex,
          transaction.payload,
          transaction.txHash,
        )

  private def validateIdempotency(
      idempotency: Map[TxPipelineIdempotencyKey, TxPipelineIdempotencyBinding],
      candidate: Option[
        (TxPipelineIdempotencyKey, TxPipelineIdempotencyBinding),
      ],
  ): Either[ExactTxPipelineStoreFailure, Unit] =
    candidate match
      case None                 => Right(())
      case Some((key, binding)) =>
        idempotency.get(key) match
          case None => Right(())
          case Some(existing)
              if existing.pipelineId == binding.pipelineId &&
                existing.canonicalPayloadHash == binding.canonicalPayloadHash =>
            Right(())
          case Some(existing) =>
            Left(
              ExactTxPipelineStoreFailure.IdempotencyConflict(
                key,
                existing.pipelineId,
              ),
            )

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class InMemoryExactTxPipelineStore[F[_]: Sync] private (
    state: Ref[F, InMemoryExactTxPipelineStore.State],
) extends ExactTxPipelineStore[F]:
  override def createOrReplay(
      record: ExactTxPipelineRecord,
  ): EitherT[F, ExactTxPipelineStoreFailure, ExactTxPipelineCreateOutcome] =
    EitherT:
      state.modify: current =>
        ExactTxPipelineStoreValidation.createOrReplayIndexed(
          current.indexes,
          current.idempotency,
          current.records.get(record.genericProjection.pipelineId),
          record,
        ) match
          case Left(failure) => current -> Left(failure)
          case Right(result) =>
            val key             = record.genericProjection.idempotencyKey
            val nextIdempotency = (key, result.idempotencyBinding) match
              case (Some(value), Some(index)) =>
                current.idempotency.updated(value, index)
              case _ => current.idempotency
            val nextRecords =
              if result.outcome.created then
                current.records.updated(
                  record.genericProjection.pipelineId,
                  record,
                )
              else current.records
            InMemoryExactTxPipelineStore.State(
              nextRecords,
              result.indexes,
              nextIdempotency,
            ) -> Right(result.outcome)

  override def get(
      pipelineId: TxPipelineId,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    EitherT.right(state.get.map(_.records.get(pipelineId)))

  override def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    EitherT:
      state.get.map: current =>
        current.idempotency.get(idempotencyKey) match
          case None          => Right(None)
          case Some(binding) =>
            ExactTxPipelineStoreValidation.resolveIdempotencyRecord(
              binding,
              current.records.get(binding.pipelineId),
            )

  override def getByRequestIdentity(
      requestIdentity: ExactPipelineRequestIdentity,
  ): EitherT[F, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    EitherT.right:
      state.get.map: current =>
        current.indexes.byRequest
          .get(requestIdentity)
          .flatMap(current.records.get)

  override def list(
      offset: Int,
      limit: Int,
  ): EitherT[F, ExactTxPipelineStoreFailure, Vector[ExactTxPipelineRecord]] =
    EitherT.right:
      state.get.map: current =>
        if limit <= 0 then Vector.empty
        else
          current.records.values.toVector
            .sortBy(_.genericProjection.pipelineId.value)
            .slice(offset.max(0), offset.max(0) + limit)

  override def countUnjournaled(
      journaledPipelineIds: Set[TxPipelineId],
      stopAt: Int,
  ): EitherT[F, ExactTxPipelineStoreFailure, Int] =
    EitherT.right:
      state.get.map: current =>
        if stopAt <= 0 then 0
        else
          current.records.keysIterator
            .filterNot(journaledPipelineIds.contains)
            .take(stopAt)
            .size

object InMemoryExactTxPipelineStore:
  private final case class State(
      records: Map[TxPipelineId, ExactTxPipelineRecord],
      indexes: ExactTxPipelineIndexes,
      idempotency: Map[TxPipelineIdempotencyKey, TxPipelineIdempotencyBinding],
  )

  private object State:
    val empty: State = State(Map.empty, ExactTxPipelineIndexes.empty, Map.empty)

  def create[F[_]: Sync]: F[InMemoryExactTxPipelineStore[F]] =
    Ref.of[F, State](State.empty).map(new InMemoryExactTxPipelineStore(_))
