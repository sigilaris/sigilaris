package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite

import org.sigilaris.core.datatype.Utf8

final class SwayStoresSuite extends CatsEffectSuite:
  private given Bag.Async[IO] = Bag.global

  private def tempDirResource: Resource[IO, Path] =
    Resource.make(IO.blocking(Files.createTempDirectory("sigilaris-node-jvm-sway"))) { dir =>
      IO.blocking:
        Using.resource(Files.walk(dir)): stream =>
          stream.iterator.asScala.toList.reverse.foreach(Files.deleteIfExists)
        ()
    }

  test("keyValue round-trips values through SwayDB"):
    tempDirResource.use: root =>
      val dir = root.resolve("kv")
      SwayStores.keyValue[Utf8, Utf8](dir).use: store =>
        for
          _ <- store.put(Utf8("alpha"), Utf8("beta"))
          loaded <- store.get(Utf8("alpha")).value
          _ <- IO(assertEquals(loaded, Right(Some(Utf8("beta")))))
          _ <- store.remove(Utf8("alpha"))
          removed <- store.get(Utf8("alpha")).value
          _ <- IO(assertEquals(removed, Right(None)))
        yield ()

  test("singleValue round-trips values through SwayDB"):
    tempDirResource.use: root =>
      val dir = root.resolve("single")
      SwayStores.singleValue[Utf8](dir).use: store =>
        for
          _ <- store.put(Utf8("singleton"))
          loaded <- store.get().value
          _ <- IO(assertEquals(loaded, Right(Some(Utf8("singleton")))))
        yield ()

  test("storeIndex supports ordered page reads"):
    tempDirResource.use: root =>
      val dir = root.resolve("index")
      SwayStores.storeIndex[Utf8, Long](dir).use: index =>
        for
          _ <- index.put(Utf8("a"), 1L) >>
            index.put(Utf8("b"), 2L) >>
            index.put(Utf8("c"), 3L)
          page <- index.from(Utf8("a"), offset = 1, limit = 2).value
          _ <- IO(assertEquals(page, Right(List(Utf8("b") -> 2L, Utf8("c") -> 3L))))
          _ <- index.remove(Utf8("b"))
          removed <- index.get(Utf8("b")).value
          _ <- IO(assertEquals(removed, Right(None)))
        yield ()

  test("StorageLayout derives the opinionated SwayDB directory structure"):
    IO:
      val root   = Path.of("data", "sway")
      val layout = StorageLayout.fromRoot(root)
      assertEquals(layout.block.bestHeader, root.resolve("block").resolve("best"))
      assertEquals(layout.state.nodes, root.resolve("state").resolve("nodes"))
      assertEquals(
        layout.state.historicalArchive,
        root.resolve("state").resolve("historical-archive"),
      )
