package org.sigilaris.core
package datatype

import java.nio.charset.StandardCharsets

import cats.Eq
import cats.syntax.either.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import failure.DecodeFailure

/** UTF-8 encoded string with length-prefixed byte representation.
  *
  * Utf8 is an opaque type wrapping a String with specialized byte encoding that includes
  * a length prefix ([[BigNat]]) followed by raw UTF-8 bytes. This enables self-describing
  * serialization where the string length is explicitly encoded.
  *
  * Key features:
  *   - Zero-cost abstraction over String
  *   - Length-prefixed byte encoding: `[size: BigNat][raw UTF-8 bytes]`
  *   - JSON codec delegates to String encoding/decoding
  *   - JSON key codec support for use as Map keys
  *   - Automatic UTF-8 validation during decoding
  *
  * @example
  * ```scala
  * import org.sigilaris.core.codec.byte.ByteEncoder
  * import org.sigilaris.core.codec.json.JsonEncoder
  *
  * val text = Utf8("Hello, 世界!")
  *
  * // String conversion
  * val str: String = text.asString
  *
  * // Byte encoding includes length prefix
  * val bytes = ByteEncoder[Utf8].encode(text)
  * // bytes = [size as BigNat][UTF-8 bytes of "Hello, 世界!"]
  *
  * // JSON encoding (plain string)
  * val json = JsonEncoder[Utf8].encode(text)  // "Hello, 世界!"
  *
  * // Use as Map key
  * val map = Map(Utf8("key1") -> 42, Utf8("key2") -> 100)
  * ```
  *
  * @note Byte encoding format: `[size: BigNat][UTF-8 bytes]` where size is the byte length
  *       of the UTF-8 encoded string (not character count).
  * @note The byte decoder validates UTF-8 encoding and fails with [[org.sigilaris.core.failure.DecodeFailure]]
  *       on invalid sequences.
  * @note JSON encoding simply delegates to String, providing no additional structure.
  *
  * @see [[BigNat]] for the length prefix encoding
  * @see [[org.sigilaris.core.codec.byte.ByteEncoder]] for byte encoding details
  * @see [[org.sigilaris.core.codec.json.JsonKeyCodec]] for Map key support
  */
opaque type Utf8 = String

object Utf8:
  /** Constructs a Utf8 value from a String.
    *
    * @param value the string to wrap
    * @return Utf8 instance
    *
    * @example
    * ```scala
    * val text = Utf8("Hello")
    * val unicode = Utf8("こんにちは")
    * ```
    */
  def apply(value: String): Utf8 = value

  /** Converts Utf8 to String.
    *
    * @param u the Utf8 value
    * @return the underlying String
    */
  extension (u: Utf8)
    inline def asString: String = u

  /** Cats Eq instance for Utf8 using universal equality.
    *
    * @return Eq instance
    */
  given utf8Eq: Eq[Utf8] = Eq.fromUniversalEquals

  /** Byte encoder for Utf8.
    *
    * Encodes as length-prefixed UTF-8 bytes: `[size: BigNat][raw UTF-8 bytes]`.
    * The size represents the byte length of the UTF-8 encoding (not character count).
    *
    * @return encoder instance
    *
    * @example
    * ```scala
    * val text = Utf8("Hello")
    * val bytes = ByteEncoder[Utf8].encode(text)
    * // bytes = [5 as BigNat][0x48, 0x65, 0x6c, 0x6c, 0x6f]
    *
    * val unicode = Utf8("世界")
    * val unicodeBytes = ByteEncoder[Utf8].encode(unicode)
    * // unicodeBytes = [6 as BigNat][UTF-8 bytes for "世界"]
    * ```
    *
    * @note The size prefix uses [[BigNat]] encoding, which is variable-length.
    * @note Total encoded size is: size of BigNat + UTF-8 byte length
    * @see [[utf8ByteDecoder]] for decoding
    * @see [[BigNat]] for size prefix format
    */
  given utf8ByteEncoder: ByteEncoder[Utf8] = (u: Utf8) =>
    val data = ByteVector.view(u.getBytes(StandardCharsets.UTF_8))
    val sizeNat: BigNat = BigNat.unsafeFromLong(data.size)
    ByteEncoder[BigNat].encode(sizeNat) ++ data

  /** Byte decoder for Utf8.
    *
    * Decodes from length-prefixed format: `[size: BigNat][raw UTF-8 bytes]`.
    * Validates UTF-8 encoding and ensures sufficient bytes are available.
    *
    * @return decoder instance
    *
    * @note Fails with [[org.sigilaris.core.failure.DecodeFailure]] if:
    *       - Size prefix cannot be decoded as BigNat
    *       - Insufficient bytes remain after reading size prefix
    *       - Bytes are not valid UTF-8
    * @see [[utf8ByteEncoder]] for encoding
    * @see [[BigNat]] for size prefix format
    */
  given utf8ByteDecoder: ByteDecoder[Utf8] = bytes =>
    for
      sizeResult <- ByteDecoder[BigNat].decode(bytes)
      len = sizeResult.value.toBigInt.toLong
      remainder = sizeResult.remainder
      res <-
        if remainder.size >= len then
          val (front, back) = remainder.splitAt(len)
          front
            .decodeUtf8
            .leftMap(_ => DecodeFailure("Invalid UTF-8 bytes"))
            .map(u => DecodeResult[Utf8](u, back))
        else DecodeFailure("Insufficient bytes for Utf8 payload").asLeft[DecodeResult[Utf8]]
    yield res

  /** JSON encoder for Utf8.
    *
    * Encodes as a plain JSON string (delegates to String encoder).
    *
    * @return encoder instance
    *
    * @example
    * ```scala
    * val text = Utf8("Hello")
    * val json = JsonEncoder[Utf8].encode(text)  // JsonValue.Str("Hello")
    * ```
    *
    * @see [[utf8JsonDecoder]] for decoding
    */
  given utf8JsonEncoder: JsonEncoder[Utf8] =
    JsonEncoder[String].contramap(identity)

  /** JSON decoder for Utf8.
    *
    * Decodes from a JSON string (delegates to String decoder).
    *
    * @return decoder instance
    *
    * @example
    * ```scala
    * val json = JsonValue.Str("Hello")
    * val text = JsonDecoder[Utf8].decode(json)  // Right(Utf8("Hello"))
    * ```
    *
    * @see [[utf8JsonEncoder]] for encoding
    */
  given utf8JsonDecoder: JsonDecoder[Utf8] =
    JsonDecoder[String].map(Utf8(_))

  /** JSON key codec for using Utf8 as Map keys.
    *
    * Enables Utf8 values to be used as keys in JSON objects.
    *
    * @return key codec instance
    *
    * @example
    * ```scala
    * val map = Map(Utf8("key1") -> 42, Utf8("key2") -> 100)
    * val json = JsonEncoder[Map[Utf8, Int]].encode(map)
    * // { "key1": 42, "key2": 100 }
    * ```
    *
    * @see [[org.sigilaris.core.codec.json.JsonKeyCodec]] for key codec details
    */
  given utf8JsonKeyCodec: JsonKeyCodec[Utf8] =
    JsonKeyCodec[String].imap(Utf8(_), _.asString)
