package org.sigilaris.core
package codec.json

import scala.deriving.Mirror

import failure.DecodeFailure

/** Bidirectional JSON codec bundling a [[JsonEncoder]] and a [[JsonDecoder]].
  *
  * Provides convenience `encode`/`decode` that delegate to the underlying
  * instances. When both encoder and decoder are available, a `given` codec is
  * derived automatically.
  *
  * '''Example:'''
  * ```scala
  * case class User(name: String, age: Int) derives JsonCodec
  * val codec = JsonCodec[User]
  * val json = codec.encode(User("Alice", 30))
  * val user = codec.decode(json)
  * ```
  *
  * See also: [[JsonEncoder]] for encoding only, [[JsonDecoder]] for decoding only
  */
final case class JsonCodec[A](encoder: JsonEncoder[A], decoder: JsonDecoder[A]):
  /** Encodes a value using the bundled encoder.
    *
    * @param value the value to encode
    * @return the JSON representation
    */
  def encode(value: A): JsonValue = encoder.encode(value)

  /** Decodes a JSON value using the bundled decoder.
    *
    * @param json the JSON value to decode
    * @return either a decode failure or the decoded value
    */
  def decode(json: JsonValue): Either[DecodeFailure, A] =
    decoder.decode(json)

object JsonCodec:
  /** Builds a [[JsonCodec]] from explicit encoder and decoder.
    *
    * ```scala
    * val codec = JsonCodec.instance(myEncoder, myDecoder)
    * ```
    */
  def instance[A](enc: JsonEncoder[A], dec: JsonDecoder[A]): JsonCodec[A] =
    JsonCodec(enc, dec)

  /** Alias of `instance` for conciseness.
    *
    * ```scala
    * val codec = JsonCodec.of(myEncoder, myDecoder)
    * ```
    */
  def of[A](enc: JsonEncoder[A], dec: JsonDecoder[A]): JsonCodec[A] =
    JsonCodec(enc, dec)

  /** Automatic JsonCodec when both encoder and decoder are available.
    *
    * This given instance allows implicit summoning of a codec when both
    * an encoder and decoder are in scope.
    *
    * ```scala
    * given JsonEncoder[MyType] = ...
    * given JsonDecoder[MyType] = ...
    * val codec = summon[JsonCodec[MyType]]
    * ```
    */
  given [A: JsonEncoder: JsonDecoder]: JsonCodec[A] =
    JsonCodec(JsonEncoder[A], JsonDecoder[A])

  // Default instances are exposed directly via JsonEncoder/JsonDecoder objects

  /** Derived JsonCodec using Mirror, enabling `derives JsonCodec`.
    *
    * Supports both product types (case classes) and sum types (sealed traits).
    *
    * ```scala
    * case class Point(x: Int, y: Int) derives JsonCodec
    * sealed trait Shape derives JsonCodec
    * case class Circle(radius: Double) extends Shape
    * case class Rectangle(width: Double, height: Double) extends Shape
    * ```
    */
  inline def derived[A: Mirror.Of]: JsonCodec[A] =
    JsonCodec(
      JsonEncoder.derived[A],
      JsonDecoder.derived[A],
    )

  /** Configuration-bound codec bundles. */
  object configured:
    /** Bundle of configured encoders/decoders that exports givens.
      *
      * Import the constructed value to activate the instances.
      *
      * ```scala
      * val cfg = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
      * object MyCodecs extends JsonCodec.configured.Codecs(
      *   JsonEncoder.configured(cfg),
      *   JsonDecoder.configured(cfg)
      * )
      * import MyCodecs.given
      * ```
      */
    final class Codecs(
        val enc: JsonEncoder.configured.Encoders,
        val dec: JsonDecoder.configured.Decoders,
    ):
      export enc.given
      export dec.given

    /** Creates a codec bundle with a specific configuration.
      *
      * ```scala
      * val cfg = JsonConfig.default.copy(dropNullValues = false)
      * val codecs = JsonCodec.configured(cfg)
      * import codecs.given
      * ```
      */
    def apply(config: JsonConfig): Codecs =
      Codecs(JsonEncoder.configured(config), JsonDecoder.configured(config))
