package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

trait StoreIndex[F[_], K, V] extends KeyValueStore[F, K, V]:
  def from(
      key: K,
      offset: Int,
      limit: Int,
  ): EitherT[F, DecodeFailure, List[(K, V)]]
