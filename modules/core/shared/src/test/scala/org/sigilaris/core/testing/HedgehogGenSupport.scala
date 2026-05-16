package org.sigilaris.core.testing

import hedgehog.*
import scodec.bits.ByteVector

import org.sigilaris.core.datatype.{BigNat, NonZeroBigNat}

/** Reusable generators shared by codec and datatype property suites. */
object HedgehogGenSupport:
  def byteVector(range: Range[Int]): Gen[ByteVector] =
    Gen.bytes(range).map(ByteVector.view)

  def fixedByteVector(size: Int): Gen[ByteVector] =
    byteVector(Range.singleton(size))

  val bigNat: Gen[BigNat] =
    byteVector(Range.linear(0, 64))
      .map(bytes => BigNat.unsafeFromBigInt(BigInt(1, bytes.toArray)))

  val nonZeroBigNat: Gen[NonZeroBigNat] =
    byteVector(Range.linear(1, 64))
      .map(bytes => NonZeroBigNat.unsafeFromBigInt(BigInt(1, bytes.toArray) + 1))
