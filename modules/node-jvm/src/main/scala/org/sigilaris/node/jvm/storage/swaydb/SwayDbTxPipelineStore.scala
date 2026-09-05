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
  TxPipelineIdempotencyBinding,
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
      binding <- loadIdempotencyBinding(idempotencyKey)
      record  <- binding match
        case None        => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          loadByPipelineId(value.pipelineId).flatMap:
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
      created  <- existing match
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
      case None      => EitherT.rightT(())
      case Some(key) =>
        loadIdempotencyBinding(key).flatMap:
          case None           => EitherT.rightT(())
          case Some(existing) =>
            EitherT.leftT[IO, Unit](
              TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                key,
                existing.pipelineId,
              ),
            )

  private def loadByPipelineId(
      pipelineId: TxPipelineId,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    byId
      .get(Utf8(pipelineId.value))
      .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
      .flatMap:
        case None        => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          EitherT.fromEither[IO](decodeRecord(value).map(Some(_)))

  private def addIdempotencyAliasUnderGate(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, TxPipelineStoreFailure, TxPipelineRecord] =
    loadByPipelineId(binding.pipelineId).flatMap:
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
        loadIdempotencyBinding(idempotencyKey).flatMap:
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
            persistIdempotencyBinding(idempotencyKey, binding).as(record)

  private def persistRecord(
      record: TxPipelineRecord,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    val writeRecord =
      byId.put(Utf8(record.pipelineId.value), encodeRecord(record))
    // The record is the source of truth. If the process crashes before the
    // idempotency index write, a later deterministic create observes the
    // existing record and repairs convergence through the normal alias path.
    val writeIdempotency = record.idempotencyKey.fold(IO.unit): key =>
      persistIdempotencyBinding(
        key,
        TxPipelineIdempotencyBinding(
          pipelineId = record.pipelineId,
          canonicalPayloadHash = record.canonicalPayloadHash,
        ),
      ).value.void
    EitherT.right(writeRecord >> writeIdempotency)

  private def loadIdempotencyBinding(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[IO, TxPipelineStoreFailure, Option[TxPipelineIdempotencyBinding]] =
    byIdempotencyKey
      .get(Utf8(idempotencyKey.value))
      .leftMap(failure => TxPipelineStoreFailure.DecodeFailed(failure.msg))
      .flatMap:
        case None        => EitherT.rightT[IO, TxPipelineStoreFailure](None)
        case Some(value) =>
          decode[TxPipelineIdempotencyBinding](value.asString) match
            case Right(binding) =>
              EitherT.rightT[IO, TxPipelineStoreFailure](Some(binding))
            case Left(_) =>
              // Backward compatibility for pre-0022 indexes that stored only the
              // pipeline id. The old format has no hash, so admission must still
              // compare the loaded record's identity hash against the current
              // submit request before accepting this as a replay.
              TxPipelineId.parse(value.asString) match
                case Left(error) =>
                  EitherT.leftT[IO, Option[TxPipelineIdempotencyBinding]](
                    TxPipelineStoreFailure.DecodeFailed(error),
                  )
                case Right(pipelineId) =>
                  loadByPipelineId(pipelineId).flatMap:
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

  private def persistIdempotencyBinding(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[IO, TxPipelineStoreFailure, Unit] =
    EitherT.right:
      byIdempotencyKey.put(
        Utf8(idempotencyKey.value),
        Utf8(binding.asJson.noSpaces),
      )

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
