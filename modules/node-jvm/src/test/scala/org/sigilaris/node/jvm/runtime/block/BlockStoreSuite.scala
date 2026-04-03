package org.sigilaris.node.jvm.runtime.block

import cats.effect.IO
import munit.CatsEffectSuite

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.{UInt256, Utf8}

final class BlockStoreSuite extends CatsEffectSuite:

  test("application-owned block views round-trip through the block query/store seam"):
    val body =
      blockBody(
        blockRecord("tx-a", Some("result-a"), Vector("event-a")),
        blockRecord("tx-b", Some("result-b"), Vector("event-b")),
      )
    val view =
      BlockView(
        header = blockHeader(BlockBody.computeBodyRoot(body).toOption.get),
        body = body,
      )

    for
      store <- BlockStore.inMemory[IO, InventoryTxRef, InventoryResultRef, InventoryEvent]
      blockId <- unwrap(store.putView(view))
      storedHeader <- store.getHeader(blockId)
      storedBody <- store.getBody(blockId)
      storedView <- store.getView(blockId).value
    yield
      assertEquals(blockId, BlockHeader.computeId(view.header))
      assertEquals(storedHeader, Some(view.header))
      assertEquals(storedBody, Some(view.body))
      assertEquals(storedView, Right(Some(view)))

  test("split header/body storage hydrates views only after local body-root re-verification"):
    val canonicalBody =
      blockBody(
        blockRecord("tx-a", Some("result-a"), Vector("event-a")),
      )
    val mismatchedBody =
      blockBody(
        blockRecord("tx-b", Some("result-b"), Vector("event-b")),
      )
    val header =
      blockHeader(BlockBody.computeBodyRoot(canonicalBody).toOption.get)

    for
      store <- BlockStore.inMemory[IO, InventoryTxRef, InventoryResultRef, InventoryEvent]
      blockId <- store.putHeader(header)
      missingView <- store.getView(blockId).value
      _ <- unwrap(store.putBody(blockId, mismatchedBody))
      mismatchedView <- store.getView(blockId).value
      _ <- unwrap(store.putBody(blockId, canonicalBody))
      hydratedView <- store.getView(blockId).value
    yield
      assertEquals(missingView, Right(None))
      assertEquals(mismatchedView.left.map(_.reason), Left("bodyRootMismatch"))
      assertEquals(
        hydratedView,
        Right(
          Some(
            BlockView(
              header = header,
              body = canonicalBody,
            ),
          )
        ),
      )

  private def unwrap[A](
      result: cats.data.EitherT[IO, BlockValidationFailure, A],
  ): IO[A] =
    result.value.flatMap:
      case Right(value) =>
        IO.pure(value)
      case Left(error) =>
        IO.raiseError(new IllegalStateException(error.reason))

  private def blockHeader(
      bodyRoot: BodyRoot,
  ): BlockHeader =
    BlockHeader(
      parent = None,
      height = BlockHeight.unsafeFromLong(11L),
      stateRoot = StateRoot(hex("11")),
      bodyRoot = bodyRoot,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(1_712_345_678_000L),
    )

  private def blockBody(
      records: InventoryBlockRecord*,
  ): BlockBody[InventoryTxRef, InventoryResultRef, InventoryEvent] =
    BlockBody(records.toSet)

  private def blockRecord(
      tx: String,
      result: Option[String],
      events: Vector[String],
  ): InventoryBlockRecord =
    BlockRecord(
      tx = InventoryTxRef(Utf8(tx)),
      result = result.map(value => InventoryResultRef(Utf8(value))),
      events = events.map(value => InventoryEvent(Utf8(value))),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private type InventoryBlockRecord =
    BlockRecord[InventoryTxRef, InventoryResultRef, InventoryEvent]

  private final case class InventoryTxRef(
      value: Utf8,
  ) derives ByteEncoder

  private final case class InventoryResultRef(
      value: Utf8,
  ) derives ByteEncoder

  private final case class InventoryEvent(
      value: Utf8,
  ) derives ByteEncoder
