package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Files, Path}

import cats.effect.{IO, Resource}
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.storage.{
  KeyValueStore,
  SingleValueStore,
  StoreIndex,
}

import scodec.bits.ByteVector

object SwayStores:
  private given ByteEncoder[ByteVector] = (value: ByteVector) => value
  private given ByteDecoder[ByteVector] = bytes =>
    DecodeResult(bytes, ByteVector.empty).asRight[DecodeFailure]

  def keyValue[K: ByteEncoder: ByteDecoder, V: ByteEncoder: ByteDecoder](
      dir: Path,
  )(using Bag.Async[IO]): Resource[IO, KeyValueStore[IO, K, V]] =
    Resource.make(
      ensureDirectory(dir).flatMap(_ => KeyValueSwayStore[K, V](dir)),
    )(_.close())

  def singleValue[V: ByteEncoder: ByteDecoder](dir: Path)(using
      Bag.Async[IO],
  ): Resource[IO, SingleValueStore[IO, V]] =
    keyValue[ByteVector, V](dir).map: kvStore =>
      given KeyValueStore[IO, ByteVector, V] = kvStore
      SingleValueStore.fromKeyValueStore[IO, V]

  def storeIndex[K: ByteEncoder: ByteDecoder, V: ByteEncoder: ByteDecoder](
      dir: Path,
  )(using Bag.Async[IO]): Resource[IO, StoreIndex[IO, K, V]] =
    Resource.make(
      ensureDirectory(dir).flatMap(_ => StoreIndexSwayInterpreter[K, V](dir)),
    )(_.close())

  private def ensureDirectory(dir: Path): IO[Unit] =
    IO.blocking(Files.createDirectories(dir)).void
