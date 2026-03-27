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

  /** Stable, machine-readable failure identifier. */
  def code: FailureCode = FailureCode.Unknown

/** Decoding failed with a descriptive message (e.g., type mismatch, missing
  * field).
  *
  * @param msg
  *   the failure description
  */
final case class DecodeFailure(msg: String) extends SigilarisFailure:
  override val code: FailureCode = FailureCode.Decode

/** Parsing of external representation (e.g., JSON text) failed.
  *
  * @param msg
  *   the parse error description
  */
final case class ParseFailure(msg: String) extends SigilarisFailure:
  override val code: FailureCode = FailureCode.Parse

/** Merkle trie operation failed (e.g., node store access, structural issues).
  *
  * @param msg
  *   the trie operation error description
  */
final case class TrieFailure(msg: String) extends SigilarisFailure:
  override val code: FailureCode = FailureCode.Trie

/** Transaction routing failed (e.g., module not found in composed blueprint).
  *
  * @param msg
  *   the routing error description
  */
final case class RoutingFailure(msg: String) extends SigilarisFailure:
  override val code: FailureCode = FailureCode.Routing

/** Cryptographic operation failed (e.g., signature verification, key recovery).
  *
  * @param msg
  *   the crypto operation error description
  */
final case class CryptoFailure(msg: String) extends SigilarisFailure:
  override val code: FailureCode = FailureCode.Crypto

// UInt256-specific failures must live in this file to satisfy the `sealed` restriction
sealed trait UInt256Failure extends SigilarisFailure

final case class UInt256TooLong(actualBytes: Long, maxBytes: Int)
    extends UInt256Failure:
  override val code: FailureCode = FailureCode.UInt256.TooLong

  def msg: String =
    ss"Too long: ${actualBytes.toString} bytes > ${maxBytes.toString}"

case object UInt256NegativeValue extends UInt256Failure:
  override val code: FailureCode = FailureCode.UInt256.NegativeValue
  val msg: String = "Negative value for UInt256"

final case class UInt256Overflow(detail: String) extends UInt256Failure:
  override val code: FailureCode = FailureCode.UInt256.Overflow
  def msg: String = detail

final case class UInt256InvalidHex(detail: String) extends UInt256Failure:
  override val code: FailureCode = FailureCode.UInt256.InvalidHex
  def msg: String = detail
