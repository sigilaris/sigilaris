package org.sigilaris.node.jvm.runtime

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class RuntimeImportRuleSuite extends FunSuite:

  private val runtimeRoot =
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

  // Reserved implementation package roots from the extraction plan.
  // Runtime sources must never import these packages, including before
  // later phases add the actual transport/storage implementations.
  private val bannedImports = List(
    "com.linecorp.armeria",
    "sttp.tapir.server.armeria",
    "swaydb",
    "org.sigilaris.node.jvm.transport.armeria",
    "org.sigilaris.node.jvm.storage.memory",
    "org.sigilaris.node.jvm.storage.swaydb",
  )

  test(
    "runtime production sources do not import transport or storage implementations",
  ):
    val runtimeSources = Using.resource(Files.walk(runtimeRoot)): stream =>
      stream.iterator.asScala
        .filter(path =>
          Files.isRegularFile(path) && path.toString.endsWith(".scala"),
        )
        .toList

    assert(
      runtimeSources.nonEmpty,
      s"Expected Scala sources under $runtimeRoot",
    )

    val violations =
      for
        source <- runtimeSources
        line   <- Files.readAllLines(source).asScala
        banned <- bannedImports
        trimmed = line.trim
        if trimmed.startsWith("import ") && trimmed.contains(banned)
      yield s"${runtimeRoot.relativize(source)} imports $banned"

    assertEquals(violations, Nil)
