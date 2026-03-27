package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

trait KeyValueStore[F[_], K, V]:
  def get(key: K): EitherT[F, DecodeFailure, Option[V]]
  def put(key: K, value: V): F[Unit]
  def remove(key: K): F[Unit]
