package org.sigilaris.core
package crypto

import util.SafeStringInterp.*
import datatype.UInt256

/** ECDSA signature with recovery parameter for secp256k1.
  *
  * Represents a recoverable ECDSA signature consisting of (v, r, s) where:
  *   - v is the recovery parameter (27-30) allowing public key recovery
  *   - r is the x-coordinate of the ephemeral public key
  *   - s is the signature proof value
  *
  * @param v
  *   recovery parameter, typically 27 or 28 (27 + recId where recId is 0 or 1)
  * @param r
  *   signature component r, 32-byte [[UInt256]]
  * @param s
  *   signature component s, 32-byte [[UInt256]], normalized to Low-S form (s ≤
  *   n/2)
  *
  * @example
  *   ```scala
  *   val keyPair = CryptoOps.generate()
  *   val hash = CryptoOps.keccak256("message".getBytes)
  *   val signature = CryptoOps.sign(keyPair, hash).toOption.get
  *
  *   // Recover public key from signature
  *   val recovered = CryptoOps.recover(signature, hash)
  *   ```
  *
  * @note
  *   Signatures are normalized to Low-S form (s ≤ n/2) to prevent malleability
  *
  * @see [[Sign]] for signature creation
  * @see [[Recover]] for public key recovery
  * @see [[CryptoOps.sign]] for signing
  * @see [[CryptoOps.recover]] for recovery
  */
final case class Signature(
    v: Int,
    r: UInt256,
    s: UInt256,
):
  override lazy val toString: String =
    ss"Signature(${v.toString}, ${r.toHexLower}, ${s.toHexLower})"


