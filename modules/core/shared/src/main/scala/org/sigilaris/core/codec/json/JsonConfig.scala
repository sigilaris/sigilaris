package org.sigilaris.core
package codec.json

/** Field naming policies applied during product derivation. */
enum FieldNamingPolicy:
  case Identity, SnakeCase, KebabCase, CamelCase

/** Strategy for representing subtype names in coproduct discriminator. */
enum TypeNameStrategy:
  case SimpleName, FullyQualified
  case Custom(mapping: Map[String, String])

/** Discriminator configuration for coproducts (wrapped-by-type-key form). */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class DiscriminatorConfig(
    typeNameStrategy: TypeNameStrategy,
)

/** JSON configuration affecting encode/decode behaviors. */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class JsonConfig(
    fieldNaming: FieldNamingPolicy,
    dropNullValues: Boolean,
    treatAbsentAsNull: Boolean,
    writeBigIntAsString: Boolean,
    writeBigDecimalAsString: Boolean,
    discriminator: DiscriminatorConfig,
)

object JsonConfig:
  /** Default configuration aligning with project guidelines. */
  val default: JsonConfig = JsonConfig(
    fieldNaming = FieldNamingPolicy.Identity,
    dropNullValues = true,
    treatAbsentAsNull = true,
    writeBigIntAsString = true,
    writeBigDecimalAsString = true,
    discriminator = DiscriminatorConfig(TypeNameStrategy.SimpleName),
  )

  /** Implicit default for convenience; override in scope to customize. */
  given defaultJsonConfig: JsonConfig = default

object DiscriminatorConfig:
  val default: DiscriminatorConfig = DiscriminatorConfig(TypeNameStrategy.SimpleName)


