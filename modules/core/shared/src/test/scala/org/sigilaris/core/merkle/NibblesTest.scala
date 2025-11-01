package org.sigilaris.core
package merkle

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, DecodeResult}
import codec.byte.ByteEncoder.ops.*
import org.sigilaris.core.failure.DecodeFailure
import Nibbles.*

import hedgehog.*
import hedgehog.munit.HedgehogSuite


class NibblesTest extends HedgehogSuite:

  property("roundtrip of nibbles byte codec"):
    val nibblesGen = for
      bytes <- Gen.bytes(Range.linear(0, 64))
      byteVector = ByteVector.view(bytes)
      bits <- Gen.element1(
        byteVector.bits,
        byteVector.bits.drop(4),
      )
    yield bits.assumeNibbles

    nibblesGen.forAll.map: nibbles =>      
      val encoded = nibbles.toBytes
      val decodedEither = ByteDecoder[Nibbles].decode(encoded)

      decodedEither match
        case Right(DecodeResult(decoded, remainder)) =>
          Result.all(
            List(
              decoded ==== nibbles,
              Result.assert(remainder.isEmpty),
            ),
          )
        case Left(DecodeFailure(msg)) =>
          println(s"Encoded: ${encoded.toHex}")
          println(s"Decoding Failure: ${msg}")

          Result.failure
