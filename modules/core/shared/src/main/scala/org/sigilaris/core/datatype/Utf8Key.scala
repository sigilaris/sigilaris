package org.sigilaris.core
package datatype

import java.nio.charset.StandardCharsets

import cats.Eq
import cats.syntax.either.*

import scodec.bits.ByteVector

import codec.byte.{ByteDecoder, ByteEncoder, ByteCodec, DecodeResult}
import codec.json.{JsonDecoder, JsonEncoder, JsonKeyCodec}
import codec.OrderedCodec
import failure.DecodeFailure

/** UTF-8 encoded string key with order-preserving byte representation.
  *
  * Utf8Key is an opaque type wrapping a String with specialized byte encoding that
  * preserves lexicographic ordering. Unlike [[Utf8]] which uses length-prefix encoding,
  * Utf8Key uses 0x00 terminator with escape sequences to ensure that string comparison
  * order matches byte comparison order.
  *
  * Key features:
  *   - Zero-cost abstraction over String
  *   - Order-preserving encoding: compare(x, y) ≡ encode(x).compare(encode(y))
  *   - 0x00 terminator with escape sequences (0x00 → 0x00 0xFF)
  *   - Suitable for table keys, range queries, and streaming
  *   - JSON codec delegates to String encoding/decoding
  *
  * Encoding format:
  *   - Regular bytes: copied as-is
  *   - 0x00 byte: escaped as 0x00 0xFF
  *   - Terminator: 0x00 (unescaped)
  *
  * @example
  * ```scala
  * import org.sigilaris.core.codec.byte.ByteEncoder
  *
  * val key1 = Utf8Key("abc")
  * val key2 = Utf8Key("b")
  *
  * // String comparison: "abc" < "b"
  * key1.asString < key2.asString  // true
  *
  * // Byte encoding preserves order
  * val bytes1 = ByteEncoder[Utf8Key].encode(key1)
  * val bytes2 = ByteEncoder[Utf8Key].encode(key2)
  * bytes1.compare(bytes2) < 0  // true (same sign as string comparison)
  * ```
  *
  * @note Use Utf8Key for table keys and range queries where ordering matters.
  *       Use [[Utf8]] for general value serialization where ordering is not required.
  * @note The encoding is NOT compatible with Utf8's length-prefix format.
  * @note Escape overhead: each 0x00 byte in input adds 1 extra byte (0xFF).
  *
  * @see [[Utf8]] for length-prefixed encoding (does not preserve order)
  * @see [[org.sigilaris.core.codec.OrderedCodec]] for ordering law details
  */
opaque type Utf8Key = String

