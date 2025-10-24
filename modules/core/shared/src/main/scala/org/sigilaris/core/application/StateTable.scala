package org.sigilaris.core
package application

import cats.Monad
import cats.data.{EitherT, StateT}

import codec.byte.ByteCodec
import codec.byte.ByteDecoder.ops.*
import failure.{DecodeFailure, SigilarisFailure}
import merkle.{MerkleTrie, MerkleTrieState}
import merkle.Nibbles.*

/** State table providing key-value storage with compile-time key safety.
  *
  * A StateTable is path-independent - it knows only its schema (name, key type,
  * value type, and codecs). The actual storage location (path/prefix) is
  * determined when the table is mounted into a module.
  *
  * Key safety is enforced via instance branding: keys are branded with the
  * table's singleton type (self.type), preventing keys from different tables
  * from being mixed at compile time with zero runtime overhead.
  *
  * @tparam F the effect type
  */
trait StateTable[F[_]]:
  self =>

  /** The table name as a literal String type. */
  type Name <: String

  /** The key type. */
  type K

  /** The value type. */
  type V

  /** ByteCodec for keys. */
  given kCodec: ByteCodec[K]

  /** ByteCodec for values. */
  given vCodec: ByteCodec[V]

  /** The branded key type, preventing cross-table key confusion. */
  type Key = KeyOf[self.type, K]

  /** The table name as a runtime String value.
    *
    * Used for prefix computation when the table is mounted into a module.
    */
  def name: Name

  /** Brand an unbranded key with this table's instance type.
    *
    * @param k the unbranded key
    * @return the branded key
    */
  inline def brand(k: K): Key = KeyOf[self.type, K](k)

  /** Retrieve a value by key.
    *
    * @param k the branded key
    * @return a stateful computation returning Some(value) if found, None otherwise
    */
  def get(k: Key): StoreF[F][Option[V]]

  /** Insert or update a key-value pair.
    *
    * @param k the branded key
    * @param v the value
    * @return a stateful computation returning Unit
    */
  def put(k: Key, v: V): StoreF[F][Unit]

  /** Remove a key-value pair.
    *
    * @param k the branded key
    * @return a stateful computation returning true if the key existed, false otherwise
    */
  def remove(k: Key): StoreF[F][Boolean]

object StateTable:
  /** Instantiate a path-independent StateTable at a specific prefix.
    *
    * StateTable schemas are path-independent - they define only the table name,
    * key/value types, and codecs. This method binds a schema to a concrete
    * prefix (computed from a module's mount path), creating a usable table instance.
    *
    * This is called internally during module mounting (Phase 2). Users typically
    * don't call this directly - instead, they define Entry schemas and let the
    * mount machinery compute prefixes and instantiate tables.
    *
    * The table name is extracted from the literal type N using ValueOf.
    *
    * @tparam F the effect type
    * @tparam N the table name (literal String type, must have ValueOf instance)
    * @tparam K0 the key type
    * @tparam V0 the value type
    * @param prefix the byte prefix for all keys in this table (computed from mount path)
    * @param kCodec the key codec
    * @param vCodec the value codec
    * @param nodeStore the underlying node storage
    * @return a concrete StateTable instance bound to the given prefix
    */
  def atPrefix[F[_]: Monad, N <: String: ValueOf, K0, V0](
      prefix: scodec.bits.ByteVector,
  )(using
      kCodec: ByteCodec[K0],
      vCodec: ByteCodec[V0],
      nodeStore: MerkleTrie.NodeStore[F],
  ): StateTable[F] { type Name = N; type K = K0; type V = V0 } =
    new StateTable[F]:
      type Name = N
      type K = K0
      type V = V0

      given kCodec: ByteCodec[K] = summon[ByteCodec[K0]]
      given vCodec: ByteCodec[V] = summon[ByteCodec[V0]]

      val name: Name = valueOf[N]

      private def liftError[A](
          op: StateT[EitherT[F, String, *], MerkleTrieState, A],
      ): StoreF[F][A] =
        StateT: (state: MerkleTrieState) =>
          op.run(state).leftMap(err => DecodeFailure(err): SigilarisFailure)

      def get(k: Key): StoreF[F][Option[V]] =
        val rawKey = KeyOf.unwrap(k)
        val fullKeyBytes = prefix ++ ByteCodec[K].encode(rawKey)
        val fullKey = fullKeyBytes.toNibbles
        StateT: (state: MerkleTrieState) =>
          MerkleTrie.get[F](fullKey).run(state).leftMap(DecodeFailure(_): SigilarisFailure).flatMap:
            case (nextState, None) => EitherT.rightT[F, SigilarisFailure]((nextState, None))
            case (nextState, Some(bytes)) =>
              bytes.to[V] match
                case Right(value) => EitherT.rightT[F, SigilarisFailure]((nextState, Some(value)))
                case Left(err) => EitherT.leftT[F, (MerkleTrieState, Option[V])]((err: SigilarisFailure))

      def put(k: Key, v: V): StoreF[F][Unit] =
        val rawKey = KeyOf.unwrap(k)
        val fullKeyBytes = prefix ++ ByteCodec[K].encode(rawKey)
        val fullKey = fullKeyBytes.toNibbles
        val valueBytes = ByteCodec[V].encode(v)
        liftError:
          MerkleTrie.put[F](fullKey, valueBytes)

      def remove(k: Key): StoreF[F][Boolean] =
        val rawKey = KeyOf.unwrap(k)
        val fullKeyBytes = prefix ++ ByteCodec[K].encode(rawKey)
        val fullKey = fullKeyBytes.toNibbles
        liftError:
          MerkleTrie.remove[F](fullKey)
