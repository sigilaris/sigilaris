package org.sigilaris.core
package codec.json

import java.time.Instant
import java.time.temporal.ChronoUnit

trait JsonEncoder[A]:
  self =>
  def encode(value: A): JsonValue

  def contramap[B](f: B => A): JsonEncoder[B] = new JsonEncoder[B]:
    def encode(value: B): JsonValue =
      self.encode(f(value))

// Shared instance provider using an abstract config (top-level trait)
trait JsonEncoderInstances:
  protected def config: JsonConfig
  protected def mk[A](f: (A, JsonConfig) => JsonValue): JsonEncoder[A] =
    new JsonEncoder[A]:
      def encode(value: A): JsonValue = f(value, config)

  given booleanEncoder: JsonEncoder[Boolean] = mk((b, _) => JsonValue.JBool(b))
  given stringEncoder: JsonEncoder[String] = mk((s, _) => JsonValue.JString(s))
  given intEncoder: JsonEncoder[Int] =
    mk((n, _) => JsonValue.JNumber(BigDecimal(n)))
  given longEncoder: JsonEncoder[Long] =
    mk((n, _) => JsonValue.JNumber(BigDecimal(n)))
  given doubleEncoder: JsonEncoder[Double] =
    mk((n, _) => JsonValue.JNumber(BigDecimal(n)))

  given bigIntEncoder: JsonEncoder[BigInt] = mk: (n, cfg) =>
    if cfg.writeBigIntAsString then JsonValue.JString(n.toString)
    else JsonValue.JNumber(BigDecimal(n))

  given bigDecimalEncoder: JsonEncoder[BigDecimal] = mk: (n, cfg) =>
    if cfg.writeBigDecimalAsString then JsonValue.JString(n.toString)
    else JsonValue.JNumber(n)

  given instantEncoder: JsonEncoder[Instant] = mk: (i, _) =>
    JsonValue.JString(i.truncatedTo(ChronoUnit.MILLIS).toString)

  given optionEncoder[A: JsonEncoder]: JsonEncoder[Option[A]] = mk: (opt, _) =>
    opt.fold(JsonValue.JNull)(a => JsonEncoder[A].encode(a))

  given listEncoder[A: JsonEncoder]: JsonEncoder[List[A]] = mk: (xs, _) =>
    JsonValue.JArray(xs.iterator.map(a => JsonEncoder[A].encode(a)).toVector)

  given vectorEncoder[A: JsonEncoder]: JsonEncoder[Vector[A]] = mk: (xs, _) =>
    JsonValue.JArray(xs.iterator.map(a => JsonEncoder[A].encode(a)).toVector)

  /** Encode Map[K, V] by converting keys with JsonKeyCodec[K] to field names. */
  given mapEncoder[K, V](using JsonKeyCodec[K], JsonEncoder[V]): JsonEncoder[Map[K, V]] =
    mk: (m, cfg) =>
      val pairs = m.iterator
        .flatMap: (k, a) =>
          val field = JsonKeyCodec[K].encodeKey(k)
          val value = JsonEncoder[V].encode(a)
          value match
            case JsonValue.JNull if cfg.dropNullValues => None
            case _                                     => Some(field -> value)
        .toMap
      JsonValue.JObject(pairs)

object JsonEncoder extends JsonEncoderInstances:
  def apply[A: JsonEncoder]: JsonEncoder[A] = summon

  object ops:
    extension [A: JsonEncoder](a: A)
      def toJson: JsonValue = JsonEncoder[A].encode(a)

  protected val config: JsonConfig = JsonConfig.default

  object configured:
    final class Encoders(val config: JsonConfig) extends JsonEncoderInstances
    def apply(config: JsonConfig): Encoders = new Encoders(config)
