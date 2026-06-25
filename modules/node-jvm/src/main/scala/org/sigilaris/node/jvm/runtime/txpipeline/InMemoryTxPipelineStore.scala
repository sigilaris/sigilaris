package org.sigilaris.node.jvm.runtime.txpipeline

import cats.data.EitherT
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.util.SafeStringInterp.*
import org.sigilaris.node.txpipeline.{
  TxPipelineId,
  TxPipelineIdempotencyKey,
  TxPipelineRecord,
}

@SuppressWarnings(Array("org.wartremover.warts.Nothing"))
final class InMemoryTxPipelineStore[F[_]: Sync] private (
    ref: Ref[F, InMemoryTxPipelineStore.State],
) extends TxPipelineStore[F]:

  override def create(
      record: TxPipelineRecord,
  ): EitherT[F, TxPipelineStoreFailure, TxPipelineRecord] =
    EitherT:
      ref.modify: state =>
        state.byId.get(record.pipelineId) match
          case Some(_) =>
            state ->
              Left(
                TxPipelineStoreFailure.PipelineAlreadyExists(record.pipelineId),
              )
          case None =>
            val idempotencyConflict = record.idempotencyKey.flatMap: key =>
              state.byIdempotencyKey.get(key).map(key -> _)
            idempotencyConflict match
              case Some((idempotencyKey, existingPipelineId)) =>
                state ->
                  Left(
                    TxPipelineStoreFailure.IdempotencyKeyAlreadyExists(
                      idempotencyKey,
                      existingPipelineId,
                    ),
                  )
              case None =>
                val nextByKey = record.idempotencyKey.fold(
                  state.byIdempotencyKey,
                )(key => state.byIdempotencyKey.updated(key, record.pipelineId))
                state.copy(
                  byId = state.byId.updated(record.pipelineId, record),
                  byIdempotencyKey = nextByKey,
                ) -> Right(record)

  override def get(
      pipelineId: TxPipelineId,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    EitherT.right(ref.get.map(_.byId.get(pipelineId)))

  override def getByIdempotencyKey(
      idempotencyKey: TxPipelineIdempotencyKey,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineRecord]] =
    EitherT.right:
      ref.get.map: state =>
        state.byIdempotencyKey.get(idempotencyKey).flatMap(state.byId.get)

  override def put(
      record: TxPipelineRecord,
  ): EitherT[F, TxPipelineStoreFailure, Unit] =
    EitherT.right:
      ref.update: state =>
        val nextByKey = record.idempotencyKey.fold(
          state.byIdempotencyKey,
        )(key => state.byIdempotencyKey.updated(key, record.pipelineId))
        state.copy(
          byId = state.byId.updated(record.pipelineId, record),
          byIdempotencyKey = nextByKey,
        )

  override def update(
      pipelineId: TxPipelineId,
  )(
      update: TxPipelineRecord => TxPipelineStoreUpdate,
  ): EitherT[F, TxPipelineStoreFailure, Option[TxPipelineStoreUpdate]] =
    EitherT:
      ref.modify: state =>
        state.byId.get(pipelineId) match
          case None =>
            state -> Right(None)
          case Some(existing) =>
            val next = update(existing)
            if next.record.pipelineId.value =!= pipelineId.value then
              state ->
                Left(
                  TxPipelineStoreFailure.DecodeFailed(
                    ss"update changed pipelineId from ${pipelineId.value} to ${next.record.pipelineId.value}",
                  ),
                )
            else if !next.changed then state -> Right(Some(next))
            else
              val nextByKey = next.record.idempotencyKey.fold(
                state.byIdempotencyKey,
              )(key =>
                state.byIdempotencyKey.updated(key, next.record.pipelineId),
              )
              state.copy(
                byId = state.byId.updated(pipelineId, next.record),
                byIdempotencyKey = nextByKey,
              ) -> Right(Some(next))

  override def list(
      offset: Int,
      limit: Int,
  ): EitherT[F, TxPipelineStoreFailure, Vector[TxPipelineRecord]] =
    EitherT.right:
      ref.get.map: state =>
        if limit <= 0 then Vector.empty
        else
          val start = offset.max(0)
          state.byId.values.toVector
            .sortBy(_.pipelineId.value)
            .slice(start, start + limit)

object InMemoryTxPipelineStore:
  private final case class State(
      byId: Map[TxPipelineId, TxPipelineRecord],
      byIdempotencyKey: Map[TxPipelineIdempotencyKey, TxPipelineId],
  )

  private object State:
    val empty: State = State(Map.empty, Map.empty)

  def create[F[_]: Sync]: F[InMemoryTxPipelineStore[F]] =
    Ref.of[F, State](State.empty).map(new InMemoryTxPipelineStore[F](_))
