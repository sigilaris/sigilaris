package org.sigilaris.node.jvm.storage.memory

import cats.effect.IO
import munit.CatsEffectSuite

import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.crypto.Hash.ops.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.node.jvm.storage.{HashStore, KeyValueStore, SingleValueStore}

final class InMemoryStoresSuite extends CatsEffectSuite:

  test("keyValue supports get, put, and remove"):
    InMemoryStores
      .keyValue[IO, String, Int]
      .use: store =>
        for
          missing <- store.get("alpha").value
          _ <- IO(assertEquals(missing, Right(None)))
          _ <- store.put("alpha", 1)
          loaded <- store.get("alpha").value
          _ <- IO(assertEquals(loaded, Right(Some(1))))
          _ <- store.remove("alpha")
          removed <- store.get("alpha").value
          _ <- IO(assertEquals(removed, Right(None)))
        yield ()

  test("singleValue overwrites the previous value"):
    InMemoryStores
      .singleValue[IO, String]
      .use: store =>
        for
          _ <- store.put("first")
          _ <- store.put("second")
          loaded <- store.get().value
          _ <- IO(assertEquals(loaded, Right(Some("second"))))
        yield ()

  test("SingleValueStore.fromKeyValueStore uses the reserved singleton key"):
    InMemoryStores
      .keyValue[IO, scodec.bits.ByteVector, String]
      .use: kvStore =>
        given KeyValueStore[IO, scodec.bits.ByteVector, String] = kvStore
        val store = SingleValueStore.fromKeyValueStore[IO, String]

        for
          _ <- store.put("singleton")
          loaded <- store.get().value
          _ <- IO(assertEquals(loaded, Right(Some("singleton"))))
        yield ()

  test("storeIndex paginates from a starting key in order"):
    InMemoryStores
      .storeIndex[IO, Int, String]
      .use: index =>
        for
          _ <- index.put(1, "one")
          _ <- index.put(2, "two")
          _ <- index.put(3, "three")
          _ <- index.put(4, "four")
          page <- index.from(2, offset = 1, limit = 2).value
          _ <- IO(assertEquals(page, Right(List(3 -> "three", 4 -> "four"))))
        yield ()

  test("HashStore derives from KeyValueStore for hashable values"):
    InMemoryStores
      .keyValue[IO, Hash.Value[Utf8], Utf8]
      .use: kvStore =>
        given KeyValueStore[IO, Hash.Value[Utf8], Utf8] = kvStore
        val store = summon[HashStore[IO, Utf8]]
        val value = Utf8("sigilaris")
        val hash  = value.toHash

        for
          _ <- store.put(value)
          loaded <- store.get(hash).value
          _ <- IO(assertEquals(loaded, Right(Some(value))))
          _ <- store.remove(hash)
          removed <- store.get(hash).value
          _ <- IO(assertEquals(removed, Right(None)))
        yield ()
