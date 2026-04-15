package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

/** Generic key-value store abstraction over an effect type.
  *
  * @tparam F the effect type
  * @tparam K the key type
  * @tparam V the value type
  */
trait KeyValueStore[F[_], K, V]:

  /** Retrieves the value associated with the given key.
    *
    * @param key the key to look up
    * @return the value if found, or None
    */
  def get(key: K): EitherT[F, DecodeFailure, Option[V]]

  /** Associates the given value with the given key, overwriting any existing mapping.
    *
    * @param key   the key
    * @param value the value to store
    */
  def put(key: K, value: V): F[Unit]

  /** Removes the mapping for the given key, if it exists.
    *
    * @param key the key to remove
    */
  def remove(key: K): F[Unit]
