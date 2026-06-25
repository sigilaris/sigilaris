package org.sigilaris.node.jvm.runtime.txpipeline

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.Base64

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.data.EitherT
import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*

import io.circe.parser.decode
import io.circe.syntax.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.txpipeline.{
  TxPipelineId,
  TxPipelineIdempotencyKey,
  TxPipelineRecord,
}

@SuppressWarnings(
  Array("org.wartremover.warts.Any", "org.wartremover.warts.Nothing"),
)
final class FileTxPipelineStore private (
    root: Path,
    gate: Semaphore[IO],
) extends TxPipelineStore[IO]:
  private val recordsDir     = root.resolve("records")
  private val idempotencyDir = root.resolve("idempotency")

  override def create(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    EitherT:
      gate.permit.use: _ =>
        createUnderGate(record).value

  override def get(
      pipelineId: TxPipelineId,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    readRecordFile(recordPath(pipelineId))

  override def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    for
      pipelineId <- readTextFile(idempotencyPath(idempotencyKey)).flatMap:
        case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          TxPipelineId.parse(value) match
            case Left(error) =>
              EitherT.leftT[IO, Option[TxPipelineId]](
                TxPipelineStoreFailure.DecodeFailed(error),
              )
            case Right(pipelineId) =>
              EitherT.rightT[IO, TxPipelineStoreFailure](Some(pipelineId))
      record <- pipelineId match
        case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          get(value).flatMap:
            case Some(record) =>
              EitherT.rightT[IO, TxPipelineStoreFailure](Some(record))
            case None =>
              EitherT.leftT[IO, Option[TxPipelineRecord]](
                TxPipelineStoreFailure.DecodeFailed(
                  s"idempotency index points to missing pipeline ${value.value}",
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
      val start = offset.max(0)
      val paths = IO.blocking:
        Using.resource(Files.list(recordsDir)): stream =>
          stream
            .iterator()
            .asScala
            .toVector
            .filter(path => path.getFileName.toString.endsWith(".json"))
            .sortBy(_.getFileName.toString)
            .slice(start, start + limit)
      EitherT
        .right(paths)
        .flatMap: selected =>
          selected.traverse(readRecordFile).map(_.flatten)

  private def createUnderGate(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    for
      exists <- pathExists(recordPath(record.pipelineId))
      created <-
        if exists then
          EitherT.leftT[IO, TxPipelineRecord](
            TxPipelineStoreFailure.PipelineAlreadyExists(record.pipelineId),
          )
        else
          checkIdempotency(record).flatMap(_ =>
            persistRecord(record).as(record),
          )
    yield created

  private def updateUnderGate(
      pipelineId: TxPipelineId,
      update: TxPipelineRecord => TxPipelineStoreUpdate,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineStoreUpdate]] =
    readRecordFile(recordPath(pipelineId)).flatMap:
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
        readTextFile(idempotencyPath(key)).flatMap:
          case None => EitherT.rightT(())
          case Some(existingPipelineId) =>
            TxPipelineId.parse(existingPipelineId) match
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

  private def persistRecord(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    val writeRecord =
      writeTextFile(recordPath(record.pipelineId), record.asJson.noSpaces)
    val writeIdempotency = record.idempotencyKey.fold(IO.unit): key =>
      writeTextFile(idempotencyPath(key), record.pipelineId.value)
    EitherT.right(writeRecord >> writeIdempotency)

  private def readRecordFile(
      path: Path,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    readTextFile(path).flatMap:
      case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
      case Some(json) =>
        EitherT.fromEither[IO]:
          decode[TxPipelineRecord](json)
            .leftMap(error =>
              TxPipelineStoreFailure.DecodeFailed(error.getMessage),
            )
            .map(Some(_))

  private def readTextFile(
      path: Path,
  ): EitherT[IO, TxPipelineStoreFailure, Option[String]] =
    EitherT.right:
      IO.blocking:
        if Files.exists(path) then
          Some(Files.readString(path, StandardCharsets.UTF_8))
        else None

  private def pathExists(
      path: Path,
  ): EitherT[IO, TxPipelineStoreFailure, Boolean] =
    EitherT.right(IO.blocking(Files.exists(path)))

  private def writeTextFile(path: Path, value: String): IO[Unit] =
    val tmp = path.resolveSibling(s"${path.getFileName.toString}.tmp")
    IO.blocking:
      Files.writeString(tmp, value, StandardCharsets.UTF_8)
      Files.move(
        tmp,
        path,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
      ()

  private def recordPath(pipelineId: TxPipelineId): Path =
    recordsDir.resolve(s"${encodeName(pipelineId.value)}.json")

  private def idempotencyPath(key: TxPipelineIdempotencyKey): Path =
    idempotencyDir.resolve(s"${encodeName(key.value)}.txt")

  private def encodeName(value: String): String =
    Base64.getUrlEncoder
      .withoutPadding()
      .encodeToString(value.getBytes(StandardCharsets.UTF_8))

object FileTxPipelineStore:
  def resource(root: Path): Resource[IO, FileTxPipelineStore] =
    Resource.eval:
      for
        _ <- IO.blocking:
          Files.createDirectories(root.resolve("records"))
          Files.createDirectories(root.resolve("idempotency"))
          ()
        gate <- Semaphore[IO](1L)
      yield FileTxPipelineStore(root, gate)
