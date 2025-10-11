package org.sigilaris.core
package codec.json

/** Field naming policies applied during product derivation.
  *
  * Controls how Scala field names are transformed in JSON encoding/decoding.
  *
  * Available policies:
  *   - `Identity` - Keep field names unchanged
  *   - `SnakeCase` - Convert to snake_case
  *   - `KebabCase` - Convert to kebab-case
  *   - `CamelCase` - Convert to camelCase
  *
  * @note Naming policies are applied bidirectionally to both encoding and decoding.
  */
enum FieldNamingPolicy:
  /** Keep field names as-is. */
  case Identity

  /** Convert to snake_case (e.g., `firstName` → `first_name`). */
  case SnakeCase

  /** Convert to kebab-case (e.g., `firstName` → `first-name`). */
  case KebabCase

  /** Convert to camelCase (e.g., `FirstName` → `firstName`). */
  case CamelCase

/** Strategy for representing subtype names in coproduct discriminator.
  *
  * Used in the wrapped-by-type-key encoding: `{ "SubtypeName": { ... } }`.
  */
enum TypeNameStrategy:
  /** Use simple class name (e.g., `Red` for `sealed trait Color`). */
  case SimpleName

  /** Use fully qualified name (e.g., `com.example.Color.Red`).
    *
    * ''Note:'' Currently falls back to simple name; full qualification
    * is not available from Scala 3 Mirror labels.
    */
  case FullyQualified

  /** Use custom mapping for subtype names.
    *
    * @param mapping map from simple name to desired JSON key
    *
    * @example
    * ```scala
    * Custom(Map("Red" -> "red", "Blue" -> "blue"))
    * ```
    */
  case Custom(mapping: Map[String, String])

/** Discriminator configuration for coproducts (wrapped-by-type-key form).
  *
  * @param typeNameStrategy how to derive the type key for each subtype
  *
  * @example
  * ```scala
  * val config = DiscriminatorConfig(TypeNameStrategy.SimpleName)
  * // Encodes: sealed trait Color; case object Red extends Color
  * //   → { "Red": {} }
  * ```
  */
@SuppressWarnings(Array("org.wartremover.warts.DefaultArguments"))
final case class DiscriminatorConfig(
    typeNameStrategy: TypeNameStrategy,
)

/** JSON configuration affecting encode/decode behaviors.
  *
  * Controls all aspects of JSON encoding and decoding, including field naming,
  * null handling, number formatting, and discriminator strategy.
  *
  * @param fieldNaming how to transform field names
  * @param dropNullValues if true, null values are omitted from encoded objects
  * @param treatAbsentAsNull if true, missing fields decode as null (for Option)
  * @param writeBigIntAsString if true, BigInt is encoded as JSON string
  * @param writeBigDecimalAsString if true, BigDecimal is encoded as JSON string
  * @param discriminator configuration for coproduct type discrimination
  *
  * @example
  * ```scala
  * val customConfig = JsonConfig.default.copy(
  *   fieldNaming = FieldNamingPolicy.SnakeCase,
  *   dropNullValues = false
  * )
  * ```
  */
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
  /** Default configuration aligning with project guidelines.
    *
    * Defaults:
    * - Field names unchanged (Identity)
    * - Null values dropped in encoding
    * - Absent fields treated as null in decoding
    * - BigInt/BigDecimal encoded as strings
    * - Simple subtype names for discriminator
    */
  val default: JsonConfig = JsonConfig(
    fieldNaming = FieldNamingPolicy.Identity,
    dropNullValues = true,
    treatAbsentAsNull = true,
    writeBigIntAsString = true,
    writeBigDecimalAsString = true,
    discriminator = DiscriminatorConfig(TypeNameStrategy.SimpleName),
  )

  /** Implicit default for convenience; override in scope to customize.
    *
    * @example
    * ```scala
    * given JsonConfig = JsonConfig.default.copy(fieldNaming = FieldNamingPolicy.SnakeCase)
    * ```
    */
  given defaultJsonConfig: JsonConfig = default

object DiscriminatorConfig:
  /** Default discriminator configuration using simple type names. */
  val default: DiscriminatorConfig = DiscriminatorConfig(TypeNameStrategy.SimpleName)


