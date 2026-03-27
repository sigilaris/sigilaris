package org.sigilaris.node.jvm.storage.swaydb

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class SwayDbImportRuleSuite extends FunSuite:

  private val swayDbRoot =
    Path
      .of(sys.props.getOrElse("user.dir", "."))
      .resolve("src")
      .resolve("main")
      .resolve("scala")
      .resolve("org")
      .resolve("sigilaris")
      .resolve("node")
      .resolve("jvm")
      .resolve("storage")
      .resolve("swaydb")

  private val bannedImports = List(
    "org.sigilaris.node.jvm.transport.armeria",
    "com.linecorp.armeria",
    "sttp.tapir",
    "com.buchigo.",
  )
  // `com.buchigo.` is the downstream package prefix and must never flow back into sigilaris.

  test("swaydb storage sources do not import transport or downstream packages"):
    val sources = Using.resource(Files.walk(swayDbRoot)): stream =>
      stream.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .toList

    assert(sources.nonEmpty, s"Expected Scala sources under $swayDbRoot")

    val violations =
      for
        source <- sources
        line   <- Files.readAllLines(source).asScala
        banned <- bannedImports
        trimmed = line.trim
        if trimmed.startsWith("import ") && trimmed.contains(banned)
      yield s"${swayDbRoot.relativize(source)} imports $banned"

    assertEquals(violations, Nil)
