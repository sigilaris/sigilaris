package org.sigilaris.core
package codec.json

import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.core.util.SafeStringInterp.*
import cats.syntax.either.*
import java.util.UUID
import scala.util.Try

/** Typeclass for encoding/decoding map keys to/from JSON object field names. */
trait JsonKeyCodec[K]:
  self =>
  def encodeKey(key: K): String
  def decodeKey(key: String): Either[DecodeFailure, K]

  final def imap[L](to: K => L, from: L => K): JsonKeyCodec[L] = new JsonKeyCodec[L]:
    def encodeKey(key: L): String = self.encodeKey(from(key))
    def decodeKey(key: String): Either[DecodeFailure, L] = self.decodeKey(key).map(to)

object JsonKeyCodec:
  def apply[K](using ev: JsonKeyCodec[K]): JsonKeyCodec[K] = ev

  // Base instances
  given stringKey: JsonKeyCodec[String] with
    def encodeKey(key: String): String = key
    def decodeKey(key: String): Either[DecodeFailure, String] = key.asRight[DecodeFailure]

  given uuidKey: JsonKeyCodec[UUID] with
    def encodeKey(key: UUID): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, UUID] =
      Try(UUID.fromString(key)).toOption.toRight(DecodeFailure(ss"Invalid UUID key: ${key}"))

  given intKey: JsonKeyCodec[Int] with
    def encodeKey(key: Int): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Int] =
      key.toIntOption.toRight(DecodeFailure(ss"Invalid Int key: ${key}"))

  given longKey: JsonKeyCodec[Long] with
    def encodeKey(key: Long): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Long] =
      key.toLongOption.toRight(DecodeFailure(ss"Invalid Long key: ${key}"))

  given doubleKey: JsonKeyCodec[Double] with
    def encodeKey(key: Double): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, Double] =
      key.toDoubleOption.toRight(DecodeFailure(ss"Invalid Double key: ${key}"))

  given bigIntKey: JsonKeyCodec[BigInt] with
    def encodeKey(key: BigInt): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, BigInt] =
      Try(BigInt(key)).toOption.toRight(DecodeFailure(ss"Invalid BigInt key: ${key}"))

  given bigDecimalKey: JsonKeyCodec[BigDecimal] with
    def encodeKey(key: BigDecimal): String = key.toString
    def decodeKey(key: String): Either[DecodeFailure, BigDecimal] =
      Try(BigDecimal(key)).toOption.toRight(DecodeFailure(ss"Invalid BigDecimal key: ${key}"))


