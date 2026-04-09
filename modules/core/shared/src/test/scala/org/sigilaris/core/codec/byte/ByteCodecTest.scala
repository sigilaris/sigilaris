package org.sigilaris.core.codec.byte

import java.time.Instant

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import scodec.bits.ByteVector
import org.sigilaris.core.codec.CodecLawSupport.ByteLaws

class ByteCodecTest extends HedgehogSuite:
  property("Boolean roundtrip"):
    for boolean <- Gen.boolean.forAll
    yield
      val encoded = ByteEncoder[Boolean].encode(boolean)
      Result.all(
        List(
          ByteLaws.roundTrip(boolean),
          ByteLaws.deterministicEncoding(boolean),
          encoded ==== (if boolean then ByteVector(0x01.toByte)
                        else ByteVector(0x00.toByte)),
        ),
      )

  property("BigInt roundtrip"):
    for bignat <- Gen
        .bytes(Range.linear(1, 64))
        .map(BigInt(_))
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(bignat),
        ByteLaws.deterministicEncoding(bignat),
      ),
    )

  property("Byte roundtrip"):
    for byte <- Gen
        .byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue))
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(byte),
        ByteLaws.deterministicEncoding(byte),
      ),
    )

  property("Long roundtrip"):
    for long <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(long),
        ByteLaws.deterministicEncoding(long),
      ),
    )

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
      Result.all(
        List(
          ByteLaws.roundTrip(instant),
          ByteLaws.deterministicEncoding(instant),
        ),
      )

  property("Option[Long] roundtrip"):
    for opt <- Gen
        .long(Range.linearFrom(0L, Long.MinValue, Long.MaxValue))
        .option
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(opt),
        ByteLaws.deterministicEncoding(opt),
      ),
    )

  property("Set[Byte] roundtrip"):
    for set <- Gen
        .list(
          Gen.byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue)),
          Range.linear(0, 20),
        )
        .map(_.toSet)
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(set),
        ByteLaws.deterministicEncoding(set),
      ),
    )

  property("Map[Byte, Long] roundtrip"):
    for map <- Gen
        .list(
          for
            k <- Gen.byte(Range.linearFrom(0, Byte.MinValue, Byte.MaxValue))
            v <- Gen.long(Range.linear(0L, 1000L))
          yield (k, v),
          Range.linear(0, 10),
        )
        .map(_.toMap)
        .forAll
    yield Result.all(
      List(
        ByteLaws.roundTrip(map),
        ByteLaws.deterministicEncoding(map),
      ),
    )
