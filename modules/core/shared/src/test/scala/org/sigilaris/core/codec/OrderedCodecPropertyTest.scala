package org.sigilaris.core.codec

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import scodec.bits.ByteVector
import org.sigilaris.core.datatype.{BigNat, UInt256, Utf8Key}

/** Property-based tests for OrderedCodec instances using Hedgehog.
  *
  * These tests generate random values and verify that the OrderedCodec law holds
  * for all generated pairs.
  */
class OrderedCodecPropertyTest extends HedgehogSuite:

  // ByteVector tests
  property("ByteVector - OrderedCodec law"):
    for
      x <- Gen.bytes(Range.linear(0, 32)).map(ByteVector(_)).forAll
      y <- Gen.bytes(Range.linear(0, 32)).map(ByteVector(_)).forAll
    yield
      val oc = OrderedCodec[ByteVector]
      Result.assert(oc.satisfiesLaw(x, y))

  property("ByteVector - Round-trip"):
    for x <- Gen.bytes(Range.linear(0, 32)).map(ByteVector(_)).forAll
    yield
      val oc = OrderedCodec[ByteVector]
      val encoded = oc.encode(x)
      oc.decode(encoded) match
        case Right(result) => result.value ==== x
        case Left(_) => Result.failure

  property("ByteVector - Reflexivity"):
    for x <- Gen.bytes(Range.linear(0, 32)).map(ByteVector(_)).forAll
    yield
      val oc = OrderedCodec[ByteVector]
      oc.compare(x, x) ==== 0

  // BigNat tests
  property("BigNat - OrderedCodec law"):
    for
      x <- Gen.long(Range.linear(0, 1000000)).map(BigNat.unsafeFromLong).forAll
      y <- Gen.long(Range.linear(0, 1000000)).map(BigNat.unsafeFromLong).forAll
    yield
      val oc = OrderedCodec[BigNat]
      Result.assert(oc.satisfiesLaw(x, y))

  property("BigNat - Round-trip"):
    for x <- Gen.long(Range.linear(0, 1000000)).map(BigNat.unsafeFromLong).forAll
    yield
      val oc = OrderedCodec[BigNat]
      val encoded = oc.encode(x)
      oc.decode(encoded) match
        case Right(result) => result.value ==== x
        case Left(_) => Result.failure

  property("BigNat - Reflexivity"):
    for x <- Gen.long(Range.linear(0, 1000000)).map(BigNat.unsafeFromLong).forAll
    yield
      val oc = OrderedCodec[BigNat]
      oc.compare(x, x) ==== 0

  property("BigNat - Transitivity"):
    for
      x <- Gen.long(Range.linear(0, 100000)).map(BigNat.unsafeFromLong).forAll
      y <- Gen.long(Range.linear(0, 100000)).map(BigNat.unsafeFromLong).forAll
      z <- Gen.long(Range.linear(0, 100000)).map(BigNat.unsafeFromLong).forAll
    yield
      val oc = OrderedCodec[BigNat]
      val cxy = oc.compare(x, y)
      val cyz = oc.compare(y, z)
      val cxz = oc.compare(x, z)

      if cxy < 0 && cyz < 0 then
        Result.assert(cxz < 0)
      else
        Result.success

  // UInt256 tests
  property("UInt256 - OrderedCodec law"):
    for
      x <- Gen.long(Range.linear(0, Long.MaxValue)).map(n => UInt256.unsafeFromBigIntUnsigned(BigInt(n))).forAll
      y <- Gen.long(Range.linear(0, Long.MaxValue)).map(n => UInt256.unsafeFromBigIntUnsigned(BigInt(n))).forAll
    yield
      val oc = OrderedCodec[UInt256]
      Result.assert(oc.satisfiesLaw(x, y))

  property("UInt256 - Round-trip"):
    for x <- Gen.long(Range.linear(0, Long.MaxValue)).map(n => UInt256.unsafeFromBigIntUnsigned(BigInt(n))).forAll
    yield
      val oc = OrderedCodec[UInt256]
      val encoded = oc.encode(x)
      oc.decode(encoded) match
        case Right(result) => result.value ==== x
        case Left(_) => Result.failure

  property("UInt256 - Reflexivity"):
    for x <- Gen.long(Range.linear(0, Long.MaxValue)).map(n => UInt256.unsafeFromBigIntUnsigned(BigInt(n))).forAll
    yield
      val oc = OrderedCodec[UInt256]
      oc.compare(x, x) ==== 0

  // Utf8Key tests
  def genUtf8Key: Gen[Utf8Key] =
    Gen.choice1(
      Gen.constant(Utf8Key("")),
      Gen.string(Gen.alpha, Range.linear(1, 20)).map(Utf8Key(_)),
      Gen.constant(Utf8Key("한글")),
      Gen.constant(Utf8Key("日本語")),
      Gen.constant(Utf8Key("Ελληνικά")),
    )

  property("Utf8Key - OrderedCodec law"):
    for
      x <- genUtf8Key.forAll
      y <- genUtf8Key.forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      Result.assert(oc.satisfiesLaw(x, y))

  property("Utf8Key - Round-trip"):
    for x <- genUtf8Key.forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      val encoded = oc.encode(x)
      oc.decode(encoded) match
        case Right(result) => result.value ==== x
        case Left(_) => Result.failure

  property("Utf8Key - Reflexivity"):
    for x <- genUtf8Key.forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      oc.compare(x, x) ==== 0

  property("Utf8Key - Ordering matches string ordering"):
    for
      x <- genUtf8Key.forAll
      y <- genUtf8Key.forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      val stringCmp = x.asString.compare(y.asString)
      val codecCmp = oc.compare(x, y)
      stringCmp.sign ==== codecCmp.sign

  // Edge cases
  property("Utf8Key - Empty string"):
    for _ <- Gen.constant(()).forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      val empty = Utf8Key("")
      val encoded = oc.encode(empty)
      oc.decode(encoded) match
        case Right(result) => result.value ==== empty
        case Left(_) => Result.failure

  property("Utf8Key - String with null byte"):
    for _ <- Gen.constant(()).forAll
    yield
      val oc = OrderedCodec[Utf8Key]
      val withNull = Utf8Key("test\u0000byte")
      val encoded = oc.encode(withNull)
      oc.decode(encoded) match
        case Right(result) => result.value ==== withNull
        case Left(_) => Result.failure

  property("BigNat - Large values preserve ordering"):
    for exp <- Gen.int(Range.linear(100, 1000)).forAll
    yield
      val oc = OrderedCodec[BigNat]
      val large = BigNat.unsafeFromBigInt(BigInt(2).pow(exp))
      val small = BigNat.unsafeFromBigInt(BigInt(2).pow(exp - 1))

      Result.all(List(
        Result.assert(oc.compare(small, large) < 0),
        Result.assert(oc.satisfiesLaw(small, large))
      ))
