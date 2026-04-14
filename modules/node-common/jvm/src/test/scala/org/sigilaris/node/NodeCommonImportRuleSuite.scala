package org.sigilaris.node

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class NodeCommonImportRuleSuite extends FunSuite:

  private val repoRoot = locateRepoRoot()

  private val moduleRoot = repoRoot.resolve("modules").resolve("node-common")

  private val sharedRoot =
    moduleRoot.resolve("shared").resolve("src").resolve("main").resolve("scala")

  private val bannedImports = List(
    "org.sigilaris.node.jvm",
    "com.linecorp.armeria",
    "swaydb",
    "com.typesafe.config",
    "java.net.http",
    "java.util.Base64",
    "java.util.UUID",
  )

  test("node-common shared sources do not import runtime-specific packages"):
    val sources = Using.resource(Files.walk(sharedRoot)): stream =>
      stream.iterator.asScala
        .filter(path =>
          Files.isRegularFile(path) && path.toString.endsWith(".scala"),
        )
        .toList

    assert(sources.nonEmpty, s"Expected Scala sources under $sharedRoot")

    val violations =
      for
        source <- sources
        line   <- Files.readAllLines(source).asScala
        banned <- bannedImports
        trimmed = line.trim
        if trimmed.startsWith("import ") && trimmed.contains(banned)
      yield s"${moduleRoot.relativize(source)} imports $banned"

    assertEquals(violations, Nil)

  private def locateRepoRoot(): Path =
    Iterator
      .iterate(Path.of(sys.props.getOrElse("user.dir", ".")).toAbsolutePath.normalize())(
        _.getParent,
      )
      .takeWhile(_ != null)
      .find(path => Files.exists(path.resolve("build.sbt")))
      .getOrElse(
        throw new IllegalStateException(
          s"Could not locate repository root from ${sys.props.getOrElse("user.dir", ".")}",
        ),
      )
