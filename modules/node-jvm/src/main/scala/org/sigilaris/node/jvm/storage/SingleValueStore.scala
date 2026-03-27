package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

import scodec.bits.ByteVector

trait SingleValueStore[F[_], A]:
  def get(): EitherT[F, DecodeFailure, Option[A]]
  def put(a: A): F[Unit]

object SingleValueStore:
  def fromKeyValueStore[F[_], A](using
      kvStore: KeyValueStore[F, ByteVector, A],
  ): SingleValueStore[F, A] = new SingleValueStore[F, A]:
    override def get(): EitherT[F, DecodeFailure, Option[A]] =
      kvStore.get(Key)

    override def put(a: A): F[Unit] =
      kvStore.put(Key, a)

  private val Key: ByteVector = ByteVector.low(32)
