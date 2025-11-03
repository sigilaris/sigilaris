package org.sigilaris.core.codec

import munit.FunSuite
import scodec.bits.ByteVector
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8Key}

/** Law verification tests for OrderedCodec instances.
  *
  * This test suite verifies the fundamental law of OrderedCodec:
  *   sign(compare(x, y)) == sign(encode(x).compare(encode(y)))
  *
  * For each type, we test:
  * 1. Ordering preservation: if x < y, then encode(x) < encode(y)
  * 2. Equality preservation: if x == y, then encode(x) == encode(y)
  * 3. Reverse ordering: if x > y, then encode(x) > encode(y)
  * 4. Round-trip: decode(encode(x)) == Some(x)
  */
class OrderedCodecLawTest extends FunSuite:

  /** Verifies the OrderedCodec law for a list of values.
    * Tests all pairs (x, y) where x and y are in the list.
    */
  def verifyLaw[A](values: List[A])(using oc: OrderedCodec[A]): Unit =
    for
      x <- values
      y <- values
    do
      val ordering        = oc.compare(x, y)
      val encodedX        = oc.encode(x)
      val encodedY        = oc.encode(y)
      val encodedOrdering = encodedX.compare(encodedY)

      assert(
        ordering.sign == encodedOrdering.sign,
        s"Law violated: compare($x, $y) = $ordering, but encoded comparison = $encodedOrdering"
      )

  /** Verifies round-trip property: decode(encode(x)) == Right(DecodeResult(x, remainder)) */
  def verifyRoundTrip[A](values: List[A])(using oc: OrderedCodec[A]): Unit =
    values.foreach: x =>
      val encoded = oc.encode(x)
      val decoded = oc.decode(encoded)
      decoded match
        case Right(result) =>
          assert(result.value == x, s"Round-trip failed for $x: decoded to ${result.value}")
        case Left(failure) =>
          fail(s"Round-trip failed for $x: decode failed with $failure")

  test("OrderedCodec[ByteVector] satisfies law"):
    val values = List(
      ByteVector.empty,
      ByteVector(0x00),
      ByteVector(0x01),
      ByteVector(0xff),
      ByteVector(0x00, 0x00),
      ByteVector(0x00, 0x01),
      ByteVector(0x01, 0x00),
      ByteVector(0xff, 0xff),
    )
    verifyLaw(values)
    verifyRoundTrip(values)


  test("OrderedCodec[BigNat] satisfies law"):
    val values = List(
      BigNat.unsafeFromLong(0),
      BigNat.unsafeFromLong(1),
      BigNat.unsafeFromLong(100),
      BigNat.unsafeFromLong(1000000),
      BigNat.unsafeFromBigInt(BigInt("1000000000000000000")),
      BigNat.unsafeFromBigInt(BigInt("1000000000000000000000000000000")),
    )
    verifyLaw(values)
    verifyRoundTrip(values)

  test("OrderedCodec[UInt256] satisfies law"):
    val values = List(
      UInt256.unsafeFromBigIntUnsigned(BigInt(0)),
      UInt256.unsafeFromBigIntUnsigned(BigInt(1)),
      UInt256.unsafeFromBigIntUnsigned(BigInt(100)),
      UInt256.unsafeFromBigIntUnsigned(BigInt(255)),
      UInt256.unsafeFromBigIntUnsigned(BigInt(256)),
      UInt256.unsafeFromBigIntUnsigned(BigInt("1000000000000000000")),
      UInt256.unsafeFromBigIntUnsigned(BigInt(2).pow(255)),
      UInt256.unsafeFromBigIntUnsigned(BigInt(2).pow(256) - 1),
    )
    verifyLaw(values)
    verifyRoundTrip(values)

  test("OrderedCodec[Utf8Key] satisfies law"):
    val values = List(
      Utf8Key(""),
      Utf8Key("a"),
      Utf8Key("b"),
      Utf8Key("aa"),
      Utf8Key("ab"),
      Utf8Key("ba"),
      Utf8Key("abc"),
      Utf8Key("abd"),
      Utf8Key("z"),
      Utf8Key("한글"),
      Utf8Key("日本語"),
    )
    verifyLaw(values)
    verifyRoundTrip(values)

  // Specific boundary tests
  test("OrderedCodec[BigNat] handles boundary transitions"):
    val oc = OrderedCodec[BigNat]
    val zero = BigNat.unsafeFromLong(0)
    val one = BigNat.unsafeFromLong(1)
    val large = BigNat.unsafeFromBigInt(BigInt("1000000000000000000"))

    // Zero to one
    assert(oc.compare(zero, one) < 0)
    assert(oc.encode(zero).compare(oc.encode(one)) < 0)

    // One to large
    assert(oc.compare(one, large) < 0)
    assert(oc.encode(one).compare(oc.encode(large)) < 0)

    // Zero to large
    assert(oc.compare(zero, large) < 0)
    assert(oc.encode(zero).compare(oc.encode(large)) < 0)

  test("OrderedCodec[UInt256] handles boundary transitions"):
    val oc = OrderedCodec[UInt256]
    val zero = UInt256.unsafeFromBigIntUnsigned(BigInt(0))
    val one = UInt256.unsafeFromBigIntUnsigned(BigInt(1))
    val max = UInt256.unsafeFromBigIntUnsigned(BigInt(2).pow(256) - 1)

    // Zero to one
    assert(oc.compare(zero, one) < 0)
    assert(oc.encode(zero).compare(oc.encode(one)) < 0)

    // One to max
    assert(oc.compare(one, max) < 0)
    assert(oc.encode(one).compare(oc.encode(max)) < 0)

    // Zero to max
    assert(oc.compare(zero, max) < 0)
    assert(oc.encode(zero).compare(oc.encode(max)) < 0)

  test("OrderedCodec.satisfiesLaw method"):
    val ocBigNat = OrderedCodec[BigNat]
    val n1 = BigNat.unsafeFromLong(10)
    val n2 = BigNat.unsafeFromLong(100)

    assert(ocBigNat.satisfiesLaw(n1, n2))
    assert(ocBigNat.satisfiesLaw(n2, n1))
    assert(ocBigNat.satisfiesLaw(n1, n1))

  test("OrderedCodec[Utf8Key] handles empty string"):
    val oc = OrderedCodec[Utf8Key]
    val empty = Utf8Key("")
    val a = Utf8Key("a")

    assert(oc.compare(empty, a) < 0)
    assert(oc.encode(empty).compare(oc.encode(a)) < 0)

    assert(oc.compare(empty, empty) == 0)
    assert(oc.encode(empty).compare(oc.encode(empty)) == 0)

  test("OrderedCodec[Utf8Key] handles prefix relationships"):
    val oc = OrderedCodec[Utf8Key]

    // "a" < "aa"
    assert(oc.compare(Utf8Key("a"), Utf8Key("aa")) < 0)
    assert(oc.encode(Utf8Key("a")).compare(oc.encode(Utf8Key("aa"))) < 0)

    // "ab" < "abc"
    assert(oc.compare(Utf8Key("ab"), Utf8Key("abc")) < 0)
    assert(oc.encode(Utf8Key("ab")).compare(oc.encode(Utf8Key("abc"))) < 0)

  test("OrderedCodec[ByteVector] preserves lexicographic ordering"):
    val oc = OrderedCodec[ByteVector]

    val bv1 = ByteVector(0x01, 0x02)
    val bv2 = ByteVector(0x01, 0x03)
    val bv3 = ByteVector(0x02, 0x00)

    // bv1 < bv2 < bv3
    assert(oc.compare(bv1, bv2) < 0)
    assert(oc.compare(bv2, bv3) < 0)
    assert(oc.compare(bv1, bv3) < 0)

    // Encoded ordering should match
    assert(oc.encode(bv1).compare(oc.encode(bv2)) < 0)
    assert(oc.encode(bv2).compare(oc.encode(bv3)) < 0)
    assert(oc.encode(bv1).compare(oc.encode(bv3)) < 0)
