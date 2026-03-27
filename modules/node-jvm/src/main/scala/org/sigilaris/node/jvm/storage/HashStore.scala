package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.failure.DecodeFailure

trait HashStore[F[_], A]:
  def get(hash: Hash.Value[A]): EitherT[F, DecodeFailure, Option[A]]
  def put(a: A): F[Unit]
  def remove(hash: Hash.Value[A]): F[Unit]

object HashStore:
  given fromKeyValueStore[F[_], A: Hash](using
      kvStore: KeyValueStore[F, Hash.Value[A], A],
  ): HashStore[F, A] = new HashStore[F, A]:
    override def get(hash: Hash.Value[A]): EitherT[F, DecodeFailure, Option[A]] =
      kvStore.get(hash)

    override def put(a: A): F[Unit] =
      kvStore.put(a.toHash, a)

    override def remove(hash: Hash.Value[A]): F[Unit] =
      kvStore.remove(hash)
