package org.sigilaris.core
package codec.json

import scala.deriving.Mirror

import failure.DecodeFailure

/** Bidirectional JSON codec bundling a `JsonEncoder` and a `JsonDecoder`.
  *
  * Provides convenience `encode`/`decode` that delegate to the underlying
  * instances. When both encoder and decoder are available, a `given` codec is
  * derived automatically.
  */
final case class JsonCodec[A](encoder: JsonEncoder[A], decoder: JsonDecoder[A]):
  def encode(value: A): JsonValue = encoder.encode(value)
  def decode(json: JsonValue, config: JsonConfig): Either[DecodeFailure, A] =
    decoder.decode(json, config)

object JsonCodec:
  /** Builds a `JsonCodec` from explicit encoder and decoder. */
  def instance[A](enc: JsonEncoder[A], dec: JsonDecoder[A]): JsonCodec[A] =
    JsonCodec(enc, dec)

  /** Alias of `instance` for conciseness. */
  def of[A](enc: JsonEncoder[A], dec: JsonDecoder[A]): JsonCodec[A] =
    JsonCodec(enc, dec)

  /** Automatic JsonCodec when both encoder and decoder are available. */
  given [A: JsonEncoder: JsonDecoder]: JsonCodec[A] =
    JsonCodec(JsonEncoder[A], JsonDecoder[A])

  // Default instances are exposed directly via JsonEncoder/JsonDecoder objects

  /** Derived JsonCodec using Mirror, enabling `derives JsonCodec`. */
  inline def derived[A: Mirror.Of]: JsonCodec[A] =
    JsonCodec(
      JsonEncoder.derived[A],
      JsonDecoder.derived[A],
    )

  /** Build a bundle of codecs bound to a specific configuration, activated via
    * import.
    */
  object configured:
    /** Bundle of configured encoders/decoders that exports givens.
      * Import the constructed value to activate the instances.
      */
    final class Codecs(
        val enc: JsonEncoder.configured.Encoders,
        val dec: JsonDecoder.configured.Decoders,
    ):
      export enc.given
      export dec.given

    def apply(config: JsonConfig): Codecs =
      Codecs(JsonEncoder.configured(config), JsonDecoder.configured(config))
