package org.sigilaris.core

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class CoreImportRuleSuite extends FunSuite:

  private val repoRoot = locateRepoRoot()

  private val moduleRoot = repoRoot.resolve("modules").resolve("core")

  private val sourceRoots = List(
    moduleRoot.resolve("shared").resolve("src").resolve("main").resolve("scala"),
    moduleRoot.resolve("jvm").resolve("src").resolve("main").resolve("scala"),
    moduleRoot.resolve("js").resolve("src").resolve("main").resolve("scala"),
  ).filter(Files.exists(_))

  test("core production sources do not import node packages"):
    val sources = sourceRoots.flatMap: root =>
      Using.resource(Files.walk(root)): stream =>
        stream.iterator.asScala
          .filter(path =>
            Files.isRegularFile(path) && path.toString.endsWith(".scala"),
          )
          .toList

    assert(sources.nonEmpty, s"Expected Scala sources under $sourceRoots")

    // A lightweight text scan is enough here because this suite only guards the
    // top-level module boundary against obvious import drift.
    val violations =
      for
        source <- sources
        line   <- Files.readAllLines(source).asScala
        trimmed = line.trim
        if trimmed.startsWith("import org.sigilaris.node")
      yield s"${moduleRoot.relativize(source)} imports node packages"

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
