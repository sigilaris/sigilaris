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
      binding <- readIdempotencyBinding(idempotencyKey)
      record <- binding match
        case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          get(value.pipelineId).flatMap:
            case Some(record) =>
              if record.canonicalPayloadHash.value ===
                  value.canonicalPayloadHash.value
              then EitherT.rightT[IO, TxPipelineStoreFailure](Some(record))
              else
                EitherT.leftT[IO, Option[TxPipelineRecord]](
                  TxPipelineStoreFailure.DecodeFailed(
                    s"idempotency index hash mismatch for ${value.pipelineId.value}",
                  ),
                )
            case None =>
              EitherT.leftT[IO, Option[TxPipelineRecord]](
                TxPipelineStoreFailure.DecodeFailed(
                  s"idempotency index points to missing pipeline ${value.pipelineId.value}",
                ),
              )
    yield record

  override def addIdempotencyAlias(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    EitherT:
      gate.permit.use: _ =>
        addIdempotencyAliasUnderGate(idempotencyKey, binding).value

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
        readIdempotencyBinding(key).flatMap:
          case None => EitherT.rightT(())
          case Some(existing) =>
            EitherT.leftT[IO, Unit](
              TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                key,
                existing.pipelineId,
              ),
            )

  private def addIdempotencyAliasUnderGate(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    readRecordFile(recordPath(binding.pipelineId)).flatMap:
      case None =>
        EitherT.leftT[IO, TxPipelineRecord](
          TxPipelineStoreFailure.PipelineMissing(binding.pipelineId),
        )
      case Some(record)
          if record.canonicalPayloadHash.value =!=
            binding.canonicalPayloadHash.value =>
        EitherT.leftT[IO, TxPipelineRecord](
          TxPipelineStoreFailure.DecodeFailed(
            ss"idempotency alias hash mismatch for ${binding.pipelineId.value}",
          ),
        )
      case Some(record) =>
        readIdempotencyBinding(idempotencyKey).flatMap:
          case Some(existing)
              if existing.pipelineId.value === binding.pipelineId.value &&
                existing.canonicalPayloadHash.value ===
                binding.canonicalPayloadHash.value =>
            EitherT.rightT[IO, TxPipelineStoreFailure](record)
          case Some(existing) =>
            EitherT.leftT[IO, TxPipelineRecord](
              TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                idempotencyKey,
                existing.pipelineId,
              ),
            )
          case None =>
            writeIdempotencyBinding(idempotencyKey, binding).as(record)

  private def persistRecord(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    val writeRecord =
      writeTextFile(recordPath(record.pipelineId), record.asJson.noSpaces)
    // The record is the source of truth. If the process crashes before the
    // idempotency index write, a later deterministic create observes the
    // existing record and repairs convergence through the normal alias path.
    val writeIdempotency = record.idempotencyKey.fold(IO.unit): key =>
      writeIdempotencyBinding(
        key,
        TxPipelineIdempotencyBinding(
          pipelineId = record.pipelineId,
          canonicalPayloadHash = record.canonicalPayloadHash,
        ),
      ).value.void
    EitherT.right(writeRecord >> writeIdempotency)

  private def readIdempotencyBinding(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineIdempotencyBinding]] =
    readTextFile(idempotencyPath(idempotencyKey)).flatMap:
      case None => EitherT.rightT[IO, TxPipelineStoreFailure](None)
      case Some(value) =>
        decode[TxPipelineIdempotencyBinding](value) match
          case Right(binding) =>
            EitherT.rightT[IO, TxPipelineStoreFailure](Some(binding))
          case Left(_) =>
            // Backward compatibility for pre-0022 indexes that stored only the
            // pipeline id. The old format has no hash, so admission must still
            // compare the loaded record's identity hash against the current
            // submit request before accepting this as a replay.
            TxPipelineId.parse(value) match
              case Left(error) =>
                EitherT.leftT[IO, Option[TxPipelineIdempotencyBinding]](
                  TxPipelineStoreFailure.DecodeFailed(error),
                )
              case Right(pipelineId) =>
                readRecordFile(recordPath(pipelineId)).flatMap:
                  case Some(record) =>
                    EitherT.rightT[IO, TxPipelineStoreFailure](
                      Some(
                        TxPipelineIdempotencyBinding(
                          pipelineId = pipelineId,
                          canonicalPayloadHash = record.canonicalPayloadHash,
                        ),
                      ),
                    )
                  case None =>
                    EitherT.leftT[IO, Option[TxPipelineIdempotencyBinding]](
                      TxPipelineStoreFailure.DecodeFailed(
                        s"idempotency index points to missing pipeline ${pipelineId.value}",
                      ),
                    )

  private def writeIdempotencyBinding(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    EitherT.right:
      writeTextFile(idempotencyPath(idempotencyKey), binding.asJson.noSpaces)

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
