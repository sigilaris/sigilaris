package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

/** Ordered key-value store that supports range queries starting from a given key.
  *
  * @tparam F the effect type
  * @tparam K the key type (must have an ordering for range queries)
  * @tparam V the value type
  */
trait StoreIndex[F[_], K, V] extends KeyValueStore[F, K, V]:

  /** Returns a paginated list of key-value pairs starting from the given key.
    *
    * @param key    the starting key (inclusive)
    * @param offset number of entries to skip from the starting key
    * @param limit  maximum number of entries to return
    * @return a list of key-value pairs in key order
    */
  def from(
      key: K,
      offset: Int,
      limit: Int,
  ): EitherT[F, DecodeFailure, List[(K, V)]]
