package org.sigilaris.core.application

import hedgehog.munit.HedgehogSuite
import hedgehog.*
import org.sigilaris.core.application.{support as supportPkg}
import supportPkg.{encodePathRuntime, encodeSegmentRuntime, lenBytesRuntime, tablePrefixRuntimeFromList}
import org.sigilaris.core.assembly.PrefixFreeValidator

/** Property-based tests for path encoding functions using Hedgehog.
  *
  * These tests verify that path encoding maintains the prefix-free property
  * and produces consistent, deterministic results.
  */
class PathEncodingPropertyTest extends HedgehogSuite:

  // Generators
  def genSegment: Gen[String] =
    Gen.string(Gen.alpha, Range.linear(1, 20))

  def genPath: Gen[List[String]] =
    Gen.list(genSegment, Range.linear(0, 5))

  // Property: encodeSegment should produce different outputs for different inputs
  property("encodeSegmentRuntime: different inputs produce different outputs"):
    for
      s1 <- genSegment.forAll
      s2 <- genSegment.forAll
    yield
      val enc1 = encodeSegmentRuntime(s1)
      val enc2 = encodeSegmentRuntime(s2)
      if s1 == s2 then enc1 ==== enc2
      else Result.assert(enc1 != enc2)

  // Property: encodeSegment should be deterministic
  property("encodeSegmentRuntime: deterministic encoding"):
    for s <- genSegment.forAll
    yield
      val enc1 = encodeSegmentRuntime(s)
      val enc2 = encodeSegmentRuntime(s)
      enc1 ==== enc2

  // Property: encodeSegment should be injective (one-to-one)
  property("encodeSegmentRuntime: injective encoding"):
    for
      s1 <- genSegment.forAll
      s2 <- genSegment.forAll
    yield
      val enc1 = encodeSegmentRuntime(s1)
      val enc2 = encodeSegmentRuntime(s2)
      if enc1 == enc2 then s1 ==== s2
      else Result.success

  // Property: length-prefix encoding should make segments prefix-free
  property("encodeSegmentRuntime: encoded segments are prefix-free"):
    for
      s1 <- genSegment.forAll
      s2 <- genSegment.forAll
    yield
      val enc1 = encodeSegmentRuntime(s1)
      val enc2 = encodeSegmentRuntime(s2)

      val isPrefix = enc1.length <= enc2.length && enc2.take(enc1.length) == enc1

      // If enc1 is a prefix of enc2, they must be identical
      if isPrefix then enc1 ==== enc2
      else Result.success

  // Property: encoded segment should contain length information
  property("encodeSegmentRuntime: encoding includes length and sentinel"):
    for s <- genSegment.forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      val bytes   = s.getBytes("UTF-8")

      // Encoded should be: varlen(length) + bytes + sentinel(0x00)
      // At minimum, 1 byte for length + bytes + 1 byte sentinel
      Result.assert(encoded.length >= 1 + bytes.length + 1)

  // Property: encodePath should produce different outputs for different paths
  property("encodePathRuntime: different paths produce different outputs"):
    for segments <- genPath.forAll
    yield
      val path1 = segments
      val path2 = segments.reverse

      val enc1 = encodePathRuntime(path1)
      val enc2 = encodePathRuntime(path2)

      if path1 == path2 then enc1 ==== enc2
      else Result.assert(enc1 != enc2)

  // Property: encodePath should be deterministic
  property("encodePathRuntime: deterministic encoding"):
    for segments <- genPath.forAll
    yield
      val enc1 = encodePathRuntime(segments)
      val enc2 = encodePathRuntime(segments)
      enc1 ==== enc2

  // Property: empty path should encode to empty bytes
  property("encodePathRuntime: empty path"):
    for _ <- Gen.constant(()).forAll
    yield
      val encoded = encodePathRuntime(Nil)
      encoded ==== lenBytesRuntime(0)

  // Property: single-segment path has path-level framing
  property("encodePathRuntime: single segment"):
    for s <- genSegment.forAll
    yield
      val pathEncoded    = encodePathRuntime(List(s))
      val header         = lenBytesRuntime(1) // length header for 1 segment
      val segmentEncoded = encodeSegmentRuntime(s)
      pathEncoded ==== header ++ segmentEncoded

  // Property: path framing prevents concatenation property
  // NOTE: With path-level framing, concatenation does NOT hold because each
  // path has its own length header. This is intentional - the framing ensures
  // that shorter paths cannot be prefixes of longer ones.
  property("encodePathRuntime: path framing structure"):
    for
      p1 <- genPath.forAll
      p2 <- genPath.forAll
    yield
      val encCombined = encodePathRuntime(p1 ++ p2)
      val headerCombined = lenBytesRuntime(p1.length + p2.length)

      // The combined path has a single header for the total length
      Result.assert(encCombined.startsWith(headerCombined))
        .log(s"Paths: p1=$p1, p2=$p2")
        .log(s"Combined path length: ${p1.length + p2.length}")

  // Property: tablePrefix should combine path and name encodings
  property("tablePrefixRuntime: combines path and name"):
    for
      path <- genPath.forAll
      name <- genSegment.forAll
    yield
      val tablePrefix = tablePrefixRuntimeFromList(path, name)
      val expected    = encodePathRuntime(path) ++ encodeSegmentRuntime(name)

      tablePrefix ==== expected

  // Property: different table names at same path produce different prefixes
  property("tablePrefixRuntime: different names produce different prefixes"):
    for
      path  <- genPath.forAll
      name1 <- genSegment.forAll
      name2 <- genSegment.forAll
    yield
      val prefix1 = tablePrefixRuntimeFromList(path, name1)
      val prefix2 = tablePrefixRuntimeFromList(path, name2)

      if name1 == name2 then prefix1 ==== prefix2
      else Result.assert(prefix1 != prefix2)

  // Property: different paths with same table name produce different prefixes
  property("tablePrefixRuntime: different paths produce different prefixes"):
    for
      path1 <- genPath.forAll
      path2 <- genPath.forAll
      name  <- genSegment.forAll
    yield
      val prefix1 = tablePrefixRuntimeFromList(path1, name)
      val prefix2 = tablePrefixRuntimeFromList(path2, name)

      if path1 == path2 then prefix1 ==== prefix2
      else Result.assert(prefix1 != prefix2)

  // Property: table prefixes should be prefix-free
  property("tablePrefixRuntime: prefix-free property"):
    for
      path  <- genPath.forAll
      name1 <- genSegment.forAll
      name2 <- genSegment.forAll
    yield
      val prefix1 = tablePrefixRuntimeFromList(path, name1)
      val prefix2 = tablePrefixRuntimeFromList(path, name2)

      val isPrefix = prefix1.length < prefix2.length && prefix2.take(prefix1.length) == prefix1

      // Length-prefix encoding guarantees no strict prefix relationship can exist
      // between different table names. If we detect a prefix, it's a bug.
      if isPrefix then
        Result.failure.log(s"Prefix collision detected: '$name1' is prefix of '$name2'")
      else if name1 == name2 then
        prefix1 ==== prefix2
      else
        // Different names must produce different prefixes
        Result.assert(prefix1 != prefix2)

  // Property: encoding provides deterministic ordering
  property("encodePath: deterministic ordering"):
    for
      p1 <- genPath.forAll
      p2 <- genPath.forAll
    yield
      val enc1 = encodePathRuntime(p1)
      val enc2 = encodePathRuntime(p2)

      // Path encoding is prefix-free and deterministic, but does NOT preserve
      // lexicographic ordering (that's only required for OrderedCodec on KEY types).
      // We only verify: identical paths encode identically, different paths encode differently
      if p1 == p2 then
        enc1 ==== enc2
      else
        Result.assert(enc1 != enc2)
          .log(s"Paths: p1=$p1, p2=$p2")

  // Edge case: very long segments
  property("encodeSegmentRuntime: handles long segments"):
    for s <- Gen.string(Gen.alpha, Range.linear(100, 1000)).forAll
    yield
      val encoded = encodeSegmentRuntime(s)
      val bytes = s.getBytes("UTF-8")
      // varlen(length) + bytes + sentinel
      Result.assert(encoded.length >= 1 + bytes.length + 1)

  // Edge case: empty segment
  property("encodeSegmentRuntime: handles empty segment"):
    for _ <- Gen.constant(()).forAll
    yield
      val encoded = encodeSegmentRuntime("")
      // varlen(0) + empty + sentinel = at least 2 bytes
      Result.assert(encoded.length >= 2)

  // Edge case: special characters
  property("encodeSegmentRuntime: handles special characters"):
    for _ <- Gen.constant(()).forAll
    yield
      val segments = List("", "a", "ab", "a-b", "a_b", "a.b", "a/b", "a b", "日本語")
      val results = segments.map: s =>
        val encoded = encodeSegmentRuntime(s)
        Result.assert(encoded.nonEmpty).log(s"Failed to encode: $s")
      Result.all(results)

  // Regression: verify PrefixFreeValidator accepts encoded paths
  property("encodePath: validator accepts encoded paths"):
    for
      names1 <- Gen.list(genSegment, Range.linear(0, 3)).forAll
      names2 <- Gen.list(genSegment, Range.linear(0, 3)).forAll
    yield
      val names = (names1 ++ names2).distinct

      val prefixes = names.map(encodeSegmentRuntime)
      val result   = PrefixFreeValidator.validate(prefixes)

      result ==== PrefixFreeValidator.Valid
