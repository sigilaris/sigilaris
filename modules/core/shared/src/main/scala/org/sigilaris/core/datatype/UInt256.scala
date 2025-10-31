package org.sigilaris.core.datatype

import scodec.bits.ByteVector
import cats.Eq

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import cats.syntax.either.*
import org.sigilaris.core.failure.{DecodeFailure, UInt256Failure, UInt256InvalidHex, UInt256NegativeValue, UInt256Overflow, UInt256TooLong}

/** 256-bit unsigned integer with fixed-size representation.
  *
  * UInt256 is an opaque type wrapping a 32-byte [[scodec.bits.ByteVector]] stored in big-endian format.
  * It represents unsigned integers in the range [0, 2^256 - 1] with a fixed memory footprint.
  *
  * Key features:
  *   - Fixed 32-byte (256-bit) big-endian representation
  *   - Supports construction from bytes, BigInt, and hex strings
  *   - Automatic left-padding with zeros for values shorter than 32 bytes
  *   - Type-safe failures via [[org.sigilaris.core.failure.UInt256Failure]] ADT
  *   - JSON codec encodes/decodes as lowercase hex string without `0x` prefix
  *   - Fixed-size byte codec (always 32 bytes)
  *
  * @example
  * ```scala
  * import scodec.bits.ByteVector
  *
  * // From hex string
  * val u1 = UInt256.fromHex("ff")  // Right(UInt256 with 32 bytes, left-padded)
  * val u2 = UInt256.fromHex("0x123abc")  // Right(UInt256)
  * val invalid = UInt256.fromHex("xyz")  // Left(UInt256InvalidHex(...))
  *
  * // From BigInt
  * val u3 = UInt256.fromBigIntUnsigned(BigInt(42))  // Right(UInt256)
  * val negative = UInt256.fromBigIntUnsigned(BigInt(-1))  // Left(UInt256NegativeValue)
  * val overflow = UInt256.fromBigIntUnsigned(BigInt(2).pow(256))  // Left(UInt256Overflow(...))
  *
  * // From bytes
  * val bytes = ByteVector.fromHex("cafe").get
  * val u4 = UInt256.fromBytesBE(bytes)  // Right(UInt256), left-padded to 32 bytes
  *
  * // Conversion
  * val bigInt: BigInt = u1.toBigIntUnsigned
  * val hex: String = u1.toHexLower  // lowercase hex without 0x prefix
  * ```
  *
  * @note Byte encoding is fixed-size (32 bytes). Values are stored in big-endian format.
  * @note JSON encoding uses lowercase hex strings without `0x` prefix for consistency.
  * @note The hex decoder accepts optional `0x` prefix, whitespace, and underscores for flexibility.
  *
  * @see [[org.sigilaris.core.failure.UInt256Failure]] for typed failure cases
  * @see [[org.sigilaris.core.codec.byte.ByteEncoder]] for byte encoding details
  * @see [[org.sigilaris.core.codec.json.JsonEncoder]] for JSON encoding details
  */
opaque type UInt256 = ByteVector

