package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all.*

import org.sigilaris.core.codec.byte.{ByteDecoder, ByteEncoder, DecodeResult}
import org.sigilaris.core.failure.DecodeFailure
import org.sigilaris.node.jvm.storage.StoreIndex

import scodec.bits.ByteVector
import swaydb.Map
import swaydb.data.slice.Slice
import swaydb.serializers.Serializer
import swaydb.serializers.Default.ByteArraySerializer

/** `StoreIndex` implementation backed by a SwayDB persistent map, supporting range queries.
  *
  * @tparam K the key type
  * @tparam V the value type, which must have byte codec instances
  * @param map the underlying SwayDB map
  */
final class StoreIndexSwayInterpreter[K, V: ByteEncoder: ByteDecoder](
    map: Map[K, Array[Byte], Nothing, IO],
) extends StoreIndex[IO, K, V]:
  private val keyValue = new KeyValueSwayStore[K, V](map)

  override def get(key: K): EitherT[IO, DecodeFailure, Option[V]] =
    keyValue.get(key)

  override def put(key: K, value: V): IO[Unit] =
    keyValue.put(key, value)

  override def remove(key: K): IO[Unit] =
    keyValue.remove(key)

  @SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
  override def from(
      key: K,
      offset: Int,
      limit: Int,
  ): EitherT[IO, DecodeFailure, List[(K, V)]] = EitherT:
    map
      .fromOrAfter(key)
      .stream
      .drop(offset.max(0))
      .take(limit.max(0))
      .materialize
      .map: entries =>
        entries.toList.traverse:
          case (decodedKey, valueArray) =>
            ByteDecoder[V]
              .decode(ByteVector.view(valueArray))
              .flatMap: result =>
                StoreIndexSwayInterpreter
                  .ensureNoRemainder(result, "decoded value has remainder")
              .map(decodedValue => (decodedKey, decodedValue))

  /** Closes the underlying SwayDB map and releases resources. */
  def close(): IO[Unit] =
    keyValue.close()

@SuppressWarnings(
  Array(
    "org.wartremover.warts.Any",
    "org.wartremover.warts.MutableDataStructures",
  ),
)
/** Factory and SwayDB serializer givens for `StoreIndexSwayInterpreter`. */
object StoreIndexSwayInterpreter:

  /** Creates a new `StoreIndexSwayInterpreter` by opening a persistent SwayDB map at the given directory.
    *
    * @tparam K the key type
    * @tparam V the value type
    * @param dir the filesystem directory for the SwayDB storage
    * @return an IO that yields the constructed store index
    */
  def apply[K: ByteEncoder: ByteDecoder, V: ByteEncoder: ByteDecoder](
      dir: Path,
  )(using Bag.Async[IO]): IO[StoreIndexSwayInterpreter[K, V]] =
    KeyValueSwayStore
      .openMap[K](dir)
      .map(new StoreIndexSwayInterpreter[K, V](_))

  /** Validates that a decode result consumed all bytes, returning a `DecodeFailure` if not.
    *
    * @tparam A the decoded value type
    * @param decoded the decode result to validate
    * @param message the error message if there is a non-empty remainder
    * @return the decoded value, or a `DecodeFailure`
    */
  def ensureNoRemainder[A](
      decoded: DecodeResult[A],
      message: String,
  ): Either[DecodeFailure, A] =
    Either.cond(
      decoded.remainder.isEmpty,
      decoded.value,
      DecodeFailure(message),
    )

  given scala.reflect.ClassTag[Nothing] = scala.reflect.Manifest.Nothing
  given swaydb.core.build.BuildValidator =
    swaydb.core.build.BuildValidator
      .DisallowOlderVersions(swaydb.data.DataType.Map)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  given serializerFromCodecs[A: ByteEncoder: ByteDecoder]: Serializer[A] =
    new Serializer[A]:
      override def write(data: A): Slice[Byte] =
        Slice[Byte](ByteEncoder[A].encode(data).toArray)

      override def read(data: Slice[Byte]): A =
        // SwayDB's serializer contract is exception-based on decode failure.
        ByteDecoder[A].decode(ByteVector.view(data.toArray)) match
          case Right(DecodeResult(value, remainder)) if remainder.isEmpty =>
            value
          case other =>
            throw new Exception(
              s"Failed to decode SwayDB key/value bytes: $other",
            )

  given Serializer[Array[Byte]] = ByteArraySerializer
