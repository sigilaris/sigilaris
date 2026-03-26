package org.sigilaris.core
package codec.json

import failure.DecodeFailure
import cats.syntax.either.*
import util.SafeStringInterp.*
import java.util.Locale
import java.time.Instant
import scala.deriving.Mirror
import scala.compiletime.{erasedValue, constValue, summonInline}

/** Type class for decoding the core [[JsonValue]] AST into Scala values.
  *
  * Decoders use [[JsonConfig]] to interpret field naming, discriminator strategy,
  * null/absent semantics, and big number representations.
  *
  * Combinators `map`/`emap` help build validated or dependent decoders;
  * `derived` provides automatic derivation for products and sums.
  *
  * @example
  * ```scala
  * case class User(name: String, age: Int) derives JsonDecoder
  * val json = JsonValue.obj("name" -> JString("Alice"), "age" -> JNumber(30))
  * val user = JsonDecoder[User].decode(json)
  * ```
  *
  * @note Decoding validates structure and types. Failures return [[org.sigilaris.core.failure.DecodeFailure]]
  *       with descriptive error messages.
  *
  * @see [[JsonEncoder]] for the inverse operation
  * @see [[JsonCodec]] for bidirectional encoding and decoding
  * @see [[JsonConfig]] for configuration options
  */
trait JsonDecoder[A]:
  self =>
  /** Decodes a JSON value to a Scala value.
    *
    * @param json the JSON value to decode
    * @return either a decode failure or the decoded value
    */
  def decode(json: JsonValue): Either[DecodeFailure, A]

  /** Creates a new decoder by applying a function after decoding.
    *
    * @param f the postprocessing function
    * @return a new decoder for type B
    *
    * @example
    * ```scala
    * val intDecoder: JsonDecoder[Int] = JsonDecoder[Int]
    * val evenDecoder = intDecoder.emap { n =>
    *   if (n % 2 == 0) Right(n) else Left(DecodeFailure("Not even"))
    * }
    * ```
    */
  def map[B](f: A => B): JsonDecoder[B] = new JsonDecoder[B]:
    def decode(json: JsonValue): Either[DecodeFailure, B] =
      self.decode(json).map(f)

  /** Creates a new decoder by applying a validation function after decoding.
    *
    * @param f the validation function
    * @return a new decoder for type B
    *
    * @example
    * ```scala
    * val positiveInt = JsonDecoder[Int].emap { n =>
    *   if (n > 0) Right(n) else Left(DecodeFailure("Must be positive"))
    * }
    * ```
    */
  def emap[B](f: A => Either[DecodeFailure, B]): JsonDecoder[B] =
    new JsonDecoder[B]:
      def decode(json: JsonValue): Either[DecodeFailure, B] =
        self.decode(json).flatMap(f)

