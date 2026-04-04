package org.sigilaris.core.application.scheduling

/** Lightweight derivation failure surface for the initial rollout.
  *
  * Later phases can widen `detail` if structured diagnostics become necessary.
  */
final case class FootprintDerivationFailure(
    reason: String,
    detail: Option[String],
)

object FootprintDerivationFailure:
  def withoutDetail(
      reason: String,
  ): FootprintDerivationFailure =
    FootprintDerivationFailure(reason = reason, detail = None)

trait FootprintDeriver[-A]:
  self =>

  def derive(
      value: A,
  ): Either[FootprintDerivationFailure, ConflictFootprint]

  def contramap[B](
      f: B => A,
  ): FootprintDeriver[B] =
    value => self.derive(f(value))

object FootprintDeriver:
  def apply[A](using
      deriver: FootprintDeriver[A],
  ): FootprintDeriver[A] =
    deriver

  def instance[A](
      f: A => Either[FootprintDerivationFailure, ConflictFootprint],
  ): FootprintDeriver[A] =
    value => f(value)

  def derive[A: FootprintDeriver](
      value: A,
  ): Either[FootprintDerivationFailure, ConflictFootprint] =
    FootprintDeriver[A].derive(value)
