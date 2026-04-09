package org.sigilaris.node.jvm.storage

import cats.data.EitherT

import org.sigilaris.core.failure.DecodeFailure

import scodec.bits.ByteVector

/** Store that holds a single optional value.
  *
  * @tparam F the effect type
  * @tparam A the type of value stored
  */
trait SingleValueStore[F[_], A]:

  /** Retrieves the stored value.
    *
    * @return the value if present, or None
    */
  def get(): EitherT[F, DecodeFailure, Option[A]]

  /** Replaces the stored value.
    *
    * @param a the value to store
    */
  def put(a: A): F[Unit]

/** Provides a `SingleValueStore` derived from a `KeyValueStore` using a fixed key. */
object SingleValueStore:

  /** Creates a `SingleValueStore` backed by a `KeyValueStore` with a constant key.
    *
    * @tparam F the effect type
    * @tparam A the value type
    * @param kvStore the underlying key-value store keyed by `ByteVector`
    * @return a single-value store delegating to the key-value store
    */
  def fromKeyValueStore[F[_], A](using
      kvStore: KeyValueStore[F, ByteVector, A],
  ): SingleValueStore[F, A] = new SingleValueStore[F, A]:
    override def get(): EitherT[F, DecodeFailure, Option[A]] =
      kvStore.get(Key)

    override def put(a: A): F[Unit] =
      kvStore.put(Key, a)

  private val Key: ByteVector = ByteVector.low(32)
