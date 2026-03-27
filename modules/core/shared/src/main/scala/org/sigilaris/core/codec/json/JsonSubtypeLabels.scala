package org.sigilaris.core
package codec.json

/** Shared subtype-label contract for wrapped-by-type-key JSON coproducts.
  *
  * Sealed traits and enums both derive their canonical labels from Scala 3
  * Mirror labels. Discriminator configuration only projects those canonical
  * labels onto JSON keys; it does not introduce a second independent wire
  * format.
  */
final case class JsonSubtypeLabels private (
    canonical: Vector[String],
    encoded: Vector[String],
):
  def canonicalAt(ordinal: Int): String = canonical(ordinal)

  def labelAt(ordinal: Int): String = encoded(ordinal)

  def indexOfEncoded(label: String): Int = encoded.indexOf(label)

object JsonSubtypeLabels:
  def fromMirrorLabels(
      labels: List[String],
      discriminator: DiscriminatorConfig,
  ): JsonSubtypeLabels =
    val canonical = labels.iterator.map(canonicalLabel).toVector
    val encoded   = canonical.map(encodedLabel(_, discriminator))
    JsonSubtypeLabels(canonical, encoded)

  def canonicalLabel(label: String): String = label

  def encodedLabel(
      label: String,
      discriminator: DiscriminatorConfig,
  ): String =
    val canonical = canonicalLabel(label)
    discriminator.typeNameStrategy match
      case TypeNameStrategy.SimpleName     => canonical
      case TypeNameStrategy.FullyQualified => canonical
      case TypeNameStrategy.Custom(mapping) =>
        mapping.getOrElse(canonical, canonical)
