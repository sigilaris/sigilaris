package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.Path

import scala.concurrent.ExecutionContext

import cats.data.EitherT
import cats.effect.IO

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.storage.KeyValueStore

import scodec.bits.ByteVector
import swaydb.Map
import swaydb.data.order.KeyOrder
import swaydb.data.slice.Slice

import StoreIndexSwayInterpreter.given

/** `KeyValueStore` implementation backed by a SwayDB persistent map.
  *
  * Values are serialized to byte arrays using `ByteEncoder`/`ByteDecoder`.
  *
  * @tparam K the key type
  * @tparam V the value type, which must have byte codec instances
  * @param map the underlying SwayDB map
  */
final class KeyValueSwayStore[K, V: ByteEncoder: ByteDecoder](
    map: Map[K, Array[Byte], Nothing, IO],
) extends KeyValueStore[IO, K, V]:

  override def get(key: K): EitherT[IO, DecodeFailure, Option[V]] =
    for
      arrayOpt <- EitherT.right(map.get(key))
      decoded <- arrayOpt match
        case Some(bytes) =>
          EitherT.fromEither[IO]:
            ByteDecoder[V]
              .decode(ByteVector.view(bytes))
              .flatMap: result =>
                StoreIndexSwayInterpreter
                  .ensureNoRemainder(result, "decoded value has remainder")
              .map(Some(_))
        case None =>
          EitherT.rightT[IO, DecodeFailure](None)
    yield decoded

  override def put(key: K, value: V): IO[Unit] =
    map.put(key, ByteEncoder[V].encode(value).toArray).void

  override def remove(key: K): IO[Unit] =
    map.remove(key).void

  /** Closes the underlying SwayDB map and releases resources. */
  def close(): IO[Unit] =
    map.close().void

/** Factory methods for creating `KeyValueSwayStore` instances. */
object KeyValueSwayStore:
  private[swaydb] def openMap[K: ByteEncoder: ByteDecoder](
      dir: Path,
  )(using Bag.Async[IO]): IO[Map[K, Array[Byte], Nothing, IO]] =
    given KeyOrder[Slice[Byte]] = KeyOrder.default
    given KeyOrder[K] with
      override def compare(left: K, right: K): Int =
        Ordering[ByteVector].compare(
          ByteEncoder[K].encode(left),
          ByteEncoder[K].encode(right),
        )
    given ExecutionContext =
      swaydb.configs.level.DefaultExecutionContext.compactionEC

    swaydb.persistent.Map[K, Array[Byte], Nothing, IO](dir)

  /** Creates a new `KeyValueSwayStore` by opening a persistent SwayDB map at the given directory.
    *
    * @tparam K the key type
    * @tparam V the value type
    * @param dir the filesystem directory for the SwayDB storage
    * @return an IO that yields the constructed store
    */
  def apply[K: ByteEncoder: ByteDecoder, V: ByteEncoder: ByteDecoder](
      dir: Path,
  )(using Bag.Async[IO]): IO[KeyValueSwayStore[K, V]] =
    openMap[K](dir).map(new KeyValueSwayStore[K, V](_))
