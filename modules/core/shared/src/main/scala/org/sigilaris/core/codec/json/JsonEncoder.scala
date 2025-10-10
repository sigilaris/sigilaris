package org.sigilaris.core
package codec.json

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

import scala.compiletime.{constValue, erasedValue, summonInline}
import scala.deriving.Mirror

import util.SafeStringInterp.*

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

  // --- Helpers ------------------------------------------------------------
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Recursion",
      "org.wartremover.warts.AsInstanceOf",
    ),
  )
  private inline def elemLabels[L <: Tuple]: List[String] =
    inline erasedValue[L] match
      case _: EmptyTuple => (Nil: List[String])
      case _: (h *: t)   => constValue[h].asInstanceOf[String] :: elemLabels[t]

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  private inline def encoders[T <: Tuple]: List[JsonEncoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => (Nil: List[JsonEncoder[?]])
      case _: (h *: t)   => summonInline[JsonEncoder[h]] :: encoders[t]

  private def applyNaming(name: String, p: FieldNamingPolicy): String =
    p match
      case FieldNamingPolicy.Identity => name
      case FieldNamingPolicy.CamelCase =>
        if name.isEmpty then name else ss"${name.head.toLower.toString}${name.tail}"
      case FieldNamingPolicy.SnakeCase =>
        name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
      case FieldNamingPolicy.KebabCase =>
        name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)

  private def typeKeyFor(label: String, d: DiscriminatorConfig): String =
    d.typeNameStrategy match
      case TypeNameStrategy.SimpleName => label
      case TypeNameStrategy.FullyQualified =>
        label // fallback to simple; FQ not available from Mirror label
      case TypeNameStrategy.Custom(mapping) => mapping.getOrElse(label, label)

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

  /** Encode Map[K, V] by converting keys with JsonKeyCodec[K] to field names.
    */
  given mapEncoder[K, V](using
      JsonKeyCodec[K],
      JsonEncoder[V],
  ): JsonEncoder[Map[K, V]] =
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

  // --- Derivation: Product -----------------------------------------------
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  inline given derivedProductEncoder[A](using
      m: Mirror.ProductOf[A],
  ): JsonEncoder[A] =
    mk: (a, cfg) =>
      val names = elemLabels[m.MirroredElemLabels].map(n =>
        applyNaming(n, cfg.fieldNaming),
      )
      val encs            = encoders[m.MirroredElemTypes]
      val values: Product = a.asInstanceOf[Product]
      val fields = names.iterator.zipWithIndex.flatMap: (n, i) =>
        val enc = encs(i).asInstanceOf[JsonEncoder[Any]]
        val jv  = enc.encode(values.productElement(i))
        jv match
          case JsonValue.JNull if cfg.dropNullValues => None
          case _                                     => Some(n -> jv)
      JsonValue.JObject(fields.toMap)

  // --- Derivation: Sum (wrapped-by-type-key) -----------------------------
  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  inline given derivedSumEncoder[A](using m: Mirror.SumOf[A]): JsonEncoder[A] =
    mk: (a, cfg) =>
      val labels = elemLabels[m.MirroredElemLabels]
      val ord    = m.ordinal(a)
      val key    = typeKeyFor(labels(ord), cfg.discriminator)
      // summon encoder for the active subtype and encode
      val encs: List[JsonEncoder[?]] =
        inline erasedValue[m.MirroredElemTypes] match
          case _: EmptyTuple => Nil
          case _: (h *: t)   => summonInline[JsonEncoder[h]] :: encoders[t]
      val enc  = encs(ord).asInstanceOf[JsonEncoder[Any]]
      val body = enc.encode(a)
      JsonValue.JObject(Map(key -> body))

object JsonEncoder extends JsonEncoderInstances:
  def apply[A: JsonEncoder]: JsonEncoder[A] = summon

  object ops:
    extension [A: JsonEncoder](a: A)
      def toJson: JsonValue = JsonEncoder[A].encode(a)

  inline def derived[A](using m: Mirror.Of[A]): JsonEncoder[A] =
    inline m match
      case _: Mirror.ProductOf[A] => summonInline[JsonEncoder[A]]
      case _: Mirror.SumOf[A]     => summonInline[JsonEncoder[A]]

  protected val config: JsonConfig = JsonConfig.default

  object configured:
    final class Encoders(val config: JsonConfig) extends JsonEncoderInstances
    def apply(config: JsonConfig): Encoders = new Encoders(config)
