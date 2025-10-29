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
  * @tparam F
  *   the base effect type
  */
type Eff[F[_]] = EitherT[F, SigilarisFailure, *]

/** State effect type for blockchain storage operations.
  *
  * Combines state management (StateT) with error handling (Eff) over
  * MerkleTrieState.
  *
  * @tparam F
  *   the base effect type
  */
type StoreF[F[_]] = StateT[Eff[F], MerkleTrieState, *]

/** Zero-cost key branding to prevent keys from different tables being mixed.
  *
  * Brand is typically the table instance's singleton type (self.type), ensuring
  * compile-time safety with no runtime overhead.
  *
  * @tparam Brand
  *   the phantom type used for branding (usually table instance type)
  * @tparam A
  *   the underlying key type
  */
opaque type KeyOf[Brand, A] = A

object KeyOf:
  /** Create a branded key from an unbranded value.
    *
    * @param a
    *   the unbranded key value
    * @return
    *   the branded key
    */
  inline def apply[Brand, A](a: A): KeyOf[Brand, A] = a

  /** Extract the unbranded value from a branded key.
    *
    * @param branded
    *   the branded key
    * @return
    *   the unbranded value
    */
  inline def unwrap[Brand, A](branded: KeyOf[Brand, A]): A = branded

/** Schema entry defining a table's name and key-value types.
  *
  * This is both a compile-time marker for type-level schema definitions and a
  * runtime value that can create StateTable instances.
  *
  * Each Entry requires ByteCodec instances for both key and value types.
  *
  * @tparam Name
  *   the table name (literal String type)
  * @tparam K
  *   the key type
  * @tparam V
  *   the value type
  */
final class Entry[Name <: String, K, V](val tableName: Name)(using
    val kCodec: ByteCodec[K],
    val vCodec: ByteCodec[V],
):
  /** Create a StateTable instance at the given prefix.
    *
    * Re-exposes the codecs captured when this Entry was created, ensuring that
    * the blueprint's codec choices are preserved across mounts regardless of
    * what codecs are in scope at the mount call site.
    *
    * @param prefix
    *   the byte prefix for this table
    * @param monad
    *   the Monad instance for F
    * @param nodeStore
    *   the MerkleTrie node store
    * @return
    *   a StateTable bound to the given prefix
    */
  def createTable[F[_]](prefix: scodec.bits.ByteVector)(using
      monad: cats.Monad[F],
      nodeStore: merkle.MerkleTrie.NodeStore[F],
  ): StateTable[F] {
    type Name = Entry.this.Name; type K = Entry.this.K; type V = Entry.this.V
  } =
    given ValueOf[Name] = ValueOf(tableName)
    // Re-expose the captured codecs to implicit search so StateTable.atPrefix
    // uses the exact codecs this Entry was created with, not whatever happens
    // to be in scope at the mount call site
    given ByteCodec[K] = kCodec
    given ByteCodec[V] = vCodec
    StateTable.atPrefix[F, Name, K, V](prefix)

object Entry:
  /** Create an Entry instance with inferred name from ValueOf.
    *
    * @tparam Name
    *   the table name (literal String type)
    * @tparam K
    *   the key type
    * @tparam V
    *   the value type
    */
  def apply[Name <: String: ValueOf, K: ByteCodec, V: ByteCodec]
      : Entry[Name, K, V] =
    new Entry[Name, K, V](valueOf[Name])
