package org.sigilaris.core.application.scheduling

import scodec.bits.ByteVector

opaque type StateRef = ByteVector

object StateRef:
  def fromBytes(bytes: ByteVector): StateRef = bytes

  def tableKey(
      tablePrefix: ByteVector,
      encodedKey: ByteVector,
  ): StateRef =
    tablePrefix ++ encodedKey

  extension (stateRef: StateRef)
    def bytes: ByteVector = stateRef
    def toHexLower: String = stateRef.toHex