// Shared instance provider using an abstract config (top-level trait)
trait JsonDecoderInstances:
  protected def config: JsonConfig

  protected def mk[A](
      f: (JsonValue, JsonConfig) => Either[DecodeFailure, A],
  ): JsonDecoder[A] = new JsonDecoder[A]:
    def decode(json: JsonValue): Either[DecodeFailure, A] =
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
    mk: (j, _) =>
      j match
        case JsonValue.JNull => success(None)
        case other           => JsonDecoder[A].decode(other).map(Some(_))

  given listDecoder[A: JsonDecoder]: JsonDecoder[List[A]] = mk: (j, _) =>
    j match
      case JsonValue.JArray(arr) =>
        arr
          .foldLeft(List.empty[A].asRight[DecodeFailure]): (acc, jv) =>
            acc.flatMap: list =>
              JsonDecoder[A].decode(jv).map(a => a :: list)
          .map(_.reverse)
      case other => typeMismatch[List[A]]("array", other)

  given vectorDecoder[A: JsonDecoder]: JsonDecoder[Vector[A]] =
    mk: (j, _) =>
      listDecoder[A].decode(j).map(_.toVector)

  /** Decode Map[K, V] by converting field names with JsonKeyCodec[K].
    *   - Fails on key parse errors
    *   - Fails on duplicate decoded keys after normalization
    */
  given mapDecoder[K: JsonKeyCodec, V: JsonDecoder]: JsonDecoder[Map[K, V]] =
    mk:
      case (JsonValue.JObject(fields), cfg) =>
        // Decode keys and values; detect duplicates after key normalization
        fields.foldLeft(Map.empty[K, V].asRight[DecodeFailure]):
          case (acc, (rawKey, jv)) =>
            for
              m     <- acc
              key   <- JsonKeyCodec[K].decodeKey(rawKey)
              value <- JsonDecoder[V].decode(jv)
              res <-
                if m.contains(key) then
                  DecodeFailure(
                    ss"Duplicate key after normalization: ${rawKey}",
                  ).asLeft[Map[K, V]]
                else m.updated(key, value).asRight[DecodeFailure]
            yield res
      case (other, _) => typeMismatch[Map[K, V]]("object", other)

  // --- Derivation helpers -------------------------------------------------
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
  private inline def decoders[T <: Tuple]: List[JsonDecoder[?]] =
    inline erasedValue[T] match
      case _: EmptyTuple => (Nil: List[JsonDecoder[?]])
      case _: (h *: t)   => summonInline[JsonDecoder[h]] :: decoders[t]

  private def applyNaming(name: String, p: FieldNamingPolicy): String =
    p match
      case FieldNamingPolicy.Identity => name
      case FieldNamingPolicy.CamelCase =>
        if name.isEmpty then name
        else ss"${name.head.toLower.toString}${name.tail}"
      case FieldNamingPolicy.SnakeCase =>
        name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT)
      case FieldNamingPolicy.KebabCase =>
        name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)

  // --- Derivation: Product -----------------------------------------------
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.AsInstanceOf",
      "org.wartremover.warts.Any",
    ),
  )
  inline given derivedProductDecoder[A](using
      m: Mirror.ProductOf[A],
  ): JsonDecoder[A] = mk:
    case (JsonValue.JObject(fields), cfg) =>
      val names = elemLabels[m.MirroredElemLabels].map: n =>
        applyNaming(n, cfg.fieldNaming)
      val decs = decoders[m.MirroredElemTypes]
      val bldr = new Array[Any](names.size)
      val res: Either[DecodeFailure, Unit] = (0 until names.size)
        .foldLeft[Either[DecodeFailure, Unit]](().asRight): (acc, idx) =>
          acc.flatMap: _ =>
            val n  = names(idx)
            val jd = decs(idx).asInstanceOf[JsonDecoder[Any]]
            val toDecodeEither: Either[DecodeFailure, JsonValue] =
              fields.get(n) match
                case Some(jv) => jv.asRight[DecodeFailure]
                case None =>
                  if cfg.treatAbsentAsNull then
                    JsonValue.JNull.asRight[DecodeFailure]
                  else DecodeFailure(ss"Missing field: ${n}").asLeft[JsonValue]
            toDecodeEither.flatMap: toDecode =>
              jd.decode(toDecode)
                .map: a =>
                  bldr(idx) = a
                  ()
      res.map: _ =>
        val tuple = Tuple.fromArray(bldr)
        m.fromProduct(tuple)
    case (other, _) => typeMismatch[A]("object", other)

  // --- Derivation: Sum (wrapped-by-type-key) -----------------------------
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.AsInstanceOf",
      "org.wartremover.warts.Any",
    ),
  )
  inline given derivedSumDecoder[A](using m: Mirror.SumOf[A]): JsonDecoder[A] =
    mk:
      case (JsonValue.JObject(fields), cfg) =>
        val labels = elemLabels[m.MirroredElemLabels]
        // find the single key and map to subtype without using == / size
        fields.toList match
          case (rawKey, body) :: Nil =>
            val idx = cfg.discriminator.typeNameStrategy match
              case TypeNameStrategy.SimpleName     => labels.indexOf(rawKey)
              case TypeNameStrategy.FullyQualified => labels.indexOf(rawKey)
              case TypeNameStrategy.Custom(mapping) =>
                val normalized = labels.map(l => mapping.getOrElse(l, l))
                normalized.indexOf(rawKey)
            if idx < 0 then DecodeFailure(ss"Unknown subtype: ${rawKey}").asLeft
            else
              val decs: List[JsonDecoder[?]] =
                inline erasedValue[m.MirroredElemTypes] match
                  case _: EmptyTuple => Nil
                  case _: (h *: t) =>
                    summonInline[JsonDecoder[h]] :: decoders[t]
              val dec = decs(idx).asInstanceOf[JsonDecoder[Any]]
              dec.decode(body).map(_.asInstanceOf[A])
          case _ =>
            DecodeFailure:
              "Expected single-key object for coproduct discriminator"
            .asLeft[A]
      case (other, _) => typeMismatch[A]("object", other)

object JsonDecoder extends JsonDecoderInstances:
  /** Summons a decoder instance for type A.
    *
    * ```scala
    * val stringDec = JsonDecoder[String]
    * ```
    */
  def apply[A: JsonDecoder]: JsonDecoder[A] = summon
  protected val config: JsonConfig          = JsonConfig.default

  /** Derives a decoder for type A using Scala 3 mirrors.
    *
    * Supports both product types (case classes) and sum types (sealed traits).
    * Enabled via `derives JsonDecoder` clause.
    *
    * ```scala
    * case class Point(x: Int, y: Int) derives JsonDecoder
    * sealed trait Color derives JsonDecoder
    * case object Red extends Color
    * case object Blue extends Color
    * ```
    */
  inline def derived[A](using m: Mirror.Of[A]): JsonDecoder[A] =
    inline m match
      case _: Mirror.ProductOf[A] => summonInline[JsonDecoder[A]]
      case _: Mirror.SumOf[A]     => summonInline[JsonDecoder[A]]

  /** Configuration-bound decoder bundles. */
  object configured:
    /** Factory for decoder bundles bound to a specific [[JsonConfig]].
      *
      * Use this to override default behavior like field naming or null handling.
      *
      * ```scala
      * val cfg = JsonConfig.default.copy(treatAbsentAsNull = false)
      * given JsonDecoder.configured.Decoders = JsonDecoder.configured(cfg)
      * ```
      */
    final class Decoders(val config: JsonConfig) extends JsonDecoderInstances
    def apply(config: JsonConfig): Decoders = new Decoders(config)
