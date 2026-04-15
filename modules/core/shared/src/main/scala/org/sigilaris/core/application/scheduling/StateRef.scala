package org.sigilaris.core.application.scheduling

import scodec.bits.ByteVector

/** Opaque reference to a specific piece of state (table prefix + encoded key).
  *
  * Used as the unit of conflict detection in the scheduling system. Two
  * transactions conflict if they share a StateRef in their read/write sets
  * according to the conflict rules.
  */
opaque type StateRef = ByteVector

/** Companion for [[StateRef]], providing construction and extensions. */
object StateRef:
  /** Creates a StateRef from raw bytes.
    *
    * @param bytes the raw byte representation
    * @return a new StateRef
    */
  def fromBytes(bytes: ByteVector): StateRef = bytes

  /** Creates a StateRef from a table prefix and an encoded key.
    *
    * @param tablePrefix the table's byte prefix
    * @param encodedKey the encoded key bytes
    * @return a StateRef representing this specific table entry
    */
  def tableKey(
      tablePrefix: ByteVector,
      encodedKey: ByteVector,
  ): StateRef =
    tablePrefix ++ encodedKey

  extension (stateRef: StateRef)
    /** Access the underlying raw bytes.
      *
      * @return the byte vector
      */
    def bytes: ByteVector  = stateRef

    /** Returns a lowercase hexadecimal string representation.
      *
      * @return hex string
      */
    def toHexLower: String = stateRef.toHex
