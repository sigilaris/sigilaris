package org.sigilaris.core
package codec.json

/** Shared subtype-label mapping for wrapped-by-type-key JSON coproducts.
  *
  * Sealed traits and enums both derive their canonical labels from Scala 3
  * Mirror labels. Discriminator configuration only projects those canonical
  * labels onto JSON keys; it does not introduce a second independent wire
  * format.
  *
  * @param canonical
  *   the canonical Mirror labels for each subtype
  * @param encoded
  *   the projected JSON keys for each subtype
  */
final case class JsonSubtypeLabels private (
    canonical: Vector[String],
    encoded: Vector[String],
):
  /** Returns the canonical Mirror label at the given ordinal.
    *
    * @param ordinal
    *   the subtype ordinal
    * @return
    *   the canonical label
    */
  def canonicalAt(ordinal: Int): String = canonical(ordinal)

  /** Returns the encoded JSON key at the given ordinal.
    *
    * @param ordinal
    *   the subtype ordinal
    * @return
    *   the encoded label used in JSON
    */
  def labelAt(ordinal: Int): String = encoded(ordinal)

  /** Finds the ordinal index of a given encoded label.
    *
    * @param label
    *   the encoded JSON key to look up
    * @return
    *   the ordinal index, or -1 if not found
    */
  def indexOfEncoded(label: String): Int = encoded.indexOf(label)

/** Companion object for [[JsonSubtypeLabels]]. */
object JsonSubtypeLabels:
  /** Constructs a [[JsonSubtypeLabels]] from Mirror labels and discriminator config.
    *
    * @param labels
    *   the raw Mirror element labels
    * @param discriminator
    *   the discriminator configuration controlling label projection
    * @return
    *   the constructed subtype labels
    */
  def fromMirrorLabels(
      labels: List[String],
      discriminator: DiscriminatorConfig,
  ): JsonSubtypeLabels =
    val canonical = labels.iterator.map(canonicalLabel).toVector
    val encoded   = canonical.map(encodedLabel(_, discriminator))
    JsonSubtypeLabels(canonical, encoded)

  /** Returns the canonical form of a Mirror label (identity in the current implementation).
    *
    * @param label
    *   the raw Mirror label
    * @return
    *   the canonical label
    */
  def canonicalLabel(label: String): String = label

  /** Projects a canonical label to its JSON key using the discriminator config.
    *
    * @param label
    *   the canonical label
    * @param discriminator
    *   the discriminator configuration
    * @return
    *   the encoded JSON key
    */
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
