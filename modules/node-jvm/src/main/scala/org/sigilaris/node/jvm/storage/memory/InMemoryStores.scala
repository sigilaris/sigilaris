package org.sigilaris.node.jvm.storage.memory

import scala.collection.immutable.TreeMap

import cats.Monad
import cats.data.EitherT
import cats.effect.kernel.{Ref, Resource, Sync}
import cats.syntax.functor.given

import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.storage.{KeyValueStore, SingleValueStore, StoreIndex}

private final class InMemoryKeyValueStore[F[_]: Monad, K, V](ref: Ref[F, Map[K, V]])
    extends KeyValueStore[F, K, V]:

  override def get(key: K): EitherT[F, DecodeFailure, Option[V]] =
    EitherT.right(ref.get.map(_.get(key)))

  override def put(key: K, value: V): F[Unit] =
    ref.update(_.updated(key, value))

  override def remove(key: K): F[Unit] =
    ref.update(_ - key)

private final class InMemoryStoreIndex[F[_]: Monad, K, V](ref: Ref[F, TreeMap[K, V]])
    extends StoreIndex[F, K, V]:

  override def get(key: K): EitherT[F, DecodeFailure, Option[V]] =
    EitherT.right(ref.get.map(_.get(key)))

  override def put(key: K, value: V): F[Unit] =
    ref.update(_.updated(key, value))

  override def remove(key: K): F[Unit] =
    ref.update(_ - key)

  override def from(
      key: K,
      offset: Int,
      limit: Int,
  ): EitherT[F, DecodeFailure, List[(K, V)]] =
    val page = ref.get.map: data =>
      if limit <= 0 then Nil
      else data.rangeFrom(key).iterator.drop(offset.max(0)).take(limit).toList

    EitherT.right(page)

private final class InMemorySingleValueStore[F[_]: Monad, A](ref: Ref[F, Option[A]])
    extends SingleValueStore[F, A]:

  override def get(): EitherT[F, DecodeFailure, Option[A]] =
    EitherT.right(ref.get)

  override def put(a: A): F[Unit] =
    ref.set(Some(a))

object InMemoryStores:
  def keyValue[F[_]: Sync, K, V]: Resource[F, KeyValueStore[F, K, V]] =
    Resource.eval(
      Ref.of[F, Map[K, V]](Map.empty).map(new InMemoryKeyValueStore[F, K, V](_))
    )

  def storeIndex[F[_]: Sync, K: Ordering, V]: Resource[F, StoreIndex[F, K, V]] =
    Resource.eval(
      Ref.of[F, TreeMap[K, V]](TreeMap.empty[K, V]).map(new InMemoryStoreIndex[F, K, V](_))
    )

  def singleValue[F[_]: Sync, A]: Resource[F, SingleValueStore[F, A]] =
    Resource.eval(
      Ref.of[F, Option[A]](None).map(new InMemorySingleValueStore[F, A](_))
    )
