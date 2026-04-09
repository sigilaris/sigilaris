package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.failure.DecodeFailure

/** Content-addressable store that uses cryptographic hashes as keys.
  *
  * @tparam F the effect type
  * @tparam A the type of values stored, which must be hashable
  */
trait HashStore[F[_], A]:

  /** Retrieves a value by its hash.
    *
    * @param hash the hash of the value to look up
    * @return the value if found, or None
    */
  def get(hash: Hash.Value[A]): EitherT[F, DecodeFailure, Option[A]]

  /** Stores a value, keyed by its computed hash.
    *
    * @param a the value to store
    */
  def put(a: A): F[Unit]

  /** Removes the value associated with the given hash.
    *
    * @param hash the hash of the value to remove
    */
  def remove(hash: Hash.Value[A]): F[Unit]

/** Provides a default `HashStore` instance derived from a `KeyValueStore`. */
object HashStore:

  /** Derives a `HashStore` from an existing `KeyValueStore` keyed by hash values.
    *
    * @tparam F the effect type
    * @tparam A the value type, which must have a `Hash` instance
    * @param kvStore the underlying key-value store
    * @return a `HashStore` backed by the given key-value store
    */
  given fromKeyValueStore[F[_], A: Hash](using
      kvStore: KeyValueStore[F, Hash.Value[A], A],
  ): HashStore[F, A] = new HashStore[F, A]:
    override def get(
        hash: Hash.Value[A],
    ): EitherT[F, DecodeFailure, Option[A]] =
      kvStore.get(hash)

    override def put(a: A): F[Unit] =
      kvStore.put(a.toHash, a)

    override def remove(hash: Hash.Value[A]): F[Unit] =
      kvStore.remove(hash)
