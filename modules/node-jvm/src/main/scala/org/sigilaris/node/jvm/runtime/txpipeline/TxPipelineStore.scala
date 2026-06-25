package org.sigilaris.node.jvm.runtime.txpipeline

import cats.data.EitherT

import org.sigilaris.node.txpipeline.{
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

enum TxPipelineStoreFailure:
  case PipelineAlreadyExists(pipelineId: TxPipelineId)
  case IdempotencyKeyAlreadyExists(
      idempotencyKey: TxPipelineIdempotencyKey,
      pipelineId: TxPipelineId,
  )
  case DecodeFailed(detail: String)
