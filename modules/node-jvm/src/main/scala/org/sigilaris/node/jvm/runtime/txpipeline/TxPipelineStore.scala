package org.sigilaris.node.jvm.runtime.txpipeline

import cats.data.EitherT

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*

import org.sigilaris.node.txpipeline.{
  TxPipelineCanonicalPayloadHash,
  TxPipelineId,
  TxPipelineIdempotencyKey,
  TxPipelineRecord,
}

trait TxPipelineStore[F[_]]:
  def create(
      record: TxPipelineRecord,
  ): EitherT[F, TxPipelineStoreFailure, TxPipelineRecord]

  def get(
      pipelineId: TxPipelineId,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineRecord]]

  def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineRecord]]

  def addIdempotencyAlias(
      idempotencyKey: TxPipelineIdempotencyKey,
      binding: TxPipelineIdempotencyBinding,
  ): EitherT[F, TxPipelineStoreFailure, TxPipelineRecord]

  def put(record: TxPipelineRecord): EitherT[F, TxPipelineStoreFailure, Unit]

  def update(
      pipelineId: TxPipelineId,
  )(
      update: TxPipelineRecord => TxPipelineStoreUpdate,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineStoreUpdate]]

  def list(
      offset: Int,
      limit: Int,
  ): EitherT[F, TxPipelineStoreFailure, Vector[TxPipelineRecord]]

final case class TxPipelineStoreUpdate(
    record: TxPipelineRecord,
    changed: Boolean,
)

final case class TxPipelineIdempotencyBinding(
    pipelineId: TxPipelineId,
    canonicalPayloadHash: TxPipelineCanonicalPayloadHash,
)

object TxPipelineIdempotencyBinding:
  given Decoder[TxPipelineIdempotencyBinding] = deriveDecoder
  given Encoder[TxPipelineIdempotencyBinding] = deriveEncoder

enum TxPipelineStoreFailure:
  case PipelineMissing(pipelineId: TxPipelineId)
  case PipelineAlreadyExists(pipelineId: TxPipelineId)
  case IdempotencyKeyAlreadyExists(
      idempotencyKey: TxPipelineIdempotencyKey,
      pipelineId: TxPipelineId,
  )
  case DecodeFailed(detail: String)
