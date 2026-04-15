package org.sigilaris.node.jvm.runtime.block

import cats.data.EitherT
import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.ByteEncoder

/** Read-only query interface for retrieving block headers, bodies, and views.
  *
  * @tparam F
  *   effect type
  * @tparam TxRef
  *   transaction reference type
  * @tparam ResultRef
  *   result reference type
  * @tparam Event
  *   event type
  */
trait BlockQuery[F[_], TxRef, ResultRef, Event]:

  /** Retrieves a block header by its identifier.
    *
    * @param blockId
    *   the block identifier to look up
    * @return
    *   `Some(header)` if found, `None` otherwise
    */
  def getHeader(
      blockId: BlockId,
  ): F[Option[BlockHeader]]

  /** Retrieves a block body by the block identifier.
    *
    * @param blockId
    *   the block identifier to look up
    * @return
    *   `Some(body)` if found, `None` otherwise
    */
  def getBody(
      blockId: BlockId,
  ): F[Option[BlockBody[TxRef, ResultRef, Event]]]

  /** Retrieves and validates a combined block view (header + body).
    *
    * @param blockId
    *   the block identifier to look up
    * @return
    *   `Right(Some(view))` if both parts exist and are valid,
    *   `Right(None)` if either part is missing, or
    *   `Left(failure)` if validation fails
    */
  def getView(
      blockId: BlockId,
  ): EitherT[F, BlockValidationFailure, Option[
    BlockView[TxRef, ResultRef, Event],
  ]]

/** Mutable store for persisting and retrieving blocks, extending
  * `BlockQuery` with write operations.
  *
  * @tparam F
  *   effect type
  * @tparam TxRef
  *   transaction reference type
  * @tparam ResultRef
  *   result reference type
  * @tparam Event
  *   event type
  */
trait BlockStore[F[_], TxRef, ResultRef, Event]
    extends BlockQuery[F, TxRef, ResultRef, Event]:

  /** Stores a block header and returns its computed `BlockId`.
    *
    * @param header
    *   the block header to persist
    * @return
    *   the computed block identifier
    */
  def putHeader(
      header: BlockHeader,
  ): F[BlockId]

  /** Stores a block body, optionally validating against an existing header.
    *
    * Split storage is allowed to receive a body before its header is available.
    * When the header is already present, the write path rejects a mismatched
    * `bodyRoot` immediately; otherwise `getView` remains the hydration-time
    * integrity gate once both halves exist.
    *
    * @param blockId
    *   the block identifier to associate the body with
    * @param body
    *   the block body to persist
    * @return
    *   `Right(())` on success, or `Left(failure)` on validation error
    */
  def putBody(
      blockId: BlockId,
      body: BlockBody[TxRef, ResultRef, Event],
  ): EitherT[F, BlockValidationFailure, Unit]

  /** Validates and stores a complete block view (header + body) atomically.
    *
    * @param view
    *   the block view to persist
    * @return
    *   `Right(blockId)` on success, or `Left(failure)` on validation error
    */
  def putView(
      view: BlockView[TxRef, ResultRef, Event],
  ): EitherT[F, BlockValidationFailure, BlockId]

/** Companion for `BlockStore` providing factory methods. */
object BlockStore:

  /** Creates a new in-memory `BlockStore` backed by `Ref`-based maps.
    *
    * @tparam F
    *   effect type with `Sync` capability
    * @tparam TxRef
    *   transaction reference type
    * @tparam ResultRef
    *   result reference type
    * @tparam Event
    *   event type
    * @return
    *   an effectfully allocated in-memory block store
    */
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
