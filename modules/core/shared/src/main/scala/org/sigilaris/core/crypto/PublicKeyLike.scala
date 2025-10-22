package org.sigilaris.core
package crypto

import scodec.bits.ByteVector
import datatype.UInt256

/** Cross-platform minimal PublicKey surface to unify JS/JVM APIs. */
trait PublicKeyLike:
  def toBytes: ByteVector
  def x: UInt256
  def y: UInt256


