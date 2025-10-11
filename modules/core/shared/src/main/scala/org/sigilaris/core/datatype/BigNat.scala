package org.sigilaris.core
package datatype

import cats.Eq
import cats.syntax.either.*

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

import codec.byte.{ByteDecoder, ByteEncoder}
import codec.json.{JsonDecoder, JsonEncoder}
import failure.DecodeFailure

/** Non-negative arbitrary-precision integer (Natural number).
  *
  * BigNat is an opaque type wrapping a refined BigInt constrained to non-negative values (≥ 0).
  * It provides arithmetic operations that preserve the non-negative invariant and safely handles
  * operations that could violate this constraint.
  *
  * Key features:
  *   - Zero-cost abstraction over BigInt with compile-time safety
  *   - Arithmetic operations (addition, multiplication, division) always return valid BigNat
  *   - Subtraction returns `Either[String, BigNat]` to handle potential negative results
  *   - JSON codec encodes/decodes as string by default (configurable via [[org.sigilaris.core.codec.json.JsonConfig]])
  *   - Variable-length byte encoding via `BigNat` prefix
  *
  * @example
  * ```scala
  * val n1 = BigNat.unsafeFromLong(42L)
  * val n2 = BigNat.unsafeFromLong(10L)
  * val sum = BigNat.add(n1, n2)  // 52
  * val product = BigNat.multiply(n1, n2)  // 420
  *
  * val diff = BigNat.tryToSubtract(n1, n2)  // Right(32)
  * val invalid = BigNat.tryToSubtract(n2, n1)  // Left("Constraint failed: ...")
  *
  * // Safe construction from BigInt
  * val safe = BigNat.fromBigInt(BigInt(100))  // Right(BigNat(100))
  * val negative = BigNat.fromBigInt(BigInt(-1))  // Left("Constraint failed: ...")
  * ```
  *
  * @note This type uses variable-length byte encoding. The encoded size depends on the magnitude of the value.
  * @note JSON codec behavior is controlled by [[org.sigilaris.core.codec.json.JsonConfig]]. By default,
  *       values are encoded as JSON strings and decoders accept both string and numeric representations.
  *
  * @see [[org.sigilaris.core.codec.byte.ByteEncoder]] for byte encoding details
  * @see [[org.sigilaris.core.codec.json.JsonEncoder]] for JSON encoding details
  */
opaque type BigNat = BigInt :| Positive0

