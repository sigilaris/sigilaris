package org.sigilaris.node.jvm.transport.armeria

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class TransportImportRuleSuite extends FunSuite:

  private val transportRoot =
    Path
      .of(sys.props.getOrElse("user.dir", "."))
      .resolve("src")
      .resolve("main")
      .resolve("scala")
      .resolve("org")
      .resolve("sigilaris")
      .resolve("node")
      .resolve("jvm")
      .resolve("transport")
      .resolve("armeria")

  private val bannedImports = List(
    "org.sigilaris.node.jvm.storage.memory",
    "org.sigilaris.node.jvm.storage.swaydb",
    "swaydb",
    "com.buchigo.",
  )

  test("transport sources do not import storage implementations"):
    val sources = Using.resource(Files.walk(transportRoot)): stream =>
      stream.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toList

    assert(sources.nonEmpty, s"Expected Scala sources under $transportRoot")

    val violations =
      for
        source <- sources
        line   <- Files.readAllLines(source).asScala
        banned <- bannedImports
        trimmed = line.trim
        if trimmed.startsWith("import ") && trimmed.contains(banned)
      yield s"${transportRoot.relativize(source)} imports $banned"

    assertEquals(violations, Nil)
