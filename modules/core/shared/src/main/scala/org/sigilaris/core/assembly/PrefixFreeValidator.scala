package org.sigilaris.core.assembly

import org.sigilaris.core.application.support.encoding.tablePrefix

import scodec.bits.ByteVector

/** Utility for validating prefix-free properties of encoded table prefixes.
  *
  * Prefix-free requirement (from ADR-0009):
  * - All table prefixes must be distinct (no two tables share the same prefix)
  * - No prefix may be a prefix of another (strict prefix-free property)
  *
  * This validator operates at the byte level after encoding with length-prefix
  * format: length(segment_bytes) ++ segment_bytes ++ 0x00
  */
@SuppressWarnings(Array("org.wartremover.warts.Any", "org.wartremover.warts.Equals", "org.wartremover.warts.DefaultArguments", "org.wartremover.warts.SizeIs", "org.wartremover.warts.Recursion"))
object PrefixFreeValidator:

  /** Result of prefix-free validation. */
  sealed trait ValidationResult
  case object Valid                                                      extends ValidationResult
  final case class PrefixCollision(prefix1: ByteVector, prefix2: ByteVector) extends ValidationResult
  final case class IdenticalPrefixes(prefix: ByteVector, count: Int)         extends ValidationResult

  /** Checks if byte vector 'a' is a strict prefix of 'b' (a is shorter and matches). */
  def isStrictPrefix(a: ByteVector, b: ByteVector): Boolean =
    a.length < b.length && b.take(a.length) == a

  /** Validates that all prefixes are prefix-free.
    *
    * Returns:
    * - Valid if all prefixes are distinct and no prefix is a prefix of another
    * - PrefixCollision if one prefix is a prefix of another
    * - IdenticalPrefixes if two or more prefixes are identical
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

  /** Validates prefixes with human-readable names for better error reporting. */
  def validateWithNames(prefixes: List[(String, ByteVector)]): ValidationResult =
    validate(prefixes.map(_._2))

  /** Checks if a single prefix would collide with existing prefixes. */
  def wouldCollide(newPrefix: ByteVector, existing: List[ByteVector]): Boolean =
    existing.exists: existingPrefix =>
      newPrefix == existingPrefix ||
        isStrictPrefix(newPrefix, existingPrefix) ||
        isStrictPrefix(existingPrefix, newPrefix)

  /** Pretty-prints a validation result. */
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

  /** Helper to collect table prefixes from a schema tuple at compile time. */
  inline def collectSchemaPrefixes[Path <: Tuple, Schema <: Tuple](acc: List[ByteVector] = Nil): List[ByteVector] =
    import scala.compiletime.erasedValue

    inline erasedValue[Schema] match
      case _: EmptyTuple => acc
      case _: (org.sigilaris.core.application.state.Entry[name, k, v] *: tail) =>
        val prefix = tablePrefix[Path, name]
        collectSchemaPrefixes[Path, tail](prefix :: acc)

  /** Validates that the given schema (Entry tuples) produces prefix-free table prefixes
    * when mounted at the given path.
    *
    * This is a runtime check that can be used in tests or during module org.sigilaris.core.application.
    */
  inline def validateSchema[Path <: Tuple, Schema <: Tuple]: ValidationResult =
    val prefixes = collectSchemaPrefixes[Path, Schema]()
    validate(prefixes)

  /** Example usage showing how to validate a composed schema. */
  def exampleValidation(): Unit =
    // This would typically be used in tests or module assembly
    type Path   = ("app", "accounts")
    type Schema =
      org.sigilaris.core.application.state.Entry["balances", String, Int] *:
        org.sigilaris.core.application.state.Entry["accounts", String, String] *:
        EmptyTuple

    val result = validateSchema[Path, Schema]
    println(formatResult(result))