object BigNat:
  /** The natural number zero. */
  val Zero: BigNat = BigInt(0)

  /** The natural number one. */
  val One: BigNat  = BigInt(1)

  /** Safely constructs a BigNat from a BigInt.
    *
    * @param n the BigInt value to convert
    * @return `Right(BigNat)` if n ≥ 0, `Left(error message)` if n < 0
    *
    * @example
    * ```scala
    * BigNat.fromBigInt(BigInt(42))   // Right(BigNat(42))
    * BigNat.fromBigInt(BigInt(-1))   // Left("Constraint failed: ...")
    * BigNat.fromBigInt(BigInt(0))    // Right(BigNat(0))
    * ```
    */
  def fromBigInt(n: BigInt): Either[String, BigNat] =
    n.refineEither[Positive0] // refineV[NonNegative](n)

  /** Converts BigNat to BigInt.
    *
    * @param bignat the BigNat value
    * @return the underlying BigInt value (always ≥ 0)
    */
  extension (bignat: BigNat) def toBigInt: BigInt = bignat

  /** Constructs a BigNat from a BigInt, throwing on negative values.
    *
    * @param n the BigInt value to convert
    * @return BigNat if n ≥ 0
    * @throws Exception if n < 0
    *
    * @note Use [[fromBigInt]] for safe construction. This method is intended for cases where
    *       the value is known to be valid (e.g., constants, test fixtures).
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBigInt(n: BigInt): BigNat = fromBigInt(n) match
    case Right(nat) => nat
    case Left(e)    => throw new Exception(e)

  /** Constructs a BigNat from a Long, throwing on negative values.
    *
    * @param long the Long value to convert
    * @return BigNat if long ≥ 0
    * @throws Exception if long < 0
    *
    * @note Use [[fromBigInt]] for safe construction. This method is intended for constants and test data.
    */
  def unsafeFromLong(long: Long): BigNat = unsafeFromBigInt(BigInt(long))

  /** Adds two natural numbers.
    *
    * @param x first operand
    * @param y second operand
    * @return x + y (always valid BigNat)
    *
    * @note Addition of non-negative numbers is always non-negative, so this operation never fails.
    */
  def add(x: BigNat, y: BigNat): BigNat = (x + y).refineUnsafe[Positive0]

  /** Multiplies two natural numbers.
    *
    * @param x first operand
    * @param y second operand
    * @return x * y (always valid BigNat)
    *
    * @note Multiplication of non-negative numbers is always non-negative, so this operation never fails.
    */
  def multiply(x: BigNat, y: BigNat): BigNat = (x * y).refineUnsafe[Positive0]

  /** Divides two natural numbers using integer division.
    *
    * @param x dividend
    * @param y divisor
    * @return x / y (always valid BigNat)
    *
    * @note Integer division of non-negative numbers is always non-negative.
    * @note Division by zero will throw ArithmeticException (inherited from BigInt behavior).
    */
  def divide(x: BigNat, y: BigNat): BigNat = (x / y).refineUnsafe[Positive0]

  /** Attempts to subtract y from x.
    *
    * @param x minuend
    * @param y subtrahend
    * @return `Right(x - y)` if x ≥ y, `Left(error message)` if x < y
    *
    * @example
    * ```scala
    * val n1 = BigNat.unsafeFromLong(10)
    * val n2 = BigNat.unsafeFromLong(3)
    * BigNat.tryToSubtract(n1, n2)  // Right(BigNat(7))
    * BigNat.tryToSubtract(n2, n1)  // Left("Constraint failed: ...")
    * ```
    *
    * @note This is the only arithmetic operation that can fail, as subtraction may produce negative results.
    */
  def tryToSubtract(x: BigNat, y: BigNat): Either[String, BigNat] =
    (x - y).refineEither[Positive0]

  /** JSON decoder for BigNat.
    *
    * Decodes from JSON string or number, validating non-negativity.
    * Behavior is controlled by [[org.sigilaris.core.codec.json.JsonConfig]].
    * By default, accepts both string and numeric representations.
    *
    * @return decoder instance
    * @see [[bignatJsonEncoder]] for encoding
    */
  given bignatJsonDecoder: JsonDecoder[BigNat] = JsonDecoder.bigIntDecoder.emap:
    fromBigInt(_).leftMap(DecodeFailure(_))

  /** JSON encoder for BigNat.
    *
    * Encodes as JSON string by default (configurable via [[org.sigilaris.core.codec.json.JsonConfig]]).
    *
    * @return encoder instance
    * @see [[bignatJsonDecoder]] for decoding
    */
  given bignatJsonEncoder: JsonEncoder[BigNat] =
    JsonEncoder.bigIntEncoder.contramap(_.toBigInt)

  /** Byte decoder for BigNat.
    *
    * Decodes from variable-length byte representation.
    * The encoding format is implementation-defined and may include length prefix.
    *
    * @return decoder instance
    * @see [[bignatByteEncoder]] for encoding
    */
  given bignatByteDecoder: ByteDecoder[BigNat] = ByteDecoder.bignatByteDecoder

  /** Byte encoder for BigNat.
    *
    * Encodes to variable-length byte representation.
    * The encoding format is implementation-defined and may include length prefix.
    *
    * @return encoder instance
    * @see [[bignatByteDecoder]] for decoding
    */
  given bignatByteEncoder: ByteEncoder[BigNat] = ByteEncoder.bignatByteEncoder

  /** Cats Eq instance for BigNat using universal equality.
    *
    * @return Eq instance
    */
  given bignatEq: Eq[BigNat] = Eq.fromUniversalEquals
