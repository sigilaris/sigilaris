package org.sigilaris.core
package failure

import scala.util.control.NoStackTrace
import util.SafeStringInterp.*

/** Base failure type for Sigilaris operations.
  *
  * All failures carry a human-readable message and avoid stack traces for
  * performance and clarity in error reporting.
  */
sealed trait SigilarisFailure extends NoStackTrace:
  /** Human-readable failure message. */
  def msg: String

/** Decoding failed with a descriptive message (e.g., type mismatch, missing
  * field).
  *
  * @param msg
  *   the failure description
  */
final case class DecodeFailure(msg: String) extends SigilarisFailure

/** Parsing of external representation (e.g., JSON text) failed.
  *
  * @param msg
  *   the parse error description
  */
final case class ParseFailure(msg: String) extends SigilarisFailure

/** Merkle trie operation failed (e.g., node store access, structural issues).
  *
  * @param msg
  *   the trie operation error description
  */
final case class TrieFailure(msg: String) extends SigilarisFailure

/** Transaction routing failed (e.g., module not found in composed blueprint).
  *
  * @param msg
  *   the routing error description
  */
final case class RoutingFailure(msg: String) extends SigilarisFailure

// UInt256-specific failures must live in this file to satisfy the `sealed` restriction
sealed trait UInt256Failure extends SigilarisFailure

final case class UInt256TooLong(actualBytes: Long, maxBytes: Int)
    extends UInt256Failure:
  def msg: String =
    ss"Too long: ${actualBytes.toString} bytes > ${maxBytes.toString}"

case object UInt256NegativeValue extends UInt256Failure:
  val msg: String = "Negative value for UInt256"

final case class UInt256Overflow(detail: String) extends UInt256Failure:
  def msg: String = detail

final case class UInt256InvalidHex(detail: String) extends UInt256Failure:
  def msg: String = detail
