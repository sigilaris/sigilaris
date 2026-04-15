package org.sigilaris.core.assembly

import org.sigilaris.core.application.support.encoding.tablePrefix

import scodec.bits.ByteVector

/** Utility for validating prefix-free properties of encoded table prefixes.
  *
  * Prefix-free requirement (from ADR-0009):
  *   - All table prefixes must be distinct (no two tables share the same
  *     prefix)
  *   - No prefix may be a prefix of another (strict prefix-free property)
  *
  * This validator operates at the byte level after encoding with length-prefix
  * format: length(segment_bytes) ++ segment_bytes ++ 0x00
  */
@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.Equals",
    "org.wartremover.warts.DefaultArguments",
    "org.wartremover.warts.SizeIs",
    "org.wartremover.warts.Recursion",
  ),
)
object PrefixFreeValidator:

  /** Result of prefix-free validation, indicating whether a set of prefixes satisfies the prefix-free property. */
  sealed trait ValidationResult

  /** Indicates all prefixes are distinct and no prefix is a strict prefix of another. */
  case object Valid extends ValidationResult

  /** Indicates one encoded prefix is a strict prefix of another.
    *
    * @param prefix1
    *   the shorter prefix that is a prefix of `prefix2`
    * @param prefix2
    *   the longer prefix that starts with `prefix1`
    */
  final case class PrefixCollision(prefix1: ByteVector, prefix2: ByteVector)
      extends ValidationResult

  /** Indicates two or more table prefixes encode to identical byte vectors.
    *
    * @param prefix
    *   the duplicated byte vector
    * @param count
    *   the number of occurrences
    */
  final case class IdenticalPrefixes(prefix: ByteVector, count: Int)
      extends ValidationResult

  /** Checks whether byte vector `a` is a strict prefix of `b` (i.e. `a` is shorter and `b` starts with `a`).
    *
    * @param a
    *   the candidate prefix
    * @param b
    *   the byte vector to check against
    * @return
    *   true if `a` is a strict prefix of `b`
    */
  def isStrictPrefix(a: ByteVector, b: ByteVector): Boolean =
    a.length < b.length && b.take(a.length) == a

  /** Validates that all prefixes in the given list are prefix-free.
    *
    * @param prefixes
    *   the list of encoded table prefixes to validate
    * @return
    *   [[Valid]] if all prefixes are distinct and prefix-free,
    *   [[PrefixCollision]] if one prefix is a strict prefix of another,
    *   or [[IdenticalPrefixes]] if duplicates exist
    */
  def validate(prefixes: List[ByteVector]): ValidationResult =
    // Check for identical prefixes
    val grouped = prefixes.groupBy(identity)
    grouped.find(_._2.length > 1) match
      case Some((prefix, occurrences)) =>
        IdenticalPrefixes(prefix, occurrences.length)
      case None =>
        // Check for prefix relationships
        val collision = (for
          i <- prefixes.indices
          j <- prefixes.indices
          if i != j
        yield
          val a = prefixes(i)
          val b = prefixes(j)
          if isStrictPrefix(a, b) then Some(PrefixCollision(a, b))
          else None
        ).collectFirst { case Some(collision) => collision }

        collision.getOrElse(Valid)

  /** Validates prefixes with human-readable names for better error reporting.
    *
    * @param prefixes
    *   list of (name, encoded prefix) pairs; the names are used only for diagnostics
    * @return
    *   the [[ValidationResult]] for the underlying byte vectors
    */
  def validateWithNames(
      prefixes: List[(String, ByteVector)],
  ): ValidationResult =
    validate(prefixes.map(_._2))

  /** Checks whether a new prefix would collide (be identical to or a strict prefix of) any existing prefix.
    *
    * @param newPrefix
    *   the candidate prefix to test
    * @param existing
    *   the list of already-registered prefixes
    * @return
    *   true if `newPrefix` conflicts with any element in `existing`
    */
  def wouldCollide(newPrefix: ByteVector, existing: List[ByteVector]): Boolean =
    existing.exists: existingPrefix =>
      newPrefix == existingPrefix ||
        isStrictPrefix(newPrefix, existingPrefix) ||
        isStrictPrefix(existingPrefix, newPrefix)

  /** Formats a [[ValidationResult]] into a human-readable string for logging or test output.
    *
    * @param result
    *   the validation result to format
    * @return
    *   a multi-line string describing the outcome
    */
  def formatResult(result: ValidationResult): String = result match
    case Valid =>
      "✓ All prefixes are prefix-free"

    case PrefixCollision(p1, p2) =>
      s"""✗ Prefix collision detected:
  Prefix 1: ${p1.toHex}
  Prefix 2: ${p2.toHex}
  One is a prefix of the other"""

    case IdenticalPrefixes(prefix, count) =>
      s"""✗ Identical prefixes detected:
  Prefix: ${prefix.toHex}
  Occurrences: $count"""

  /** Collects encoded table prefixes from a schema tuple at compile time using inline expansion.
    *
    * @tparam Path
    *   the mount path tuple
    * @tparam Schema
    *   the schema tuple of [[org.sigilaris.core.application.state.Entry]] types
    * @param acc
    *   accumulator for collected prefixes (defaults to empty)
    * @return
    *   list of encoded byte-vector prefixes, one per entry in the schema
    */
  inline def collectSchemaPrefixes[Path <: Tuple, Schema <: Tuple](
      acc: List[ByteVector] = Nil,
  ): List[ByteVector] =
    import scala.compiletime.erasedValue

    inline erasedValue[Schema] match
      case _: EmptyTuple => acc
      case _: (org.sigilaris.core.application.state.Entry[name, k, v] *:
            tail) =>
        val prefix = tablePrefix[Path, name]
        collectSchemaPrefixes[Path, tail](prefix :: acc)

  /** Validates that the given schema produces prefix-free table prefixes when mounted at the given path.
    *
    * This is a runtime check that can be used in tests or during module assembly.
    *
    * @tparam Path
    *   the mount path tuple
    * @tparam Schema
    *   the schema tuple of [[org.sigilaris.core.application.state.Entry]] types
    * @return
    *   [[Valid]] if all entry prefixes are prefix-free, or an error result otherwise
    */
  inline def validateSchema[Path <: Tuple, Schema <: Tuple]: ValidationResult =
    val prefixes = collectSchemaPrefixes[Path, Schema]()
    validate(prefixes)

  /** Example demonstrating how to validate a composed schema at runtime. */
  def exampleValidation(): Unit =
    // This would typically be used in tests or module assembly
    type Path = ("app", "accounts")
    type Schema =
      org.sigilaris.core.application.state.Entry["balances", String, Int] *:
        org.sigilaris.core.application.state.Entry[
          "accounts",
          String,
          String,
        ] *: EmptyTuple

    val result = validateSchema[Path, Schema]
    println(formatResult(result))
