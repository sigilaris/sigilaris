package org.sigilaris.core
package codec.json

import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.util.SafeStringInterp.*
import cats.syntax.either.*
import java.util.UUID
import scala.util.Try

/** Typeclass for encoding/decoding map keys to/from JSON object field names.
  *
  * JSON objects require string keys. This typeclass bridges between typed Scala
  * map keys and JSON string field names.
  *
  * Base instances are provided for common types:
  *   - String (identity)
  *   - Numeric types (Int, Long, Double, BigInt, BigDecimal)
  *   - UUID
  *
  * @example
  * ```scala
  * val m = Map(1 -> "one", 2 -> "two")
  * val json = JsonEncoder[Map[Int, String]].encode(m)
  * // json: JObject(Map("1" -> JString("one"), "2" -> JString("two")))
  * ```
  *
  * @note Field naming policies from [[JsonConfig]] are NOT applied to map keys.
  *       Keys are encoded/decoded using only the [[JsonKeyCodec]] instance.
  *
  * @see [[JsonEncoder.mapEncoder]] and [[JsonDecoder.mapDecoder]]
  */
trait JsonKeyCodec[K]:
  self =>
  /** Encodes a key to a JSON object field name.
    *
    * @param key the key to encode
    * @return the field name
    */
  def encodeKey(key: K): String

  /** Decodes a JSON object field name to a key.
    *
    * @param key the field name
    * @return either a decode failure or the decoded key
    */
  def decodeKey(key: String): Either[DecodeFailure, K]

  /** Invariant mapping for key codecs.
    *
    * Creates a [[JsonKeyCodec]][L] from an existing [[JsonKeyCodec]][K] using
    * total conversions between K and L.
    *
    * @param to conversion from K to L
    * @param from conversion from L to K
    *
    * @example
    * ```scala
    * case class UserId(value: Int)
    * given JsonKeyCodec[UserId] = JsonKeyCodec[Int].imap(UserId(_), _.value)
    * ```
    */
  final def imap[L](to: K => L, from: L => K): JsonKeyCodec[L] = new JsonKeyCodec[L]:
    def encodeKey(key: L): String = self.encodeKey(from(key))
    def decodeKey(key: String): Either[DecodeFailure, L] = self.decodeKey(key).map(to)

  /** Narrows this codec with decode-time validation only.
    *
    * Creates a [[JsonKeyCodec]][L] from an existing [[JsonKeyCodec]][K] where
    * the forward transformation `to: K => Either[DecodeFailure, L]` may fail
    * (affecting decoding), while the backward transformation `from: L => K` is
    * total (used for encoding).
    *
    * Use this when you want to refine keys during decoding (e.g. constrain
    * numeric ranges or string formats) but can always project refined keys back
    * to the base representation for encoding.
    *
    * @param to
    *   conversion from K to L with validation (used on decode)
    * @param from
    *   total conversion from L to K (used on encode)
    *
    * @example
    * ```scala
    * // Refine Int keys to positive-only keys for decoding, while encoding via Int
    * final case class PositiveKey private (value: Int)
    * object PositiveKey:
    *   def fromInt(n: Int): Either[DecodeFailure, PositiveKey] =
    *     Either.cond(n > 0, PositiveKey(n), DecodeFailure(s"Not positive: $n"))
    *
    * given JsonKeyCodec[PositiveKey] =
    *   JsonKeyCodec[Int].narrow(PositiveKey.fromInt, _.value)
    *
    * // Map keys are validated on decode and rendered as their base Int on encode
    * val json = JsonEncoder[Map[PositiveKey, String]].encode(Map(PositiveKey(1) -> "ok"))
    * val roundtrip = JsonDecoder[Map[PositiveKey, String]].decode(json)
    * ```
    *
    * @note If you need total conversions in both directions, prefer [[imap]].
    *       If you need encode-time validation (L => Either[DecodeFailure, K]),
    *       design your key type so that `from` is total, and validate on `to`.
    */
  final def narrow[L](to: K => Either[DecodeFailure, L], from: L => K): JsonKeyCodec[L] =
    new JsonKeyCodec[L]:
      def encodeKey(key: L): String = self.encodeKey(from(key))
      def decodeKey(key: String): Either[DecodeFailure, L] =
        self.decodeKey(key).flatMap(to)

object JsonKeyCodec:
  /** Summons a key codec for the given type.
    *
    * @example
    * ```scala
    * val codec = JsonKeyCodec[Int]
    * ```
    */
  def apply[K](using ev: JsonKeyCodec[K]): JsonKeyCodec[K] = ev

  // Base instances
  /** Key codec for String (identity). */
  given stringKey: JsonKeyCodec[String] with
    def encodeKey(key: String): String = key
    def decodeKey(key: String): Either[DecodeFailure, String] = key.asRight[DecodeFailure]

  /** Key codec for UUID (toString / fromString). */
  given uuidKey: JsonKeyCodec[UUID] with
    def encodeKey(key: UUID): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, UUID] =
      Try(UUID.fromString(key)).toOption.toRight(DecodeFailure(ss"Invalid UUID key: ${key}"))

  /** Key codec for Int (toString / parse). */
  given intKey: JsonKeyCodec[Int] with
    def encodeKey(key: Int): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Int] =
      key.toIntOption.toRight(DecodeFailure(ss"Invalid Int key: ${key}"))

  /** Key codec for Long (toString / parse). */
  given longKey: JsonKeyCodec[Long] with
    def encodeKey(key: Long): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Long] =
      key.toLongOption.toRight(DecodeFailure(ss"Invalid Long key: ${key}"))

  /** Key codec for Double (toString / parse). */
  given doubleKey: JsonKeyCodec[Double] with
    def encodeKey(key: Double): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Double] =
      key.toDoubleOption.toRight(DecodeFailure(ss"Invalid Double key: ${key}"))

  /** Key codec for BigInt (toString / parse). */
  given bigIntKey: JsonKeyCodec[BigInt] with
    def encodeKey(key: BigInt): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, BigInt] =
      Try(BigInt(key)).toOption.toRight(DecodeFailure(ss"Invalid BigInt key: ${key}"))

  /** Key codec for BigDecimal (toString / parse). */
  given bigDecimalKey: JsonKeyCodec[BigDecimal] with
    def encodeKey(key: BigDecimal): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, BigDecimal] =
      Try(BigDecimal(key)).toOption.toRight(DecodeFailure(ss"Invalid BigDecimal key: ${key}"))


