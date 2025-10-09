package org.sigilaris.core.codec.byte

import java.time.Instant

import hedgehog.munit.HedgehogSuite
import hedgehog.*

class ByteCodecTest extends HedgehogSuite:
  property("BigInt roundtrip"):
    for bignat <- Gen
        .bytes(Range.linear(1, 64))
        .map(BigInt(_))
        .forAll
    yield
      val encoded = ByteEncoder[BigInt].encode(bignat)

      ByteDecoder[BigInt].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all(
            List(
              decoded ==== bignat,
              Result.assert(remainder.isEmpty),
            ),
          )
        case _ => Result.failure

  property("Byte roundtrip"):
    for byte <- Gen
        .byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue))
        .forAll
    yield
      val encoded = ByteEncoder[Byte].encode(byte)

      ByteDecoder[Byte].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== byte,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure

  property("Long roundtrip"):
    for long <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .forAll
    yield
      val encoded = ByteEncoder[Long].encode(long)

      ByteDecoder[Long].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== long,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure

  property("Instant roundtrip"):
    for epochMilli <- Gen
        .long:
          Range.linearFrom(
            0L,
            Long.MinValue,
            Long.MaxValue,
          )
        .forAll
    yield
      val instant = Instant.ofEpochMilli(epochMilli)
      val encoded = ByteEncoder[Instant].encode(instant)

      ByteDecoder[Instant].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== instant,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure

  property("Option[Long] roundtrip"):
    for opt <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .option
        .forAll
    yield
      val encoded = ByteEncoder[Option[Long]].encode(opt)

      ByteDecoder[Option[Long]].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== opt,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure

  property("Set[Byte] roundtrip"):
    for set <- Gen
        .list(
          Gen.byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue)),
          Range.linear(0, 20)
        )
        .map(_.toSet)
        .forAll
    yield
      val encoded = ByteEncoder[Set[Byte]].encode(set)

      ByteDecoder[Set[Byte]].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== set,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure

  property("Map[Byte, Long] roundtrip"):
    for map <- Gen
        .list(
          for
            k <- Gen.byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue))
            v <- Gen.long(Range.linear(0L, 1000L))
          yield (k, v),
          Range.linear(0, 10)
        )
        .map(_.toMap)
        .forAll
    yield
      val encoded = ByteEncoder[Map[Byte, Long]].encode(map)

      ByteDecoder[Map[Byte, Long]].decode(encoded) match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all:
            List(
              decoded ==== map,
              Result.assert(remainder.isEmpty),
            )
        case _ => Result.failure
