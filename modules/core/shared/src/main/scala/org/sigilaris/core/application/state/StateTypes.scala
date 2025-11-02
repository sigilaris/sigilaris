package org.sigilaris.core.application.state

import cats.Monad
import cats.data.{EitherT, StateT}

import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.failure.SigilarisFailure
import org.sigilaris.core.merkle.MerkleTrieState

/** Effect type for blockchain operations.
  *
  * Wraps an underlying effect F with error handling via EitherT.
  *
  * @tparam F
  *   the base effect type
  */
type Eff[F[_]] = EitherT[F, SigilarisFailure, *]

/** Combined state for blockchain storage and access logging.
  *
  * Phase 8: Extends MerkleTrieState with AccessLog to track all read/write
  * operations performed during transaction execution.
  *
  * @param trieState
  *   the Merkle trie state
  * @param accessLog
  *   the accumulated access log
  */
final case class StoreState(
    trieState: MerkleTrieState,
    accessLog: AccessLog,
)

object StoreState:
  /** Create an empty store state with no logged accesses. */
  def empty: StoreState = StoreState(MerkleTrieState.empty, AccessLog.empty)

  /** Create a store state from just a trie state (empty access log). */
  def fromTrieState(trieState: MerkleTrieState): StoreState =
    StoreState(trieState, AccessLog.empty)

/** State effect type for blockchain storage operations.
  *
  * Phase 8 update: Now operates over StoreState (MerkleTrieState + AccessLog)
  * instead of just MerkleTrieState, enabling automatic access logging and
  * conflict detection.
  *
  * @tparam F
  *   the base effect type
  */
type StoreF[F[_]] = StateT[Eff[F], StoreState, *]

object StoreF:
  /** Lift a pure value into StoreF. */
  def pure[F[_]: Monad, A](value: A): StoreF[F][A] =
    StateT.pure[Eff[F], StoreState, A](value)

  /** Lift an Eff action into StoreF. */
  def lift[F[_]: Monad, A](fa: Eff[F][A]): StoreF[F][A] =
    StateT.liftF[Eff[F], StoreState, A](fa)

  /** Raise a failure, short-circuiting the StoreF computation. */
  def raise[F[_]: Monad, A](failure: SigilarisFailure): StoreF[F][A] =
    StateT.liftF[Eff[F], StoreState, A](EitherT.leftT[F, A](failure))

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
      nodeStore: org.sigilaris.core.merkle.MerkleTrie.NodeStore[F],
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
