package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import io.circe.parser.decode
import io.circe.syntax.*

import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.runtime.txpipeline.*
import org.sigilaris.node.jvm.storage.{KeyValueStore, StoreIndex}
import org.sigilaris.node.txpipeline.*

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.Nothing",
  ),
)
final class SwayDbExactTxPipelineStore private[swaydb] (
    records: StoreIndex[IO, Utf8, Utf8],
    idempotency: KeyValueStore[IO, Utf8, Utf8],
    indexes: Ref[IO, Option[ExactTxPipelineIndexes]],
    gate: Semaphore[IO],
) extends ExactTxPipelineStore[IO]:
  override def createOrReplay(
      record: ExactTxPipelineRecord,
  ): EitherT[IO, ExactTxPipelineStoreFailure, ExactTxPipelineCreateOutcome] =
    EitherT:
      gate.permit.use: _ =>
        IO.uncancelable(_ => createUnderGate(record).value)

  override def get(
      pipelineId: TxPipelineId,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    records
      .get(Utf8(pipelineId.value))
      .leftMap { value =>
        ExactTxPipelineStoreFailure.DecodeFailed(value.msg)
      }
      .flatMap:
        case None        => EitherT.rightT(None)
        case Some(value) =>
          EitherT.fromEither[IO](
            decodeRecord(Utf8(pipelineId.value), value).map(Some(_)),
          )

  override def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    loadIdempotencyBinding(idempotencyKey).flatMap:
      case None          => EitherT.rightT(None)
      case Some(binding) =>
        get(binding.pipelineId).flatMap(record =>
          EitherT.fromEither[IO](
            ExactTxPipelineStoreValidation.resolveIdempotencyRecord(
              binding,
              record,
            ),
          ),
        )

  override def getByRequestIdentity(
      requestIdentity: ExactPipelineRequestIdentity,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    EitherT:
      gate.permit.use(_ => getByRequestIdentityUnderGate(requestIdentity).value)

  private def getByRequestIdentityUnderGate(
      requestIdentity: ExactPipelineRequestIdentity,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Option[ExactTxPipelineRecord]] =
    loadIndexes.flatMap: current =>
      current.byRequest.get(requestIdentity) match
        case None             => EitherT.rightT(None)
        case Some(pipelineId) =>
          get(pipelineId).flatMap:
            case value @ Some(_) => EitherT.rightT(value)
            case None            =>
              EitherT.leftT(
                ExactTxPipelineStoreFailure.DecodeFailed(
                  s"exact request index points to missing pipeline ${pipelineId.value}",
                ),
              )

  override def list(
      offset: Int,
      limit: Int,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Vector[ExactTxPipelineRecord]] =
    if limit <= 0 then EitherT.rightT(Vector.empty)
    else
      records
        .from(Utf8(""), offset.max(0), limit)
        .leftMap(value => ExactTxPipelineStoreFailure.DecodeFailed(value.msg))
        .flatMap(values =>
          EitherT.fromEither[IO](
            values.toVector.traverse(value => decodeRecord(value._1, value._2)),
          ),
        )

  override def countUnjournaled(
      journaledPipelineIds: Set[TxPipelineId],
      stopAt: Int,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Int] =
    if stopAt <= 0 then EitherT.rightT(0)
    else
      val scanLimit =
        (journaledPipelineIds.size.toLong + stopAt.toLong)
          .min(Int.MaxValue.toLong)
          .toInt
      records
        .from(Utf8(""), 0, scanLimit)
        .leftMap(value => ExactTxPipelineStoreFailure.DecodeFailed(value.msg))
        .map(
          _.iterator
            .map((key, _) => TxPipelineId(key.asString))
            .filterNot(journaledPipelineIds.contains)
            .take(stopAt)
            .size,
        )

  private def createUnderGate(
      record: ExactTxPipelineRecord,
  ): EitherT[IO, ExactTxPipelineStoreFailure, ExactTxPipelineCreateOutcome] =
    for
      currentIndexes  <- loadIndexes
      existing        <- get(record.genericProjection.pipelineId)
      existingBinding <- record.genericProjection.idempotencyKey match
        case Some(key) => loadIdempotencyBinding(key)
        case None      => EitherT.rightT[IO, ExactTxPipelineStoreFailure](None)
      bindings = (
        record.genericProjection.idempotencyKey,
        existingBinding,
      ) match
        case (Some(key), Some(value)) => Map(key -> value)
        case _                        => Map.empty
      validation <- EitherT.fromEither[IO](
        ExactTxPipelineStoreValidation.createOrReplayIndexed(
          currentIndexes,
          bindings,
          existing,
          record,
        ),
      )
      _ <-
        if validation.outcome.created then persistRecord(record)
        else EitherT.rightT[IO, ExactTxPipelineStoreFailure](())
      _ <-
        if validation.outcome.created then
          EitherT.liftF(indexes.set(Some(validation.indexes)))
        else EitherT.rightT[IO, ExactTxPipelineStoreFailure](())
      _ <- (
        record.genericProjection.idempotencyKey,
        validation.idempotencyBinding,
      ) match
        case (Some(key), Some(value)) => persistIdempotency(key, value)
        case _ => EitherT.rightT[IO, ExactTxPipelineStoreFailure](())
    yield validation.outcome

  private def loadIndexes
      : EitherT[IO, ExactTxPipelineStoreFailure, ExactTxPipelineIndexes] =
    EitherT:
      indexes.get.map(
        _.toRight(
          ExactTxPipelineStoreFailure.DecodeFailed(
            "exact pipeline indexes are invalid after a storage write failure; restart to rebuild",
          ),
        ),
      )

  private[swaydb] def rebuildIndexes: IO[Unit] =
    (for
      stored  <- loadKeyedRecords
      rebuilt <- EitherT.fromEither[IO](rebuildIndexesFrom(stored))
      _       <- EitherT.liftF[IO, ExactTxPipelineStoreFailure, Unit](
        indexes.set(Some(rebuilt)),
      )
    yield ()).value.flatMap:
      case Right(_)    => IO.unit
      case Left(error) =>
        IO.raiseError(
          new IllegalStateException(
            s"${error.reason}: ${error.diagnosticDetail}",
          ),
        )

  private def rebuildIndexesFrom(
      stored: Vector[(Utf8, ExactTxPipelineRecord)],
  ): Either[ExactTxPipelineStoreFailure, ExactTxPipelineIndexes] =
    stored.foldLeft(
      Right(ExactTxPipelineIndexes.empty): Either[
        ExactTxPipelineStoreFailure,
        ExactTxPipelineIndexes,
      ],
    ): (accumulated, entry) =>
      val (key, record) = entry
      accumulated.flatMap(
        _.add(record).leftMap(error =>
          ExactTxPipelineStoreFailure.DecodeFailed(
            s"exact record key ${key.asString} failed index rebuild: ${error.reason}: ${error.diagnosticDetail}",
          ),
        ),
      )

  private def loadKeyedRecords: EitherT[
    IO,
    ExactTxPipelineStoreFailure,
    Vector[(Utf8, ExactTxPipelineRecord)],
  ] =
    records
      .from(Utf8(""), 0, Int.MaxValue)
      .leftMap(value => ExactTxPipelineStoreFailure.DecodeFailed(value.msg))
      .flatMap(values =>
        EitherT.fromEither[IO](
          values.toVector
            .traverse { value =>
              decodeRecord(value._1, value._2).map(value._1 -> _)
            }
            .map(_.sortBy(_._1.asString)),
        ),
      )

  private def loadIdempotencyBinding(
      key: TxPipelineIdempotencyKey,
  ): EitherT[
    IO,
    ExactTxPipelineStoreFailure,
    Option[TxPipelineIdempotencyBinding],
  ] =
    idempotency
      .get(Utf8(key.value))
      .leftMap(value => ExactTxPipelineStoreFailure.DecodeFailed(value.msg))
      .flatMap:
        case None        => EitherT.rightT(None)
        case Some(value) =>
          EitherT.fromEither[IO](
            decode[TxPipelineIdempotencyBinding](value.asString)
              .leftMap(error =>
                ExactTxPipelineStoreFailure.DecodeFailed(error.getMessage),
              )
              .map(Some(_)),
          )

  private def persistRecord(
      record: ExactTxPipelineRecord,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Unit] =
    EitherT:
      records
        .put(
          Utf8(record.genericProjection.pipelineId.value),
          Utf8(record.asJson.noSpaces),
        )
        .attempt
        .flatMap:
          case Right(_)    => IO.pure(Right(()))
          case Left(error) =>
            indexes
              .set(None)
              .as(
                Left(
                  ExactTxPipelineStoreFailure.DecodeFailed(
                    s"exact record write failed and invalidated indexes: ${error.getMessage}",
                  ),
                ),
              )

  private def persistIdempotency(
      key: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, ExactTxPipelineStoreFailure, Unit] =
    EitherT.right:
      idempotency.put(Utf8(key.value), Utf8(binding.asJson.noSpaces))

  private def decodeRecord(
      key: Utf8,
      value: Utf8,
  ): Either[ExactTxPipelineStoreFailure, ExactTxPipelineRecord] =
    decode[ExactTxPipelineRecord](value.asString)
      .leftMap(error =>
        ExactTxPipelineStoreFailure.DecodeFailed(
          s"exact record key ${key.asString} could not be decoded: ${error.getMessage}",
        ),
      )
      .flatMap(record =>
        for
          _ <- ExactTxPipelineStoreValidation.validateDescriptor(record)
          _ <- Either.cond(
            record.genericProjection.pipelineId.value === key.asString,
            (),
            ExactTxPipelineStoreFailure.DecodeFailed(
              s"exact record key ${key.asString} does not match ${record.genericProjection.pipelineId.value}",
            ),
          )
        yield record,
      )

object SwayDbExactTxPipelineStore:
  def resource(
      recordsDir: Path,
      idempotencyDir: Path,
  )(using Bag.Async[IO]): Resource[IO, SwayDbExactTxPipelineStore] =
    for
      records     <- SwayStores.storeIndex[Utf8, Utf8](recordsDir)
      idempotency <- SwayStores.keyValue[Utf8, Utf8](idempotencyDir)
      indexes     <- Resource.eval(
        Ref.of[IO, Option[ExactTxPipelineIndexes]](None),
      )
      gate <- Resource.eval(Semaphore[IO](1L))
      store = SwayDbExactTxPipelineStore(
        records,
        idempotency,
        indexes,
        gate,
      )
      _ <- Resource.eval(store.rebuildIndexes)
    yield store
