package org.sigilaris.core
package crypto

import scodec.bits.ByteVector
import datatype.UInt256

/** Cross-platform minimal PublicKey surface to unify JS/JVM APIs.
  *
  * Represents an uncompressed secp256k1 public key with explicit x and y
  * coordinates. The 64-byte representation consists of x and y coordinates
  * concatenated (x||y), each being 32 bytes, big-endian.
  *
  * @see [[org.sigilaris.core.crypto.PublicKey]] for platform-specific
  *      implementations
  */
trait PublicKeyLike:
  /** Returns the 64-byte uncompressed public key representation.
    *
    * @return
    *   64 bytes = x (32 bytes) || y (32 bytes), big-endian
    */
  def toBytes: ByteVector

  /** X-coordinate of the elliptic curve point.
    *
    * @return
    *   x-coordinate as 32-byte [[UInt256]]
    */
  def x: UInt256

  /** Y-coordinate of the elliptic curve point.
    *
    * @return
    *   y-coordinate as 32-byte [[UInt256]]
    */
  def y: UInt256


