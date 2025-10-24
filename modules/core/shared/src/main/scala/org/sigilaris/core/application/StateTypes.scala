package org.sigilaris.core
package application

import cats.data.{EitherT, StateT}

import codec.byte.ByteCodec
import failure.SigilarisFailure
import merkle.MerkleTrieState

/** Effect type for blockchain operations.
  *
  * Wraps an underlying effect F with error handling via EitherT.
  *
  * @tparam F the base effect type
  */
type Eff[F[_]] = EitherT[F, SigilarisFailure, *]

/** State effect type for blockchain storage operations.
  *
  * Combines state management (StateT) with error handling (Eff) over
  * MerkleTrieState.
  *
  * @tparam F the base effect type
  */
type StoreF[F[_]] = StateT[Eff[F], MerkleTrieState, *]

/** Zero-cost key branding to prevent keys from different tables being mixed.
  *
  * Brand is typically the table instance's singleton type (self.type), ensuring
  * compile-time safety with no runtime overhead.
  *
  * @tparam Brand the phantom type used for branding (usually table instance type)
  * @tparam A the underlying key type
  */
opaque type KeyOf[Brand, A] = A

object KeyOf:
  /** Create a branded key from an unbranded value.
    *
    * @param a the unbranded key value
    * @return the branded key
    */
  inline def apply[Brand, A](a: A): KeyOf[Brand, A] = a

  /** Extract the unbranded value from a branded key.
    *
    * @param branded the branded key
    * @return the unbranded value
    */
  inline def unwrap[Brand, A](branded: KeyOf[Brand, A]): A = branded

/** Schema entry defining a table's name and key-value types.
  *
  * This is a compile-time marker used for type-level schema definitions.
  * Each Entry requires ByteCodec instances for both key and value types.
  *
  * @tparam Name the table name (literal String type)
  * @tparam K the key type
  * @tparam V the value type
  */
final class Entry[Name <: String, K, V](using val kCodec: ByteCodec[K], val vCodec: ByteCodec[V])
