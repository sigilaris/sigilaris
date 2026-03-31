package org.sigilaris.node.jvm.runtime.gossip

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class GossipImportRuleSuite extends FunSuite:

  private val gossipRoot =
    Path
      .of(sys.props.getOrElse("user.dir", "."))
      .resolve("src")
      .resolve("main")
      .resolve("scala")
      .resolve("org")
      .resolve("sigilaris")
      .resolve("node")
      .resolve("jvm")
      .resolve("runtime")
      .resolve("gossip")

  private val bannedImports = List(
    "com.linecorp.armeria",
    "sttp.tapir.server.armeria",
    "org.sigilaris.node.jvm.transport.armeria",
    "org.sigilaris.node.jvm.storage.memory",
    "org.sigilaris.node.jvm.storage.swaydb",
    "swaydb",
  )

  test("runtime gossip sources do not import transport or storage implementations"):
    val sources = Using.resource(Files.walk(gossipRoot)): stream =>
      stream.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toList

    assert(sources.nonEmpty, s"Expected Scala sources under $gossipRoot")

    val violations =
      for
        source <- sources
        line <- Files.readAllLines(source).asScala
        banned <- bannedImports
        trimmed = line.trim
        if trimmed.startsWith("import ") && trimmed.contains(banned)
      yield s"${gossipRoot.relativize(source)} imports $banned"

    assertEquals(violations, Nil)
