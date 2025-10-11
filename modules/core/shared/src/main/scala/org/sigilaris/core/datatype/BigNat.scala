package org.sigilaris.core
package datatype

import cats.Eq
import cats.syntax.either.*

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.json.{JsonDecoder, JsonEncoder}
import failure.DecodeFailure

opaque type BigNat = BigInt :| Positive0

object BigNat:
  val Zero: BigNat = BigInt(0)
  val One: BigNat  = BigInt(1)

  def fromBigInt(n: BigInt): Either[String, BigNat] =
    n.refineEither[Positive0] // refineV[NonNegative](n)

  extension (bignat: BigNat) def toBigInt: BigInt = bignat

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBigInt(n: BigInt): BigNat = fromBigInt(n) match
    case Right(nat) => nat
    case Left(e)    => throw new Exception(e)

  def unsafeFromLong(long: Long): BigNat = unsafeFromBigInt(BigInt(long))

  def add(x: BigNat, y: BigNat): BigNat = (x + y).refineUnsafe[Positive0]

  def multiply(x: BigNat, y: BigNat): BigNat = (x * y).refineUnsafe[Positive0]

  def divide(x: BigNat, y: BigNat): BigNat = (x / y).refineUnsafe[Positive0]

  def tryToSubtract(x: BigNat, y: BigNat): Either[String, BigNat] =
    (x - y).refineEither[Positive0]

  given bignatJsonDecoder: JsonDecoder[BigNat] = JsonDecoder.bigIntDecoder.emap:
    fromBigInt(_).leftMap(DecodeFailure(_))

  given bignatJsonEncoder: JsonEncoder[BigNat] =
    JsonEncoder.bigIntEncoder.contramap(_.toBigInt)

  given bignatByteDecoder: ByteDecoder[BigNat] = ByteDecoder.bignatByteDecoder

  given bignatByteEncoder: ByteEncoder[BigNat] = ByteEncoder.bignatByteEncoder

  given bignatEq: Eq[BigNat] = Eq.fromUniversalEquals
