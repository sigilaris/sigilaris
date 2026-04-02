package org.sigilaris.node.jvm.runtime.consensus.hotstuff

import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*
import scala.util.Using

import munit.FunSuite

final class HotStuffImportRuleSuite extends FunSuite:

  private val moduleRoot =
    val workingDir = Path.of(sys.props.getOrElse("user.dir", ".")).toAbsolutePath.normalize
    val directModuleRoot = workingDir
    val nestedModuleRoot = workingDir.resolve("modules").resolve("node-jvm")

    if Files.isDirectory(directModuleRoot.resolve("src").resolve("main").resolve("scala")) then
      directModuleRoot
    else nestedModuleRoot

  private val gossipRoots = List(
    sourceRoot("main", "org", "sigilaris", "node", "jvm", "runtime", "gossip"),
    sourceRoot("test", "org", "sigilaris", "node", "jvm", "runtime", "gossip"),
  )

  private val hotStuffRoots = List(
    sourceRoot("main", "org", "sigilaris", "node", "jvm", "runtime", "consensus", "hotstuff"),
    sourceRoot("test", "org", "sigilaris", "node", "jvm", "runtime", "consensus", "hotstuff"),
  )

  private val gossipBannedImports = List(
    "org.sigilaris.node.jvm.runtime.consensus",
  )

  private val hotStuffBannedImports = List(
    "com.linecorp.armeria",
    "sttp.tapir.server.armeria",
    "org.sigilaris.node.jvm.transport",
    // Abstract store contracts under `org.sigilaris.node.jvm.storage` remain allowed.
    // This rule blocks the current concrete storage implementation roots only.
    "org.sigilaris.node.jvm.storage.memory",
    "org.sigilaris.node.jvm.storage.swaydb",
    "swaydb",
  )

  test("runtime gossip sources do not import consensus runtime packages"):
    assertNoImportViolations(gossipRoots, gossipBannedImports)

  test("hotstuff consensus sources do not import transport or storage implementations"):
    assertNoImportViolations(hotStuffRoots, hotStuffBannedImports)

  test("import parser detects comma-separated and grouped import targets"):
    assertEquals(
      normalizedImportTargets(
        "foo.Bar, org.sigilaris.node.jvm.runtime.consensus.hotstuff.Baz",
      ),
      List(
        "foo.Bar",
        "org.sigilaris.node.jvm.runtime.consensus.hotstuff.Baz",
      ),
    )

    assertEquals(
      normalizedImportTargets(
        "org.sigilaris.node.jvm.storage.memory.{MemStore => MemAlias, OtherStore}",
      ),
      List(
        "org.sigilaris.node.jvm.storage.memory.MemStore",
        "org.sigilaris.node.jvm.storage.memory.OtherStore",
      ),
    )

  test("import parser assembles multi-line imports and ignores exclusion selectors"):
    val source =
      """package test
        |
        |import org.sigilaris.node.jvm.storage.memory.{
        |  MemStore => MemAlias,
        |}
        |""".stripMargin

    val tempSource = Files.createTempFile("hotstuff-import-rule", ".scala")

    try
      Files.writeString(tempSource, source)

      val statements = collectedImportStatements(tempSource)
      assertEquals(statements.map(_._1), List(3))
      assertEquals(
        normalizedImportTargets(statements.head._2),
        List("org.sigilaris.node.jvm.storage.memory.MemStore"),
      )
      assertEquals(
        normalizedImportTargets("org.sigilaris.node.jvm.runtime.consensus.{hotstuff as _}"),
        Nil,
      )
      assertEquals(
        normalizedImportTargets("org.sigilaris.node.jvm.runtime.consensus.{hotstuff => _}"),
        Nil,
      )
    finally
      Files.deleteIfExists(tempSource)
      ()

  private def sourceRoot(scope: String, segments: String*): Path =
    segments.foldLeft(moduleRoot.resolve("src").resolve(scope).resolve("scala"))(_.resolve(_))

  private def assertNoImportViolations(roots: List[Path], bannedImports: List[String]): Unit =
    val existingRoots = roots.filter(root => Files.isDirectory(root))
    assert(existingRoots.nonEmpty, s"Expected source roots to exist: ${roots.mkString(", ")}")

    val sources = existingRoots.flatMap: root =>
      Using.resource(Files.walk(root)): stream =>
        stream.iterator.asScala
          .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
          .toList

    assert(sources.nonEmpty, s"Expected Scala sources under ${existingRoots.mkString(", ")}")

    val violations =
      for
        source <- sources
        (lineNumber, importStatement) <- collectedImportStatements(source)
        importTargets = normalizedImportTargets(importStatement)
        matchedBannedImports = bannedImports.filter: banned =>
          importTargets.exists(target => target == banned || target.startsWith(s"$banned."))
        if matchedBannedImports.nonEmpty
      yield s"${moduleRoot.relativize(source)}:${lineNumber} imports ${matchedBannedImports.distinct.mkString(", ")}"

    assert(
      violations.isEmpty,
      s"roots=${roots.mkString(", ")} banned=${bannedImports.mkString(", ")} violations=${violations.mkString("; ")}",
    )

  private def collectedImportStatements(source: Path): List[(Int, String)] =
    val lines = Files.readAllLines(source).asScala.toList
    val statements = scala.collection.mutable.ListBuffer.empty[(Int, String)]
    var currentStartLine: Option[Int] = None
    var currentParts = List.empty[String]
    var braceDepth = 0

    def flushCurrent(): Unit =
      currentStartLine.foreach: startLine =>
        statements += ((startLine, currentParts.mkString(" ")))
      currentStartLine = None
      currentParts = Nil
      braceDepth = 0

    lines.zipWithIndex.foreach: (line, index) =>
      val trimmed = line.trim
      currentStartLine match
        case None =>
          if trimmed.startsWith("import ") then
            val body = trimmed.stripPrefix("import ").trim
            currentStartLine = Some(index + 1)
            currentParts = List(body)
            braceDepth = braceDelta(body)
            if braceDepth <= 0 then flushCurrent()
        case Some(_) =>
          currentParts = currentParts :+ trimmed
          braceDepth = braceDepth + braceDelta(trimmed)
          if braceDepth <= 0 then flushCurrent()

    if currentStartLine.nonEmpty then flushCurrent()

    statements.toList

  private def normalizedImportTargets(importStatement: String): List[String] =
    if importStatement.isEmpty then Nil
    else
      splitTopLevel(importStatement).flatMap: body =>
        if body.contains("{") && body.contains("}") then
          val prefix = body.takeWhile(_ != '{').trim.stripSuffix(".")
          val selectorBody = body.dropWhile(_ != '{').drop(1).takeWhile(_ != '}')
          splitTopLevel(selectorBody).flatMap(selector => normalizeImportSelector(prefix, selector.trim))
        else
          normalizeSimpleImport(body).toList

  private def normalizeImportSelector(prefix: String, selector: String): Option[String] =
    val arrowParts = selector.split("=>", 2).map(_.trim)
    val arrowSelector =
      if arrowParts.length == 2 && arrowParts(1) == "_" then None
      else arrowParts.headOption.map(_.trim).filter(_.nonEmpty)

    val selectorRoot = arrowSelector.flatMap: candidate =>
      val asParts = candidate.split("\\s+as\\s+", 2).map(_.trim)
      if asParts.length == 2 && asParts(1) == "_" then None
      else asParts.headOption.map(_.trim).filter(_.nonEmpty)

    selectorRoot.flatMap:
      case "_" | "*" => Some(prefix)
      case root      => Some(s"$prefix.${root.stripSuffix(".*").stripSuffix("._")}")

  private def normalizeSimpleImport(body: String): Option[String] =
    val importRoot = body.split("\\s+as\\s+", 2).headOption.map(_.trim).getOrElse("")
    val normalized = importRoot.stripSuffix(".*").stripSuffix("._")

    Option.when(normalized.nonEmpty)(normalized)

  private def braceDelta(text: String): Int =
    val withoutInlineComment = text.split("//", 2).headOption.getOrElse(text)
    withoutInlineComment.count(_ == '{') - withoutInlineComment.count(_ == '}')

  private def splitTopLevel(text: String): List[String] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    var braceDepth = 0

    text.foreach:
      case '{' =>
        braceDepth = braceDepth + 1
        current.append('{')
      case '}' =>
        braceDepth = math.max(0, braceDepth - 1)
        current.append('}')
      case ',' if braceDepth == 0 =>
        val part = current.result().trim
        if part.nonEmpty then parts += part
        current.clear()
      case char =>
        current.append(char)

    val tail = current.result().trim
    if tail.nonEmpty then parts += tail

    parts.toList
