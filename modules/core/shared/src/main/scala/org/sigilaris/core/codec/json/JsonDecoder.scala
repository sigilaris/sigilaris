package org.sigilaris.core
package codec.json

import org.sigilaris.core.failure.DecodeFailure
import cats.syntax.either.*
import util.SafeStringInterp.*
import java.time.Instant

trait JsonDecoder[A]:
  self =>
  def decode(json: JsonValue, config: JsonConfig): Either[DecodeFailure, A]

  def map[B](f: A => B): JsonDecoder[B] = new JsonDecoder[B]:
    def decode(json: JsonValue, config: JsonConfig): Either[DecodeFailure, B] =
      self.decode(json, config).map(f)

  def emap[B](f: A => Either[DecodeFailure, B]): JsonDecoder[B] =
    new JsonDecoder[B]:
      def decode(
          json: JsonValue,
          config: JsonConfig,
      ): Either[DecodeFailure, B] =
        self.decode(json, config).flatMap(f)

// Shared instance provider using an abstract config (top-level trait)
trait JsonDecoderInstances:
  protected def config: JsonConfig

  protected def mk[A](
      f: (JsonValue, JsonConfig) => Either[DecodeFailure, A],
  ): JsonDecoder[A] = new JsonDecoder[A]:
    def decode(json: JsonValue, c: JsonConfig): Either[DecodeFailure, A] =
      f(json, config)

  private inline def success[A](a: A): Either[DecodeFailure, A] =
    a.asRight[DecodeFailure]

  private inline def failure[A](msg: String): Either[DecodeFailure, A] =
    DecodeFailure(msg).asLeft[A]

  protected def typeMismatch[A](
      expected: String,
      got: JsonValue,
  ): Either[DecodeFailure, A] =
    DecodeFailure(ss"Expected ${expected}, got ${got.getClass.getSimpleName}")
      .asLeft[A]

  given booleanDecoder: JsonDecoder[Boolean] = mk: (j, _) =>
    j match
      case JsonValue.JBool(b) => success(b)
      case other              => typeMismatch[Boolean]("boolean", other)

  given stringDecoder: JsonDecoder[String] = mk: (j, _) =>
    j match
      case JsonValue.JString(s) => success(s)
      case other                => typeMismatch[String]("string", other)

  given intDecoder: JsonDecoder[Int] = mk: (j, _) =>
    j match
      case JsonValue.JNumber(n) => success(n.toInt)
      case other                => typeMismatch[Int]("number", other)

  given longDecoder: JsonDecoder[Long] = mk: (j, _) =>
    j match
      case JsonValue.JNumber(n) => success(n.toLong)
      case other                => typeMismatch[Long]("number", other)

  given doubleDecoder: JsonDecoder[Double] = mk: (j, _) =>
    j match
      case JsonValue.JNumber(n) => success(n.toDouble)
      case other                => typeMismatch[Double]("number", other)

  given bigIntDecoder: JsonDecoder[BigInt] = mk: (j, cfg) =>
    j match
      case JsonValue.JString(s) if cfg.writeBigIntAsString => success(BigInt(s))
      case JsonValue.JNumber(n) => success(n.toBigInt)
      case other => typeMismatch[BigInt]("bigint (string or number)", other)

  given bigDecimalDecoder: JsonDecoder[BigDecimal] = mk: (j, cfg) =>
    j match
      case JsonValue.JString(s) if cfg.writeBigDecimalAsString =>
        success(BigDecimal(s))
      case JsonValue.JNumber(n) => success(n)
      case other =>
        typeMismatch[BigDecimal]("bigdecimal (string or number)", other)

  given instantDecoder: JsonDecoder[Instant] = mk: (j, _) =>
    j match
      case JsonValue.JString(s) => success(Instant.parse(s))
      case other => typeMismatch[Instant]("instant (ISO-8601 string)", other)

  given optionDecoder[A: JsonDecoder]: JsonDecoder[Option[A]] =
    mk: (j, cfg) =>
      j match
        case JsonValue.JNull => success(None)
        case other           => JsonDecoder[A].decode(other, cfg).map(Some(_))

  given listDecoder[A: JsonDecoder]: JsonDecoder[List[A]] = mk:
    (j, cfg) =>
      j match
        case JsonValue.JArray(arr) =>
          arr
            .foldLeft(List.empty[A].asRight[DecodeFailure]): (acc, jv) =>
              acc.flatMap(list => JsonDecoder[A].decode(jv, cfg).map(a => a :: list))
            .map(_.reverse)
        case other => typeMismatch[List[A]]("array", other)

  given vectorDecoder[A: JsonDecoder]: JsonDecoder[Vector[A]] =
    mk: (j, cfg) =>
      listDecoder[A].decode(j, cfg).map(_.toVector)

  /** Decode Map[K, V] by converting field names with JsonKeyCodec[K].
    * - Fails on key parse errors
    * - Fails on duplicate decoded keys after normalization
    */
  given mapDecoder[K, V](using JsonKeyCodec[K], JsonDecoder[V]): JsonDecoder[Map[K, V]] =
    mk:
      case (JsonValue.JObject(fields), cfg) =>
        // Decode keys and values; detect duplicates after key normalization
        fields.foldLeft(Map.empty[K, V].asRight[DecodeFailure]):
          case (acc, (rawKey, jv)) =>
            for
              m     <- acc
              key   <- JsonKeyCodec[K].decodeKey(rawKey)
              value <- JsonDecoder[V].decode(jv, cfg)
              res   <-
                if m.contains(key) then DecodeFailure(ss"Duplicate key after normalization: ${rawKey}").asLeft[Map[K, V]]
                else m.updated(key, value).asRight[DecodeFailure]
            yield res
      case (other, _) => typeMismatch[Map[K, V]]("object", other)

object JsonDecoder extends JsonDecoderInstances:
  def apply[A: JsonDecoder]: JsonDecoder[A] = summon
  protected val config: JsonConfig          = JsonConfig.default

  object configured:
    final class Decoders(val config: JsonConfig) extends JsonDecoderInstances
    def apply(config: JsonConfig): Decoders = new Decoders(config)
