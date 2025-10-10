package org.sigilaris.core.failure

import scala.util.control.NoStackTrace

/** Base failure type for Sigilaris operations.
  *
  * All failures carry a human-readable message and avoid stack traces for
  * performance and clarity in error reporting.
  */
sealed trait SigilarisFailure extends NoStackTrace:
  /** Human-readable failure message. */
  def msg: String

/** Decoding failed with a descriptive message (e.g., type mismatch, missing field).
  *
  * @param msg the failure description
  */
final case class DecodeFailure(msg: String) extends SigilarisFailure

/** Parsing of external representation (e.g., JSON text) failed.
  *
  * @param msg the parse error description
  */
final case class ParseFailure(msg: String) extends SigilarisFailure
