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

  /** Closed, machine-readable failure family. */
  def kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Unknown

  /** Stable, machine-readable failure identifier. */
  def code: FailureCode = kind.code

object SigilarisFailure:
  /** Closed family for the baseline core failure hierarchy. */
  enum Kind(val code: FailureCode):
    case Unknown extends Kind(FailureCode.Unknown)
    case Decode extends Kind(FailureCode.Decode)
    case Parse extends Kind(FailureCode.Parse)
    case Trie extends Kind(FailureCode.Trie)
    case Routing extends Kind(FailureCode.Routing)
    case Crypto extends Kind(FailureCode.Crypto)
    case UInt256TooLong extends Kind(FailureCode.UInt256.TooLong)
    case UInt256NegativeValue extends Kind(FailureCode.UInt256.NegativeValue)
    case UInt256Overflow extends Kind(FailureCode.UInt256.Overflow)
    case UInt256InvalidHex extends Kind(FailureCode.UInt256.InvalidHex)

/** Decoding failed with a descriptive message (e.g., type mismatch, missing
  * field).
  *
  * @param msg
  *   the failure description
  */
final case class DecodeFailure(msg: String) extends SigilarisFailure:
  override val kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Decode

/** Parsing of external representation (e.g., JSON text) failed.
  *
  * @param msg
  *   the parse error description
  */
final case class ParseFailure(msg: String) extends SigilarisFailure:
  override val kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Parse

/** Merkle trie operation failed (e.g., node store access, structural issues).
  *
  * @param msg
  *   the trie operation error description
  */
final case class TrieFailure(msg: String) extends SigilarisFailure:
  override val kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Trie

/** Transaction routing failed (e.g., module not found in composed blueprint).
  *
  * @param msg
  *   the routing error description
  */
final case class RoutingFailure(msg: String) extends SigilarisFailure:
  override val kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Routing

/** Cryptographic operation failed (e.g., signature verification, key recovery).
  *
  * @param msg
  *   the crypto operation error description
  */
final case class CryptoFailure(msg: String) extends SigilarisFailure:
  override val kind: SigilarisFailure.Kind = SigilarisFailure.Kind.Crypto

/** Sealed family of failures specific to UInt256 operations.
  *
  * Must live in this file alongside [[SigilarisFailure]] to satisfy the
  * `sealed` restriction.
  */
sealed trait UInt256Failure extends SigilarisFailure

/** Input byte sequence exceeds the maximum allowed length for UInt256.
  *
  * @param actualBytes
  *   the actual number of bytes provided
  * @param maxBytes
  *   the maximum allowed number of bytes (32)
  */
final case class UInt256TooLong(actualBytes: Long, maxBytes: Int)
    extends UInt256Failure:
  override val kind: SigilarisFailure.Kind =
    SigilarisFailure.Kind.UInt256TooLong

  def msg: String =
    ss"Too long: ${actualBytes.toString} bytes > ${maxBytes.toString}"

/** The input value is negative, which is invalid for an unsigned 256-bit integer. */
case object UInt256NegativeValue extends UInt256Failure:
  override val kind: SigilarisFailure.Kind =
    SigilarisFailure.Kind.UInt256NegativeValue
  val msg: String                = "Negative value for UInt256"

/** The input value exceeds the 2^256 - 1 upper bound.
  *
  * @param detail
  *   description of the overflow condition
  */
final case class UInt256Overflow(detail: String) extends UInt256Failure:
  override val kind: SigilarisFailure.Kind =
    SigilarisFailure.Kind.UInt256Overflow
  def msg: String                = detail

/** The input hex string contains characters that are not valid hexadecimal digits.
  *
  * @param detail
  *   description of the hex parsing error
  */
final case class UInt256InvalidHex(detail: String) extends UInt256Failure:
  override val kind: SigilarisFailure.Kind =
    SigilarisFailure.Kind.UInt256InvalidHex
  def msg: String                = detail