object UInt256:
  /** Fixed size in bytes for UInt256 (32 bytes = 256 bits). */
  val Size: Int = 32

  /** Creates a UInt256 from big-endian bytes, left-padding with zeros if shorter.
    *
    * @param bytes the input bytes in big-endian format
    * @return `Right(UInt256)` if bytes.length ≤ 32, `Left(UInt256TooLong)` if bytes.length > 32
    *
    * @example
    * ```scala
    * import scodec.bits.ByteVector
    *
    * val bytes = ByteVector.fromHex("ff").get
    * UInt256.fromBytesBE(bytes)  // Right(UInt256), left-padded to 32 bytes
    *
    * val tooLong = ByteVector.fill(33)(0xff.toByte)
    * UInt256.fromBytesBE(tooLong)  // Left(UInt256TooLong(33, 32))
    * ```
    *
    * @note Input shorter than 32 bytes is left-padded with zeros.
    * @see [[unsafeFromBytesBE]] for throwing version
    */
  def fromBytesBE(bytes: ByteVector): Either[UInt256Failure, UInt256] =
    Either.cond(
      bytes.length <= Size,
      leftPadTo32(bytes),
      UInt256TooLong(bytes.length, Size),
    )

  /** Unsafe constructor from big-endian bytes, throwing on invalid input.
    *
    * @param bytes the input bytes in big-endian format
    * @return UInt256 if bytes.length ≤ 32
    * @throws IllegalArgumentException if bytes.length > 32
    *
    * @note Use [[fromBytesBE]] for safe construction. This is intended for constants and test data.
    * @see [[fromBytesBE]] for safe version
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBytesBE(bytes: ByteVector): UInt256 =
    fromBytesBE(bytes) match
      case Right(v) => v
      case Left(e)  => throw new IllegalArgumentException(e)

  /** Constructs a UInt256 from an unsigned BigInt.
    *
    * @param n the unsigned BigInt value (must satisfy 0 ≤ n < 2^256)
    * @return `Right(UInt256)` if valid, `Left(UInt256Failure)` otherwise
    *
    * @example
    * ```scala
    * UInt256.fromBigIntUnsigned(BigInt(42))  // Right(UInt256)
    * UInt256.fromBigIntUnsigned(BigInt(0))   // Right(UInt256)
    * UInt256.fromBigIntUnsigned(BigInt(-1))  // Left(UInt256NegativeValue)
    * UInt256.fromBigIntUnsigned(BigInt(2).pow(256))  // Left(UInt256Overflow(...))
    * ```
    *
    * @note Fails with [[org.sigilaris.core.failure.UInt256NegativeValue]] if n < 0
    * @note Fails with [[org.sigilaris.core.failure.UInt256Overflow]] if n ≥ 2^256
    * @see [[unsafeFromBigIntUnsigned]] for throwing version
    */
  def fromBigIntUnsigned(n: BigInt): Either[UInt256Failure, UInt256] =
    for
      nonNeg <- Either.cond(n.signum >= 0, n, UInt256NegativeValue)
      arr = nonNeg.toByteArray.dropWhile(_ == 0.toByte)
      value <- Either.cond(
        arr.length <= Size,
        leftPadTo32(ByteVector.view(arr)),
        UInt256Overflow("BigInt does not fit into 256 bits"),
      )
    yield value

  /** Constructs a UInt256 from an unsigned java.math.BigInteger (JVM fast-path).
    *
    * This is a JVM-optimized fast-path for interoperability with BigInteger-based cryptographic operations.
    * Validates that the input is non-negative and fits within 256 bits.
    *
    * @param value the unsigned BigInteger value (must satisfy 0 ≤ value < 2^256)
    * @return `Right(UInt256)` if valid, `Left(UInt256Failure)` otherwise
    *
    * @example
    * ```scala
    * import java.math.BigInteger
    *
    * UInt256.fromBigIntegerUnsigned(BigInteger.valueOf(42))  // Right(UInt256)
    * UInt256.fromBigIntegerUnsigned(BigInteger.ZERO)         // Right(UInt256)
    * UInt256.fromBigIntegerUnsigned(BigInteger.valueOf(-1))  // Left(UInt256NegativeValue)
    * UInt256.fromBigIntegerUnsigned(BigInteger.TWO.pow(256)) // Left(UInt256Overflow(...))
    * ```
    *
    * @note Fails with [[org.sigilaris.core.failure.UInt256NegativeValue]] if value.signum < 0
    * @note Fails with [[org.sigilaris.core.failure.UInt256Overflow]] if value.bitLength > 256
    */
  def fromBigIntegerUnsigned(value: java.math.BigInteger): Either[UInt256Failure, UInt256] =
    for
      nonNeg <- Either.cond(value.signum >= 0, value, UInt256NegativeValue)
      validRange <- Either.cond(
        nonNeg.bitLength <= 256,
        nonNeg,
        UInt256Overflow("BigInteger does not fit into 256 bits"),
      )
      arr = validRange.toByteArray.dropWhile(_ == 0.toByte)
    yield leftPadTo32(ByteVector.view(arr))

  /** Creates a UInt256 from a hex string with flexible parsing.
    *
    * Accepts hex strings with optional `0x` prefix, whitespace, and underscores.
    * Empty string is treated as zero.
    *
    * @param hex the hex string to parse
    * @return `Right(UInt256)` if valid, `Left(UInt256Failure)` otherwise
    *
    * @example
    * ```scala
    * UInt256.fromHex("ff")           // Right(UInt256), left-padded
    * UInt256.fromHex("0xff")         // Right(UInt256)
    * UInt256.fromHex("0x_ff_00")     // Right(UInt256), underscores ignored
    * UInt256.fromHex("")             // Right(UInt256), zero
    * UInt256.fromHex("xyz")          // Left(UInt256InvalidHex(...))
    * UInt256.fromHex("0x" + "ff" * 33)  // Left(UInt256TooLong(33, 32))
    * ```
    *
    * @note Delegates to `ByteVector.fromHexDescriptive` for parsing
    * @note Result is left-padded to 32 bytes
    * @note Fails with [[org.sigilaris.core.failure.UInt256InvalidHex]] on invalid hex characters
    * @note Fails with [[org.sigilaris.core.failure.UInt256TooLong]] if decoded bytes exceed 32
    */
  def fromHex(hex: String): Either[UInt256Failure, UInt256] =
    for
      parsed <- ByteVector.fromHexDescriptive(hex.trim).leftMap(msg => UInt256InvalidHex(msg))
      value <- Either.cond(
        parsed.length <= Size,
        leftPadTo32(parsed),
        UInt256TooLong(parsed.length, Size),
      )
    yield value

  /** Unsafe constructor from unsigned BigInt, throwing on invalid input.
    *
    * @param n the unsigned BigInt value
    * @return UInt256 if 0 ≤ n < 2^256
    * @throws IllegalArgumentException if n < 0 or n ≥ 2^256
    *
    * @note Use [[fromBigIntUnsigned]] for safe construction. This is intended for constants and test data.
    * @see [[fromBigIntUnsigned]] for safe version
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def unsafeFromBigIntUnsigned(n: BigInt): UInt256 =
    fromBigIntUnsigned(n) match
      case Right(v) => v
      case Left(e)  => throw new IllegalArgumentException(e)

  /** Returns the underlying 32-byte big-endian representation.
    *
    * @param u the UInt256 value
    * @return 32-byte ByteVector in big-endian format
    */
  extension (u: UInt256)
    inline def bytes: ByteVector = u

    /** Interprets the UInt256 as an unsigned BigInt.
      *
      * @return BigInt in range [0, 2^256 - 1]
      */
    def toBigIntUnsigned: BigInt = BigInt(1, u.toArray)

    /** Converts to unsigned java.math.BigInteger (JVM fast-path).
      *
      * This is a JVM-optimized fast-path for interoperability with BigInteger-based cryptographic operations.
      * Converts the 32-byte big-endian representation to a non-negative BigInteger.
      *
      * @return BigInteger in range [0, 2^256 - 1]
      *
      * @example
      * ```scala
      * val u = UInt256.unsafeFromBigIntUnsigned(BigInt(255))
      * u.toJavaBigIntegerUnsigned  // BigInteger with value 255
      * ```
      */
    def toJavaBigIntegerUnsigned: java.math.BigInteger =
      new java.math.BigInteger(1, u.toArray)

    /** Converts to lowercase hex string without `0x` prefix.
      *
      * @return 64-character lowercase hex string
      *
      * @example
      * ```scala
      * val u = UInt256.unsafeFromBigIntUnsigned(BigInt(255))
      * u.toHexLower  // "00000000000000000000000000000000000000000000000000000000000000ff"
      * ```
      */
    def toHexLower: String = u.toHex

  private def leftPadTo32(bv: ByteVector): UInt256 =
    if bv.length >= Size then bv.takeRight(Size)
    else ByteVector.fill(Size - bv.length)(0.toByte) ++ bv

  /** Cats Eq instance for UInt256 using universal equality.
    *
    * Two UInt256 values are equal if their byte representations are equal.
    *
    * @return Eq instance
    */
  given eq: Eq[UInt256] = Eq.fromUniversalEquals

  /** Byte encoder for UInt256.
    *
    * Encodes as fixed 32-byte big-endian representation.
    *
    * @return encoder instance
    * @note Always produces exactly 32 bytes
    * @see [[uint256ByteDecoder]] for decoding
    */
  given uint256ByteEncoder: ByteEncoder[UInt256] = (u: UInt256) => u

  /** Byte decoder for UInt256.
    *
    * Decodes exactly 32 bytes as big-endian UInt256.
    *
    * @return decoder instance
    * @note Fails with [[org.sigilaris.core.failure.DecodeFailure]] if fewer than 32 bytes available
    * @see [[uint256ByteEncoder]] for encoding
    */
  given uint256ByteDecoder: ByteDecoder[UInt256] = bytes =>
    ByteDecoder
      .fromFixedSizeBytes[ByteVector](Size)(identity)
      .map[UInt256](bv => (bv: UInt256))
      .decode(bytes)

  /** JSON encoder for UInt256.
    *
    * Encodes as lowercase hex string without `0x` prefix.
    *
    * @return encoder instance
    *
    * @example
    * ```scala
    * val u = UInt256.unsafeFromBigIntUnsigned(BigInt(255))
    * val json = JsonEncoder[UInt256].encode(u)  // "00...00ff" (64 chars)
    * ```
    *
    * @note Always produces a 64-character hex string (32 bytes * 2 hex digits)
    * @see [[uint256JsonDecoder]] for decoding
    */
  given uint256JsonEncoder: JsonEncoder[UInt256] =
    JsonEncoder.stringEncoder.contramap(_.toHexLower)

  /** JSON decoder for UInt256.
    *
    * Decodes from hex string, accepting optional `0x` prefix, whitespace, and underscores.
    *
    * @return decoder instance
    *
    * @example
    * ```scala
    * JsonDecoder[UInt256].decode(JsonValue.Str("ff"))        // Right(UInt256)
    * JsonDecoder[UInt256].decode(JsonValue.Str("0xff"))      // Right(UInt256)
    * JsonDecoder[UInt256].decode(JsonValue.Str("0x_ff_00"))  // Right(UInt256)
    * ```
    *
    * @note Fails with [[org.sigilaris.core.failure.DecodeFailure]] on invalid hex or overflow
    * @see [[uint256JsonEncoder]] for encoding
    */
  given uint256JsonDecoder: JsonDecoder[UInt256] =
    JsonDecoder.stringDecoder.emap: s =>
      fromHex(s).leftMap(e => DecodeFailure(e.msg))

  /** JSON key codec for UInt256.
    *
    * Encodes UInt256 as lowercase hex string for use as JSON object keys.
    * Decodes from hex string keys, accepting optional `0x` prefix, whitespace, and underscores.
    *
    * @return key codec instance
    *
    * @example
    * ```scala
    * import io.circe.syntax.*
    *
    * val key = UInt256.unsafeFromBigIntUnsigned(BigInt(255))
    * val json = Map(key -> "value").asJson
    * // JSON: { "00...00ff": "value" }
    * ```
    *
    * @note Keys are encoded as 64-character hex strings (32 bytes * 2 hex digits)
    * @note Fails with [[org.sigilaris.core.failure.DecodeFailure]] on invalid hex or overflow
    * @see [[uint256JsonEncoder]] for value encoding
    * @see [[uint256JsonDecoder]] for value decoding
    */
  given uint256JsonKeyCodec: JsonKeyCodec[UInt256] =
    def decodeFromHex(s: String): Either[DecodeFailure, UInt256] =
      fromHex(s).leftMap(e => DecodeFailure(e.msg))
    JsonKeyCodec[String].narrow(decodeFromHex, _.toHexLower)

  /** OrderedCodec instance for UInt256.
    *
    * UInt256's fixed 32-byte big-endian encoding preserves ordering: smaller unsigned
    * integers encode to lexicographically smaller byte sequences.
    *
    * The big-endian byte representation satisfies the ordering law:
    *   compare(x, y) ≡ encode(x).compare(encode(y))
    *
    * @return OrderedCodec instance
    * @see [[org.sigilaris.core.codec.OrderedCodec]] for law details
    */
  given uint256OrderedCodec: org.sigilaris.core.codec.OrderedCodec[UInt256] =
    new org.sigilaris.core.codec.OrderedCodec[UInt256]:
      def encode(u: UInt256): ByteVector = u.bytes
      def decode(bv: ByteVector): Either[DecodeFailure, org.sigilaris.core.codec.byte.DecodeResult[UInt256]] =
        ByteDecoder[UInt256].decode(bv)
      def compare(x: UInt256, y: UInt256): Int =
        x.toBigIntUnsigned.compare(y.toBigIntUnsigned)
