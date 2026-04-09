package org.sigilaris.node.jvm.runtime.block

import cats.data.EitherT
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.ByteEncoder

trait BlockQuery[F[_], TxRef, ResultRef, Event]:
  def getHeader(
      blockId: BlockId,
  ): F[Option[BlockHeader]]

  def getBody(
      blockId: BlockId,
  ): F[Option[BlockBody[TxRef, ResultRef, Event]]]

  def getView(
      blockId: BlockId,
  ): EitherT[F, BlockValidationFailure, Option[
    BlockView[TxRef, ResultRef, Event],
  ]]

trait BlockStore[F[_], TxRef, ResultRef, Event]
    extends BlockQuery[F, TxRef, ResultRef, Event]:

  def putHeader(
      header: BlockHeader,
  ): F[BlockId]

  // Split storage is allowed to receive a body before its header is available.
  // When the header is already present, the write path rejects a mismatched
  // `bodyRoot` immediately; otherwise `getView` remains the hydration-time
  // integrity gate once both halves exist.
  def putBody(
      blockId: BlockId,
      body: BlockBody[TxRef, ResultRef, Event],
  ): EitherT[F, BlockValidationFailure, Unit]

  def putView(
      view: BlockView[TxRef, ResultRef, Event],
  ): EitherT[F, BlockValidationFailure, BlockId]

object BlockStore:
  def inMemory[F[_]
    : Sync, TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder]
      : F[BlockStore[F, TxRef, ResultRef, Event]] =
    for
      headers <- Ref.of[F, Map[BlockId, BlockHeader]](Map.empty)
      bodies <- Ref.of[F, Map[BlockId, BlockBody[TxRef, ResultRef, Event]]](
        Map.empty,
      )
    yield InMemoryBlockStore(headers, bodies)

  private final class InMemoryBlockStore[F[_]
    : Sync, TxRef: ByteEncoder, ResultRef: ByteEncoder, Event: ByteEncoder](
      headers: Ref[F, Map[BlockId, BlockHeader]],
      bodies: Ref[F, Map[BlockId, BlockBody[TxRef, ResultRef, Event]]],
  ) extends BlockStore[F, TxRef, ResultRef, Event]:

    override def getHeader(
        blockId: BlockId,
    ): F[Option[BlockHeader]] =
      headers.get.map(_.get(blockId))

    override def getBody(
        blockId: BlockId,
    ): F[Option[BlockBody[TxRef, ResultRef, Event]]] =
      bodies.get.map(_.get(blockId))

    override def getView(
        blockId: BlockId,
    ): EitherT[F, BlockValidationFailure, Option[
      BlockView[TxRef, ResultRef, Event],
    ]] =
      EitherT
        .right[BlockValidationFailure](
          (getHeader(blockId), getBody(blockId)).tupled,
        )
        .subflatMap:
          case (Some(header), Some(body)) =>
            val view = BlockView(header = header, body = body)
            BlockView.validate(view).map(_ => Some(view))
          case _ =>
            Option
              .empty[BlockView[TxRef, ResultRef, Event]]
              .asRight[BlockValidationFailure]

    override def putHeader(
        header: BlockHeader,
    ): F[BlockId] =
      val blockId = BlockHeader.computeId(header)
      headers.update(_.updated(blockId, header)).as(blockId)

    override def putBody(
        blockId: BlockId,
        body: BlockBody[TxRef, ResultRef, Event],
    ): EitherT[F, BlockValidationFailure, Unit] =
      for
        _ <- EitherT.fromEither[F]:
          BlockBody.computeBodyRoot(body).void
        maybeHeader <- EitherT.right[BlockValidationFailure](getHeader(blockId))
        _ <- maybeHeader match
          case Some(header) =>
            EitherT.fromEither[F]:
              BlockBody.verifyBodyRoot(body, header.bodyRoot)
          case None =>
            EitherT.rightT[F, BlockValidationFailure](())
        _ <- EitherT.right[BlockValidationFailure](
          bodies.update(_.updated(blockId, body)),
        )
      yield ()

    override def putView(
        view: BlockView[TxRef, ResultRef, Event],
    ): EitherT[F, BlockValidationFailure, BlockId] =
      (EitherT.fromEither[F]:
          BlockView.validate(view)
      ).semiflatMap: _ =>
        val blockId = BlockHeader.computeId(view.header)
        (
          headers.update(_.updated(blockId, view.header)),
          bodies.update(_.updated(blockId, view.body)),
        ).tupled.as(blockId)
