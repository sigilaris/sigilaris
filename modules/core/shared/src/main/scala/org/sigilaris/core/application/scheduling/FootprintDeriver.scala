package org.sigilaris.core.application.scheduling

import org.sigilaris.core.failure.{
  FailureDiagnosticFamily,
  StructuredFailureDiagnostic,
}

/** Lightweight derivation failure surface for the initial rollout.
  *
  * Later phases can widen `detail` if structured diagnostics become necessary.
  */
final case class FootprintDerivationFailure(
    reason: String,
    detail: Option[String],
) extends StructuredFailureDiagnostic:
  override val diagnosticFamily: FailureDiagnosticFamily =
    FailureDiagnosticFamily.FootprintDerivation

/** Companion for [[FootprintDerivationFailure]]. */
object FootprintDerivationFailure:
  /** Creates a failure with no detail message.
    *
    * @param reason the reason code
    * @return a FootprintDerivationFailure with detail = None
    */
  def withoutDetail(
      reason: String,
  ): FootprintDerivationFailure =
    FootprintDerivationFailure(reason = reason, detail = None)

/** Typeclass for deriving a conflict footprint from a value.
  *
  * Implementations map domain-specific transaction types to their
  * read/write state references for scheduling conflict detection.
  *
  * @tparam A the input type (contravariant)
  */
trait FootprintDeriver[-A]:
  self =>

  /** Derives the conflict footprint for the given value.
    *
    * @param value the value to derive a footprint from
    * @return either a derivation failure or the conflict footprint
    */
  def derive(
      value: A,
  ): Either[FootprintDerivationFailure, ConflictFootprint]

  /** Creates a new deriver that applies a function before deriving.
    *
    * @tparam B the new input type
    * @param f the function mapping B to A
    * @return a FootprintDeriver for type B
    */
  def contramap[B](
      f: B => A,
  ): FootprintDeriver[B] =
    value => self.derive(f(value))

/** Companion for [[FootprintDeriver]], providing summoning and factory methods. */
object FootprintDeriver:
  /** Summons the FootprintDeriver instance for type A.
    *
    * @tparam A the type with a deriver
    * @return the deriver instance
    */
  def apply[A](using
      deriver: FootprintDeriver[A],
  ): FootprintDeriver[A] =
    deriver

  /** Creates a FootprintDeriver from a function.
    *
    * @tparam A the input type
    * @param f the derivation function
    * @return a new FootprintDeriver instance
    */
  def instance[A](
      f: A => Either[FootprintDerivationFailure, ConflictFootprint],
  ): FootprintDeriver[A] =
    value => f(value)

  /** Derives a conflict footprint using the implicit deriver for A.
    *
    * @tparam A the type with a deriver
    * @param value the value to derive a footprint from
    * @return either a derivation failure or the conflict footprint
    */
  def derive[A: FootprintDeriver](
      value: A,
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    FootprintDeriver[A].derive(value)
