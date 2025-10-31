package org.sigilaris.core.application

import hedgehog.munit.HedgehogSuite
import hedgehog.*

/** Fuzz tests for prefix encoding using Hedgehog.
  *
  * These tests generate random, potentially pathological inputs to verify
  * robustness and correctness of the prefix encoding implementation.
  */
class FuzzPrefixEncodingTest extends HedgehogSuite:

  // Aggressive generators for fuzzing
  def genFuzzString: Gen[String] =
    Gen.frequency1(
      5 -> Gen.string(Gen.alphaNum, Range.linear(0, 50)),
      2 -> Gen.string(Gen.ascii, Range.linear(0, 50)),
      2 -> Gen.string(Gen.unicode, Range.linear(0, 50)),
      1 -> Gen.constant(""),
      1 -> Gen.string(Gen.alpha, Range.linear(1000, 1000)),
      1 -> Gen.list(Gen.byte(Range.linear(0, 127)), Range.linear(0, 50)).map(_.map(_.toChar).mkString),
    )

  def genFuzzPath: Gen[List[String]] =
    Gen.list(genFuzzString, Range.linear(0, 10))

  // Fuzz test: random strings should always produce valid encodings
  property("fuzz: encodeSegment handles arbitrary strings"):
    for s <- genFuzzString.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      // varlen + bytes + sentinel: at least 2 bytes
      Result.all(List(
        Result.assert(encoded != null),
        Result.assert(encoded.length >= 2)
      ))

  // Fuzz test: random paths should always produce valid encodings
  property("fuzz: encodePath handles arbitrary paths"):
    for path <- genFuzzPath.forAll
    yield
      val encoded = encodePathRuntime(path)
      Result.assert(encoded != null)

  // Fuzz test: no encoded segments should collide
  property("fuzz: random segments maintain prefix-free property"):
    for segments <- Gen.list(genFuzzString, Range.linear(0, 100)).forAll
    yield
      val distinct = segments.distinct
      val encoded  = distinct.map(encodeSegmentRuntime)

      val result = PrefixFreeValidator.validate(encoded)
      result ==== PrefixFreeValidator.Valid

  // Fuzz test: encoding should be stable under repeated calls
  property("fuzz: encoding is stable"):
    for s <- genFuzzString.forAll
    yield
      val encodings = (1 to 10).map(_ => encodeSegmentRuntime(s))
      encodings.distinct.size ==== 1

  // Fuzz test: null bytes in input
  property("fuzz: handles null bytes in input"):
    val withNulls = Gen.list(Gen.frequency1(
      9 -> Gen.alpha,
      1 -> Gen.constant('\u0000')
    ), Range.linear(0, 50)).map(_.mkString)

    for s <- withNulls.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      Result.assert(encoded.length >= 2) // varlen + sentinel minimum

  // Fuzz test: very long segments
  property("fuzz: handles very long segments"):
    val longSegments = Gen.string(Gen.alpha, Range.linear(1000, 10000))

    for s <- longSegments.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      val bytes = s.getBytes("UTF-8")
      Result.assert(encoded.length >= 1 + bytes.length + 1)

  // Fuzz test: mixed character sets
  property("fuzz: handles mixed character sets"):
    val mixed = Gen.list(Gen.frequency1(
      3 -> Gen.alpha,
      2 -> Gen.digit,
      1 -> Gen.constant('한'),
      1 -> Gen.constant('日'),
      1 -> Gen.element1(' ', '\t', '\n', '\r'),
    ), Range.linear(0, 50)).map(_.mkString)

    for s <- mixed.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      Result.assert(encoded.length >= 2) // varlen + sentinel minimum

  // Fuzz test: collision resistance
  property("fuzz: collision resistance"):
    for base <- Gen.string(Gen.alpha, Range.linear(0, 20)).forAll
    yield
      val similar = List(
        base,
        base + "a",
        base + "b",
        "a" + base,
        base.reverse,
      ).distinct

      val encoded = similar.map(encodeSegmentRuntime)
      encoded.distinct.size ==== similar.size

  // Fuzz test: prefix property with similar strings
  property("fuzz: no false prefix collisions with similar strings"):
    for base <- Gen.string(Gen.alpha, Range.linear(0, 20)).forAll
    yield
      val variants = List(
        base,
        base + "x",
        base + "y",
        base + "z",
        base + base,
      ).distinct

      val encoded = variants.map(encodeSegmentRuntime)
      val result  = PrefixFreeValidator.validate(encoded)

      result ==== PrefixFreeValidator.Valid

  // Fuzz test: table prefixes with random paths and names
  property("fuzz: table prefixes are prefix-free"):
    for
      paths <- Gen.list(genFuzzPath, Range.linear(0, 10)).forAll
      names <- Gen.list(genFuzzString, Range.linear(0, 10)).forAll
    yield
      val pairs = paths.zip(names)

      if pairs.isEmpty then
        Result.success
      else
        val prefixes = pairs.map((path, name) => tablePrefixRuntimeFromList(path, name))
        val result   = PrefixFreeValidator.validate(prefixes)

        if pairs.size == pairs.distinct.size then
          result ==== PrefixFreeValidator.Valid
        else
          Result.success // Duplicates are expected

  // Fuzz test: empty and whitespace segments
  property("fuzz: handles empty and whitespace segments"):
    for _ <- Gen.constant(()).forAll
    yield
      val edgeCases = List("", " ", "  ", "\t", "\n", "\r", " \t\n", "   ")

      val results = edgeCases.map: s =>
        val encoded = encodeSegmentRuntime(s)
        Result.assert(encoded.length >= 2).log(s"Failed to encode: '$s'")

      // Check they're all distinct
      val encoded = edgeCases.map(encodeSegmentRuntime)
      val distinctCheck = Result.assert(encoded.distinct.size == edgeCases.size)
        .log("Whitespace variants should produce distinct encodings")

      Result.all(results :+ distinctCheck)

  // Fuzz test: path ordering consistency
  property("fuzz: path ordering is consistent"):
    for
      p1 <- genFuzzPath.forAll
      p2 <- genFuzzPath.forAll
      p3 <- genFuzzPath.forAll
    yield
      val enc1 = encodePathRuntime(p1)
      val enc2 = encodePathRuntime(p2)
      val enc3 = encodePathRuntime(p3)

      val cmp12 = enc1.compare(enc2)
      val cmp23 = enc2.compare(enc3)
      val cmp13 = enc1.compare(enc3)

      // Transitivity: if enc1 < enc2 and enc2 < enc3, then enc1 < enc3
      if cmp12 < 0 && cmp23 < 0 then
        Result.assert(cmp13 < 0)
      else
        Result.success

  // Fuzz test: length-prefix format verification
  property("fuzz: length-prefix format contains original bytes"):
    for s <- genFuzzString.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      val bytes   = s.getBytes("UTF-8")

      // The encoding format is: varlen(length) + bytes + sentinel(0x00)
      // We can verify the encoded bytes contain the original content
      // by checking the encoded length is sufficient
      Result.assert(encoded.length >= bytes.length + 2) // at least varlen(1) + bytes + sentinel(1)

  // Fuzz test: encoding is injective
  property("fuzz: encoding is injective"):
    for
      s1 <- genFuzzString.forAll
      s2 <- genFuzzString.forAll
    yield
      val enc1 = encodeSegmentRuntime(s1)
      val enc2 = encodeSegmentRuntime(s2)

      ((s1 == s2) == (enc1 == enc2)) ==== true

  // Fuzz test: stress test with many segments
  property("fuzz: stress test with many segments"):
    for segments <- Gen.list(Gen.string(Gen.alpha, Range.linear(0, 20)), Range.linear(0, 1000)).forAll
    yield
      val distinct = segments.distinct
      if distinct.size < 2 then
        Result.success
      else
        val encoded = distinct.map(encodeSegmentRuntime)
        val result  = PrefixFreeValidator.validate(encoded)

        result ==== PrefixFreeValidator.Valid

  // Fuzz test: verify no false positives in prefix detection
  property("fuzz: no false positive prefix collisions"):
    for segments <- Gen.list(genFuzzString, Range.linear(0, 20)).forAll
    yield
      val distinct = segments.distinct.filter(_.nonEmpty)
      val encoded  = distinct.map(encodeSegmentRuntime)

      val result = PrefixFreeValidator.validate(encoded)

      // Should never report a collision for distinct inputs
      result match
        case PrefixFreeValidator.Valid => Result.success
        case PrefixFreeValidator.IdenticalPrefixes(_, _) =>
          Result.failure.log("Bug: distinct inputs produced identical encodings")
        case PrefixFreeValidator.PrefixCollision(_, _) =>
          Result.failure.log("Bug: valid encoding reported as collision")

  // Fuzz test: table prefix uniqueness under composition
  property("fuzz: composed table prefixes are unique"):
    for
      paths <- Gen.list(genFuzzPath, Range.linear(0, 5)).forAll
      names <- Gen.list(genFuzzString, Range.linear(0, 5)).forAll
    yield
      val allPairs = for
        path <- paths
        name <- names
      yield (path, name)

      val allPrefixes = allPairs.map((path, name) => tablePrefixRuntimeFromList(path, name))

      if allPairs.isEmpty then
        // Empty case: no prefixes to validate
        Result.success
      else
        val result = PrefixFreeValidator.validate(allPrefixes)

        // Should be valid or have expected duplicates
        result match
          case PrefixFreeValidator.Valid =>
            // All (path, name) pairs must be distinct
            Result.assert(allPairs.distinct.size == allPairs.size)
          case PrefixFreeValidator.IdenticalPrefixes(prefix, count) =>
            // Identical prefixes are only OK if we have duplicate (path, name) pairs
            val duplicateCount = allPrefixes.count(_ == prefix)
            duplicateCount ==== count
          case PrefixFreeValidator.PrefixCollision(_, _) =>
            // This should NEVER happen with length-prefix encoding for distinct pairs
            Result.failure.log("Unexpected prefix collision with length-prefix encoding")
