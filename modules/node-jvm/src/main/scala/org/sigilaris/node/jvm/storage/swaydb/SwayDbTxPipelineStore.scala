package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import io.circe.parser.decode
import io.circe.syntax.*

import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.jvm.storage.{KeyValueStore, StoreIndex}
import org.sigilaris.node.jvm.runtime.txpipeline.{
  TxPipelineStore,
  TxPipelineStoreFailure,
  TxPipelineStoreUpdate,
}
import org.sigilaris.node.txpipeline.{
  TxPipelineId,
  TxPipelineIdempotencyKey,
  TxPipelineRecord,
}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
final class SwayDbTxPipelineStore private (
    byId: StoreIndex[IO, Utf8, Utf8],
    byIdempotencyKey: KeyValueStore[IO, Utf8, Utf8],
    gate: Semaphore[IO],
) extends TxPipelineStore[IO]:

  override def create(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    EitherT:
      gate.permit.use: _ =>
        createUnderGate(record).value

  override def get(
      pipelineId: TxPipelineId,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    loadByPipelineId(pipelineId)

  override def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    for
      pipelineIdValue <- byIdempotencyKey
        .get(Utf8(idempotencyKey.value))
        .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
      record <- pipelineIdValue match
        case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          TxPipelineId.parse(value.asString) match
            case Left(error) =>
              EitherT.leftT[IO, Option[TxPipelineRecord]](
                TxPipelineStoreFailure.DecodeFailed(error),
              )
            case Right(pipelineId) =>
              loadByPipelineId(pipelineId).flatMap:
                case Some(record) =>
                  EitherT.rightT[IO, TxPipelineStoreFailure](Some(record))
                case None =>
                  EitherT.leftT[IO, Option[TxPipelineRecord]](
                    TxPipelineStoreFailure.DecodeFailed(
                      s"idempotency index points to missing pipeline ${pipelineId.value}",
                    ),
                  )
    yield record

  override def put(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    EitherT:
      gate.permit.use: _ =>
        persistRecord(record).value

  override def update(
      pipelineId: TxPipelineId,
  )(
      update: TxPipelineRecord => TxPipelineStoreUpdate,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineStoreUpdate]] =
    EitherT:
      gate.permit.use: _ =>
        updateUnderGate(pipelineId, update).value

  override def list(
      offset: Int,
      limit: Int,
  ): EitherT[IO, TxPipelineStoreFailure, Vector[TxPipelineRecord]] =
    if limit <= 0 then EitherT.rightT(Vector.empty)
    else
      for
        entries <- byId
          .from(Utf8(""), offset.max(0), limit)
          .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
        records <- entries.traverse: (_, value) =>
          EitherT.fromEither[IO](decodeRecord(value))
      yield records.toVector

  private def createUnderGate(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    for
      existing <- loadByPipelineId(record.pipelineId)
      created <- existing match
        case Some(_) =>
          EitherT.leftT[IO, TxPipelineRecord](
            TxPipelineStoreFailure.PipelineAlreadyExists(record.pipelineId),
          )
        case None =>
          checkIdempotency(record).flatMap: _ =>
            persistRecord(record).as(record)
    yield created

  private def updateUnderGate(
      pipelineId: TxPipelineId,
      update: TxPipelineRecord => TxPipelineStoreUpdate,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineStoreUpdate]] =
    loadByPipelineId(pipelineId).flatMap:
      case None =>
        EitherT.rightT[IO, TxPipelineStoreFailure](None)
      case Some(existing) =>
        val next = update(existing)
        if next.record.pipelineId.value =!= pipelineId.value then
          EitherT.leftT[IO, Option[TxPipelineStoreUpdate]](
            TxPipelineStoreFailure.DecodeFailed(
              ss"update changed pipelineId from ${pipelineId.value} to ${next.record.pipelineId.value}",
            ),
          )
        else if !next.changed then
          EitherT.rightT[IO, TxPipelineStoreFailure](Some(next))
        else persistRecord(next.record).as(Some(next))

  private def checkIdempotency(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    record.idempotencyKey match
      case None => EitherT.rightT(())
      case Some(key) =>
        byIdempotencyKey
          .get(Utf8(key.value))
          .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
          .flatMap:
            case None => EitherT.rightT(())
            case Some(existingPipelineId) =>
              TxPipelineId.parse(existingPipelineId.asString) match
                case Left(error) =>
                  EitherT.leftT[IO, Unit](
                    TxPipelineStoreFailure.DecodeFailed(error),
                  )
                case Right(pipelineId) =>
                  EitherT.leftT[IO, Unit](
                    TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                      key,
                      pipelineId,
                    ),
                  )

  private def loadByPipelineId(
      pipelineId: TxPipelineId,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    byId
      .get(Utf8(pipelineId.value))
      .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
      .flatMap:
        case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          EitherT.fromEither[IO](decodeRecord(value).map(Some(_)))

  private def persistRecord(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    val writeRecord =
      byId.put(Utf8(record.pipelineId.value), encodeRecord(record))
    val writeIdempotency = record.idempotencyKey.fold(IO.unit): key =>
      byIdempotencyKey.put(Utf8(key.value), Utf8(record.pipelineId.value))
    EitherT.right(writeRecord >> writeIdempotency)

  private def encodeRecord(record: TxPipelineRecord): Utf8 =
    Utf8(record.asJson.noSpaces)

  private def decodeRecord(
      value: Utf8,
  ): Either[TxPipelineStoreFailure, TxPipelineRecord] =
    decode[TxPipelineRecord](value.asString)
      .leftMap(error => TxPipelineStoreFailure.DecodeFailed(error.getMessage))

object SwayDbTxPipelineStore:
  def resource(
      metadataDir: Path,
      idempotencyDir: Path,
  )(using Bag.Async[IO]): Resource[IO, SwayDbTxPipelineStore] =
    for
      byId             <- SwayStores.storeIndex[Utf8, Utf8](metadataDir)
      byIdempotencyKey <- SwayStores.keyValue[Utf8, Utf8](idempotencyDir)
      gate             <- Resource.eval(Semaphore[IO](1L))
    yield SwayDbTxPipelineStore(byId, byIdempotencyKey, gate)
