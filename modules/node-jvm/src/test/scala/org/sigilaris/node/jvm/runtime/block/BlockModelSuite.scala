package org.sigilaris.node.jvm.runtime.block

import java.time.Instant

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.codec.byte.ByteEncoder
import org.sigilaris.core.datatype.{UInt256, Utf8}

final class BlockModelSuite extends FunSuite:

  test("block view validation rejects a body whose root does not match the header"):
    val canonicalBody =
      blockBody(
        blockRecord("tx-a", Some("result-a"), Vector("event-a")),
        blockRecord("tx-b", Some("result-b"), Vector("event-b")),
      )
    val header =
      blockHeader(BlockBody.computeBodyRoot(canonicalBody).toOption.get)
    val mismatchedBody =
      blockBody(
        blockRecord("tx-c", Some("result-c"), Vector("event-c")),
      )

    assertEquals(
      BlockView.validate(BlockView(header = header, body = canonicalBody)),
      Right(()),
    )
    assertEquals(
      BlockView
        .validate(BlockView(header = header, body = mismatchedBody))
        .left
        .map(_.reason),
      Left("bodyRootMismatch"),
    )

  test("body root is permutation invariant for unordered record membership"):
    val recordA = blockRecord("tx-a", Some("result-a"), Vector("event-1"))
    val recordB = blockRecord("tx-b", Some("result-b"), Vector("event-2"))
    val recordC = blockRecord("tx-c", Some("result-c"), Vector("event-3"))

    val bodyA = BlockBody(Set(recordA, recordB, recordC))
    val bodyB = BlockBody(Vector(recordC, recordA, recordB).toSet)

    assertEquals(
      BlockBody.computeBodyRoot(bodyA),
      BlockBody.computeBodyRoot(bodyB),
    )
    assertEquals(
      BlockBody.bodyRootPreImage(bodyA),
      BlockBody.bodyRootPreImage(bodyB),
    )

  test("all record permutations collapse to the same canonical body root"):
    val records = Vector(
      blockRecord("tx-a", Some("result-a"), Vector("event-1")),
      blockRecord("tx-b", Some("result-b"), Vector("event-2")),
      blockRecord("tx-c", Some("result-c"), Vector("event-3")),
    )

    val roots =
      records.permutations
        .map(permutation => BlockBody.computeBodyRoot(BlockBody(permutation.toSet)))
        .toVector

    assertEquals(roots.distinct.size, 1)
    assert(roots.forall(_.isRight))

  test("canonical body serialization follows record-hash lexicographic ordering"):
    val recordA = blockRecord("tx-a", Some("result-a"), Vector("event-a"))
    val recordB = blockRecord("tx-b", Some("result-b"), Vector("event-b"))
    val recordC = blockRecord("tx-c", Some("result-c"), Vector("event-c"))
    val body = BlockBody(Set(recordB, recordC, recordA))

    val canonicalEntries = BlockBody.canonicalEntries(body).toOption.get
    val canonicalHashes  = canonicalEntries.map(_._1.toHexLower)
    val expectedHashes =
      Vector(
        BlockRecordHash.compute(recordA).toHexLower,
        BlockRecordHash.compute(recordB).toHexLower,
        BlockRecordHash.compute(recordC).toHexLower,
      ).sorted
    val expectedOrder =
      Vector(recordA, recordB, recordC)
        .sortBy(record => BlockRecordHash.compute(record).toHexLower)
        .map(_.tx.value.asString)

    assertEquals(canonicalHashes, expectedHashes)
    assertEquals(
      canonicalEntries.map(_._2.tx.value.asString),
      expectedOrder,
    )
    assert(BlockBody.bodyRootPreImage(body).toOption.get.nonEmpty)

  test("duplicate record hashes are rejected even when runtime set members are distinct"):
    val recordA =
      BlockRecord[CollisionTx, Utf8, Utf8](
        tx = CollisionTx("first"),
        result = None,
        events = Vector.empty,
      )
    val recordB =
      BlockRecord[CollisionTx, Utf8, Utf8](
        tx = CollisionTx("second"),
        result = None,
        events = Vector.empty,
      )
    val body = BlockBody(Set(recordA, recordB))

    assertEquals(body.records.size, 2)
    assertEquals(
      BlockBody.canonicalEntries(body).left.map(_.reason),
      Left("duplicateRecordHash"),
    )

  test("runtime collection distinctness does not replace canonical uniqueness checks"):
    val body = BlockBody(
      Set(
        BlockRecord[CollisionTx, Utf8, Utf8](
          tx = CollisionTx("alpha"),
          result = None,
          events = Vector.empty,
        ),
        BlockRecord[CollisionTx, Utf8, Utf8](
          tx = CollisionTx("beta"),
          result = None,
          events = Vector.empty,
        ),
      ),
    )

    assertEquals(body.records.map(_.tx.label), Set("alpha", "beta"))
    assertEquals(
      BlockBody.computeBodyRoot(body).left.map(_.reason),
      Left("duplicateRecordHash"),
    )

  test("negative epoch milliseconds are rejected for block timestamps"):
    assertEquals(
      BlockTimestamp.fromEpochMillis(-1L),
      Left("block timestamp must be non-negative"),
    )

  test("out-of-range instants are rejected for block timestamps"):
    assert(BlockTimestamp.fromInstant(Instant.MAX).isLeft)

  test("negative block heights are rejected"):
    assert(BlockHeight.fromLong(-1L).isLeft)

  test("empty block bodies canonicalize deterministically"):
    val emptyBody = blockBody()
    val emptyBodyRoot = BlockBody.computeBodyRoot(emptyBody).toOption.get

    assertEquals(
      BlockBody.canonicalEntries(emptyBody),
      Right(Vector.empty),
    )
    assertEquals(
      BlockView.validate(
        BlockView(
          header = blockHeader(emptyBodyRoot),
          body = emptyBody,
        ),
      ),
      Right(()),
    )
    assertEquals(
      emptyBodyRoot.toHexLower,
      "6c98e78cc9221aa424e5266ba2c1e99cbb0c1e17676b0be42df202b2b7d37681",
    )

  test("changing a header field changes the computed block id"):
    val baseline =
      blockHeader(BodyRoot(hex("22")))
    val differentHeight =
      baseline.copy(height = BlockHeight.unsafeFromLong(8L))
    val differentTimestamp =
      baseline.copy(timestamp = BlockTimestamp.unsafeFromEpochMillis(1_712_345_678_001L))

    assertNotEquals(
      BlockHeader.computeId(baseline),
      BlockHeader.computeId(differentHeight),
    )
    assertNotEquals(
      BlockHeader.computeId(baseline),
      BlockHeader.computeId(differentTimestamp),
    )

  test("block id stays tied to the header even when block views carry different bodies"):
    val canonicalBody =
      blockBody(
        blockRecord("tx-a", Some("result-a"), Vector("event-a")),
      )
    val differentBody =
      blockBody(
        blockRecord("tx-b", Some("result-b"), Vector("event-b")),
      )
    val header =
      blockHeader(BlockBody.computeBodyRoot(canonicalBody).toOption.get)
    val canonicalView =
      BlockView(header = header, body = canonicalBody)
    val differentView =
      BlockView(header = header, body = differentBody)

    assertEquals(
      BlockView.validate(canonicalView),
      Right(()),
    )
    assertEquals(
      BlockView.validate(differentView).left.map(_.reason),
      Left("bodyRootMismatch"),
    )
    assertEquals(
      BlockHeader.computeId(canonicalView.header),
      BlockHeader.computeId(differentView.header),
    )

  test("header id, record hash, and body root stay pinned to golden vectors"):
    val record = blockRecord("tx-a", Some("result-a"), Vector("event-a"))
    val body =
      blockBody(
        blockRecord("tx-a", Some("result-a"), Vector("event-a")),
        blockRecord("tx-b", Some("result-b"), Vector("event-b")),
      )
    val header = blockHeader(BlockBody.computeBodyRoot(body).toOption.get)

    assertEquals(
      BlockRecordHash.compute(record).toHexLower,
      "270d56fea5a2d0848053d5a9245c06cb3ba69e40f1e2487f8664e5ae23ad24b1",
    )
    assertEquals(
      BlockBody.computeBodyRoot(body).toOption.get.toHexLower,
      "a2da8fc214587b5034ce7628598141097acea20c76a303742be4530c29453cd0",
    )
    assertEquals(
      BlockHeader.computeId(header).toHexLower,
      "f6da47ee49549f5113df84cda89f3c0635e2a962f2aee66e7a7b688666f56d5f",
    )

  test("parented headers stay pinned to a golden block id vector"):
    val header =
      BlockHeader(
        parent = Some(BlockId(hex("33"))),
        height = BlockHeight.unsafeFromLong(9L),
        stateRoot = StateRoot(hex("44")),
        bodyRoot = BodyRoot(hex("55")),
        timestamp = BlockTimestamp.unsafeFromEpochMillis(1_712_345_678_999L),
      )

    assertEquals(
      BlockHeader.computeId(header).toHexLower,
      "b2428b1d1a34a5cc01e4d94ba68eee8dbb1a699c04e653f9417e43d98104ead7",
    )

  private def blockHeader(
      bodyRoot: BodyRoot,
  ): BlockHeader =
    BlockHeader(
      parent = None,
      height = BlockHeight.unsafeFromLong(7L),
      stateRoot = StateRoot(hex("11")),
      bodyRoot = bodyRoot,
      timestamp = BlockTimestamp.unsafeFromEpochMillis(1_712_345_678_000L),
    )

  private def blockBody(
      records: TestBlockRecord*,
  ): BlockBody[TestTx, TestResult, TestEvent] =
    BlockBody(records.toSet)

  private def blockRecord(
      tx: String,
      result: Option[String],
      events: Vector[String],
  ): TestBlockRecord =
    BlockRecord(
      tx = TestTx(Utf8(tx)),
      result = result.map(value => TestResult(Utf8(value))),
      events = events.map(value => TestEvent(Utf8(value))),
    )

  private def hex(
      value: String,
  ): UInt256 =
    UInt256.fromHex(value).toOption.get

  private type TestBlockRecord = BlockRecord[TestTx, TestResult, TestEvent]

  private final case class TestTx(
      value: Utf8,
  ) derives ByteEncoder

  private final case class TestResult(
      value: Utf8,
  ) derives ByteEncoder

  private final case class TestEvent(
      value: Utf8,
  ) derives ByteEncoder

  private final case class CollisionTx(
      label: String,
  )

  private given ByteEncoder[CollisionTx] =
    _ => ByteVector(0xca.toByte, 0xfe.toByte)