object Utf8Key:
  /** Terminator byte marking end of encoded string. */
  private val Terminator: Byte = 0x00

  /** Escape byte following 0x00 in escaped sequences. */
  private val Escape: Byte = 0xFF.toByte

  /** Constructs a Utf8Key value from a String.
    *
    * @param value the string to wrap
    * @return Utf8Key instance
    *
    * @example
    * ```scala
    * val key = Utf8Key("table1")
    * val unicode = Utf8Key("키")
    * ```
    */
  def apply(value: String): Utf8Key = value

  /** Converts Utf8Key to String.
    *
    * @param u the Utf8Key value
    * @return the underlying String
    */
  extension (u: Utf8Key)
    inline def asString: String = u

  /** Converts Utf8Key to Utf8.
    *
    * @param u the Utf8Key value
    * @return Utf8 instance with same string value
    */
  extension (u: Utf8Key)
    def toUtf8: Utf8 = Utf8(u)

  /** Converts Utf8 to Utf8Key.
    *
    * @param u the Utf8 value
    * @return Utf8Key instance with same string value
    */
  def fromUtf8(u: Utf8): Utf8Key = u.asString

  /** Escapes 0x00 bytes in input by replacing each with 0x00 0xFF.
    *
    * This ensures that the terminator (0x00) can be distinguished from
    * actual 0x00 bytes in the string data.
    *
    * @param bytes the raw UTF-8 bytes
    * @return escaped bytes with 0x00 → 0x00 0xFF
    */
  private def escape(bytes: ByteVector): ByteVector =
    bytes.foldLeft(ByteVector.empty): (acc, b) =>
      if b == Terminator then acc ++ ByteVector(Terminator, Escape)
      else acc :+ b

  /** Unescapes 0x00 0xFF sequences back to 0x00 bytes.
    *
    * @param bytes the escaped bytes
    * @return unescaped bytes with 0x00 0xFF → 0x00
    */
  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  private def unescape(bytes: ByteVector): Either[DecodeFailure, ByteVector] =
    @scala.annotation.tailrec
    def loop(remaining: ByteVector, acc: ByteVector): Either[DecodeFailure, ByteVector] =
      if remaining.isEmpty then Right[DecodeFailure, ByteVector](acc)
      else if remaining.head == Terminator && remaining.tail.nonEmpty && remaining.tail.head == Escape then
        loop(remaining.drop(2), acc :+ Terminator)
      else if remaining.head == Terminator then
        Left[DecodeFailure, ByteVector](DecodeFailure("Invalid escape sequence: 0x00 not followed by 0xFF"))
      else
        loop(remaining.tail, acc :+ remaining.head)
    loop(bytes, ByteVector.empty)

  /** Cats Eq instance for Utf8Key using universal equality.
    *
    * @return Eq instance
    */
  given utf8KeyEq: Eq[Utf8Key] = Eq.fromUniversalEquals

  /** Byte encoder for Utf8Key.
    *
    * Encodes as order-preserving format: escaped UTF-8 bytes + 0x00 terminator.
    * The encoding ensures that lexicographic string order matches byte order.
    *
    * @return encoder instance
    * @see [[utf8KeyByteDecoder]] for decoding
    */
  given utf8KeyByteEncoder: ByteEncoder[Utf8Key] = (u: Utf8Key) =>
    val utf8Bytes = ByteVector.view(u.getBytes(StandardCharsets.UTF_8))
    escape(utf8Bytes) ++ ByteVector(Terminator)

  /** Byte decoder for Utf8Key.
    *
    * Decodes from order-preserving format: escaped UTF-8 bytes + 0x00 terminator.
    * Validates UTF-8 encoding and proper escape sequences.
    *
    * @return decoder instance
    * @note Fails with [[org.sigilaris.core.failure.DecodeFailure]] if:
    *       - No terminator found
    *       - Invalid escape sequence
    *       - Invalid UTF-8 bytes
    * @see [[utf8KeyByteEncoder]] for encoding
    */
  @SuppressWarnings(Array("org.wartremover.warts.Nothing"))
  given utf8KeyByteDecoder: ByteDecoder[Utf8Key] = bytes =>
    // Find terminator (unescaped 0x00)
    @scala.annotation.tailrec
    def findTerminator(pos: Long): Option[Long] =
      if pos >= bytes.length then None
      else if bytes(pos) == Terminator then
        // Check if this is escaped (followed by 0xFF)
        if pos + 1 < bytes.length && bytes(pos + 1) == Escape then
          findTerminator(pos + 2)  // Skip escaped 0x00 0xFF
        else
          Some(pos)  // Found unescaped terminator
      else
        findTerminator(pos + 1)

    findTerminator(0) match
      case None =>
        Left[DecodeFailure, DecodeResult[Utf8Key]](DecodeFailure("No terminator found in Utf8Key encoding"))
      case Some(idx) =>
        val encoded = bytes.take(idx)
        val remainder = bytes.drop(idx + 1)

        for
          unescaped <- unescape(encoded)
          str <- unescaped.decodeUtf8.leftMap(_ => DecodeFailure("Invalid UTF-8 bytes in Utf8Key"))
        yield DecodeResult[Utf8Key](str, remainder)

  /** JSON encoder for Utf8Key.
    *
    * Encodes as a plain JSON string (delegates to String encoder).
    *
    * @return encoder instance
    * @see [[utf8KeyJsonDecoder]] for decoding
    */
  given utf8KeyJsonEncoder: JsonEncoder[Utf8Key] =
    JsonEncoder[String].contramap(identity)

  /** JSON decoder for Utf8Key.
    *
    * Decodes from a JSON string (delegates to String decoder).
    *
    * @return decoder instance
    * @see [[utf8KeyJsonEncoder]] for encoding
    */
  given utf8KeyJsonDecoder: JsonDecoder[Utf8Key] =
    JsonDecoder[String].map(Utf8Key(_))

  /** JSON key codec for using Utf8Key as Map keys.
    *
    * Enables Utf8Key values to be used as keys in JSON objects.
    *
    * @return key codec instance
    * @see [[org.sigilaris.core.codec.json.JsonKeyCodec]] for key codec details
    */
  given utf8KeyJsonKeyCodec: JsonKeyCodec[Utf8Key] =
    JsonKeyCodec[String].imap(Utf8Key(_), _.asString)

  /** OrderedCodec instance for Utf8Key.
    *
    * Utf8Key's order-preserving encoding ensures that string lexicographic order
    * matches byte lexicographic order. This satisfies the OrderedCodec law:
    *   compare(x, y) ≡ encode(x).compare(encode(y))
    *
    * The encoding uses 0x00 terminator with escape sequences, ensuring that
    * "abc" < "b" in string comparison produces encode("abc") < encode("b") in
    * byte comparison.
    *
    * @return OrderedCodec instance
    * @see [[org.sigilaris.core.codec.OrderedCodec]] for law details
    */
  given utf8KeyOrderedCodec: OrderedCodec[Utf8Key] =
    val ord = new Ordering[Utf8Key]:
      def compare(x: Utf8Key, y: Utf8Key): Int = x.asString.compare(y.asString)
    OrderedCodec.fromCodecAndOrdering(ByteCodec[Utf8Key], ord)
