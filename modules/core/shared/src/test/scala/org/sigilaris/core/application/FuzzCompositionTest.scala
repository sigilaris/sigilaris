package org.sigilaris.core.application

import hedgehog.munit.HedgehogSuite
import hedgehog.*

/** Fuzz tests for module composition using Hedgehog.
  *
  * These tests verify that composing modules with random configurations
  * maintains the prefix-free property and other invariants.
  */
class FuzzCompositionTest extends HedgehogSuite:

  // Generators
  def genTableName: Gen[String] =
    Gen.frequency1(
      5 -> Gen.string(Gen.alphaNum, Range.linear(1, 20)),
      2 -> Gen.constant("accounts"),
      2 -> Gen.constant("balances"),
      1 -> Gen.constant("metadata"),
      1 -> Gen.constant("groups"),
      1 -> Gen.constant("members"),
    )

  def genPathSegment: Gen[String] =
    Gen.frequency1(
      5 -> Gen.string(Gen.alphaNum, Range.linear(1, 15)),
      2 -> Gen.constant("app"),
      1 -> Gen.constant("v1"),
      1 -> Gen.constant("v2"),
      1 -> Gen.constant("core"),
    )

  def genPath: Gen[List[String]] =
    Gen.list(genPathSegment, Range.linear(1, 4)).map(_.distinct)

  case class TableSpec(path: List[String], name: String)

  def genTableSpec: Gen[TableSpec] =
    for
      path <- genPath
      name <- genTableName
    yield TableSpec(path, name)

  def genSchema: Gen[List[TableSpec]] =
    Gen.list(genTableSpec, Range.linear(1, 10))

  // Fuzz test: random schemas should be prefix-free when distinct
  property("fuzz: random schemas maintain prefix-free property"):
    for specs <- genSchema.forAll
    yield
      val distinct = specs.distinct
      val prefixes = distinct.map: spec =>
        tablePrefixRuntimeFromList(spec.path, spec.name)

      val result = PrefixFreeValidator.validate(prefixes)
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: composing multiple schemas at same path
  property("fuzz: composing schemas at same path"):
    for
      path  <- genPath.forAll
      names <- Gen.list(genTableName, Range.linear(0, 5)).forAll
    yield
      val distinctNames = names.distinct
      val prefixes = distinctNames.map: name =>
        tablePrefixRuntimeFromList(path, name)

      val result = PrefixFreeValidator.validate(prefixes)
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: same schema at different paths
  property("fuzz: same table names at different paths"):
    for
      paths <- Gen.list(genPath, Range.linear(0, 3)).forAll
      name  <- genTableName.forAll
    yield
      val distinctPaths = paths.distinct
      val prefixes = distinctPaths.map: path =>
        tablePrefixRuntimeFromList(path, name)

      // All prefixes should be distinct
      prefixes.distinct.size ==== distinctPaths.size

  // Fuzz test: hierarchical path structure
  property("fuzz: hierarchical paths maintain prefix-free"):
    for
      basePath <- genPath.forAll
      name1    <- genTableName.forAll
      name2    <- genTableName.forAll
    yield
      val extendedPath = basePath :+ "sub"

      val prefix1 = tablePrefixRuntimeFromList(basePath, name1)
      val prefix2 = tablePrefixRuntimeFromList(extendedPath, name2)

      // Should never be a prefix collision
      val result = PrefixFreeValidator.validate(List(prefix1, prefix2))
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: composition with overlapping table names (should fail)
  property("fuzz: detect collisions with duplicate names"):
    for
      path <- genPath.forAll
      name <- genTableName.forAll
    yield
      val prefix1 = tablePrefixRuntimeFromList(path, name)
      val prefix2 = tablePrefixRuntimeFromList(path, name)

      val result = PrefixFreeValidator.validate(List(prefix1, prefix2))
      result match
        case PrefixFreeValidator.IdenticalPrefixes(p, count) =>
          Result.all(List(
            p ==== prefix1,
            count ==== 2
          ))
        case _ =>
          Result.failure

  // Fuzz test: cross-module dependencies
  property("fuzz: cross-module table access"):
    for
      path1       <- genPath.forAll
      path2       <- genPath.forAll
      sharedNames <- Gen.list(genTableName, Range.linear(0, 3)).forAll
    yield
      val distinctNames = sharedNames.distinct

      val module1Prefixes = distinctNames.map(n => tablePrefixRuntimeFromList(path1, n))
      val module2Prefixes = distinctNames.map(n => tablePrefixRuntimeFromList(path2, n))

      val allPrefixes = module1Prefixes ++ module2Prefixes

      val result = PrefixFreeValidator.validate(allPrefixes)

      // Should be valid if paths are different, or identical if paths are same
      if path1 == path2 then
        result match
          case PrefixFreeValidator.IdenticalPrefixes(_, _) => Result.success
          case PrefixFreeValidator.Valid => Result.assert(distinctNames.size <= 1)
          case _ => Result.failure
      else
        result ==== PrefixFreeValidator.Valid

  // Fuzz test: large composition
  property("fuzz: large schema composition"):
    for specs <- Gen.list(genTableSpec, Range.linear(0, 20)).forAll
    yield
      val distinct = specs.distinct
      val prefixes = distinct.map: spec =>
        tablePrefixRuntimeFromList(spec.path, spec.name)

      val result = PrefixFreeValidator.validate(prefixes)
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: path depth variation
  property("fuzz: varying path depths"):
    for name <- genTableName.forAll
    yield
      val paths = List(
        List("a"),
        List("a", "b"),
        List("a", "b", "c"),
        List("a", "b", "c", "d"),
      )

      val prefixes = paths.map(p => tablePrefixRuntimeFromList(p, name))
      val result   = PrefixFreeValidator.validate(prefixes)

      result ==== PrefixFreeValidator.Valid

  // Fuzz test: similar table names
  property("fuzz: similar table names don't collide"):
    for base <- Gen.string(Gen.alpha, Range.linear(0, 10)).forAll
    yield
      val names = List(
        base,
        base + "_data",
        base + "_meta",
        base + "_index",
        s"${base}_v1",
        s"${base}_v2",
      ).distinct

      val path     = List("app")
      val prefixes = names.map(n => tablePrefixRuntimeFromList(path, n))
      val result   = PrefixFreeValidator.validate(prefixes)

      result ==== PrefixFreeValidator.Valid

  // Fuzz test: path similarity
  property("fuzz: similar paths don't collide"):
    for base <- Gen.list(genPathSegment, Range.linear(0, 3)).forAll
    yield
      val paths = List(
        base,
        base :+ "x",
        base :+ "y",
        base ++ List("x", "y"),
      ).distinct

      val name     = "table"
      val prefixes = paths.map(p => tablePrefixRuntimeFromList(p, name))
      val result   = PrefixFreeValidator.validate(prefixes)

      result ==== PrefixFreeValidator.Valid

  // Fuzz test: edge case - empty path segments
  property("fuzz: handles empty path segments gracefully"):
    for _ <- Gen.constant(()).forAll
    yield
      val paths = List(
        List(""),
        List("", "a"),
        List("a", ""),
        List("", "", ""),
      )

      val results = paths.map: path =>
        val prefix = tablePrefixRuntimeFromList(path, "table")
        Result.assert(prefix.nonEmpty).log(s"Empty prefix for path: $path")

      Result.all(results)

  // Fuzz test: composition order independence
  property("fuzz: composition order doesn't affect prefix-free property"):
    for specs <- Gen.list(genTableSpec, Range.linear(0, 5)).forAll
    yield
      val prefixes1 = specs.map(s => tablePrefixRuntimeFromList(s.path, s.name))
      val prefixes2 = specs.reverse.map(s => tablePrefixRuntimeFromList(s.path, s.name))

      val result1 = PrefixFreeValidator.validate(prefixes1)
      val result2 = PrefixFreeValidator.validate(prefixes2.reverse)

      // Results should be consistent (both valid or both invalid in same way)
      result1 ==== result2

  // Fuzz test: realistic DApp structure
  property("fuzz: realistic DApp schema"):
    for _ <- Gen.constant(()).forAll
    yield
      val dappSchema = List(
        TableSpec(List("app", "accounts"), "accounts"),
        TableSpec(List("app", "accounts"), "balances"),
        TableSpec(List("app", "groups"), "groups"),
        TableSpec(List("app", "groups"), "members"),
        TableSpec(List("app", "tokens"), "tokens"),
        TableSpec(List("app", "tokens"), "holders"),
        TableSpec(List("app", "governance"), "proposals"),
        TableSpec(List("app", "governance"), "votes"),
      )

      val prefixes = dappSchema.map(s => tablePrefixRuntimeFromList(s.path, s.name))
      val result   = PrefixFreeValidator.validate(prefixes)

      result ==== PrefixFreeValidator.Valid

  // Fuzz test: stress test with many modules
  property("fuzz: stress test with 100 modules"):
    for specs <- Gen.list(genTableSpec, Range.linear(0, 100)).forAll
    yield
      val distinct = specs.distinct
      if distinct.size < 2 then
        Result.success
      else
        val prefixes = distinct.map(s => tablePrefixRuntimeFromList(s.path, s.name))
        val result   = PrefixFreeValidator.validate(prefixes)

        result ==== PrefixFreeValidator.Valid

  // Fuzz test: verify no false negatives
  property("fuzz: validator correctly identifies real collisions"):
    for spec <- genTableSpec.forAll
    yield
      // Create intentional duplicate
      val duplicate = spec.copy()
      val prefixes = List(
        tablePrefixRuntimeFromList(spec.path, spec.name),
        tablePrefixRuntimeFromList(duplicate.path, duplicate.name),
      )

      val result = PrefixFreeValidator.validate(prefixes)
      result match
        case PrefixFreeValidator.IdenticalPrefixes(_, count) => count ==== 2
        case _ => Result.failure

  // Fuzz test: versioned schemas
  property("fuzz: versioned schemas maintain prefix-free"):
    for name <- genTableName.forAll
    yield
      val versions = List("v1", "v2", "v3", "v4")
      val prefixes = versions.map: v =>
        tablePrefixRuntimeFromList(List("app", v), name)

      val result = PrefixFreeValidator.validate(prefixes)
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: multi-tenant structure
  property("fuzz: multi-tenant isolation"):
    for
      tenants   <- Gen.list(Gen.string(Gen.alpha, Range.linear(1, 10)), Range.linear(0, 5)).forAll
      tableName <- genTableName.forAll
    yield
      val distinctTenants = tenants.distinct
      val prefixes = distinctTenants.map: tenant =>
        tablePrefixRuntimeFromList(List("tenants", tenant), tableName)

      val result = PrefixFreeValidator.validate(prefixes)
      result ==== PrefixFreeValidator.Valid
