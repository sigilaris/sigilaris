package org.sigilaris.core.application.scheduling

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.state.AccessLog

final class SchedulingSuite extends FunSuite:

  private val prefixA = ByteVector.fromValidHex("01")
  private val prefixB = ByteVector.fromValidHex("02")
  private val keyA1   = ByteVector.fromValidHex("aa")
  private val keyA2   = ByteVector.fromValidHex("ab")
  private val keyB1   = ByteVector.fromValidHex("ba")
  private val keyB2   = ByteVector.fromValidHex("bb")

  private val refA1 = StateRef.tableKey(prefixA, keyA1)
  private val refA2 = StateRef.tableKey(prefixA, keyA2)
  private val refB1 = StateRef.tableKey(prefixB, keyB1)
  private val refB2 = StateRef.tableKey(prefixB, keyB2)

  test("StateRef.tableKey appends the encoded key to the table prefix"):
    assertEquals(refA1.bytes, ByteVector.fromValidHex("01aa"))

  test("StateRef.toHexLower renders lowercase hex bytes"):
    assertEquals(refA1.toHexLower, "01aa")

  test("ConflictFootprint.combine unions reads and writes independently"):
    val first = ConflictFootprint(
      reads = Set(refA1),
      writes = Set(refA2),
    )
    val second = ConflictFootprint(
      reads = Set(refB1),
      writes = Set(refA1),
    )

    assertEquals(
      first.combine(second),
      ConflictFootprint(
        reads = Set(refA1, refB1),
        writes = Set(refA2, refA1),
      ),
    )

  test("ConflictFootprint.combine has empty identity and is associative"):
    val first  = ConflictFootprint(reads = Set(refA1), writes = Set(refA2))
    val second = ConflictFootprint(reads = Set(refB1), writes = Set.empty)
    val third  = ConflictFootprint(reads = Set.empty, writes = Set(refB2))

    assertEquals(first.combine(ConflictFootprint.empty), first)
    assertEquals(ConflictFootprint.empty.combine(first), first)
    assertEquals(
      first.combine(second).combine(third),
      first.combine(second.combine(third)),
    )

  test("ConflictFootprint.conflictsWith allows R∩R and rejects W∩W / R∩W"):
    val readOnlyA  = ConflictFootprint(reads = Set(refA1), writes = Set.empty)
    val readOnlyB  = ConflictFootprint(reads = Set(refA1), writes = Set.empty)
    val writeSame  = ConflictFootprint(reads = Set.empty, writes = Set(refA1))
    val writeOther = ConflictFootprint(reads = Set.empty, writes = Set(refB1))

    assertEquals(readOnlyA.conflictsWith(readOnlyB), false)
    assertEquals(writeSame.conflictsWith(writeSame), true)
    assertEquals(readOnlyA.conflictsWith(writeSame), true)
    assertEquals(writeSame.conflictsWith(writeOther), false)

  test("ConflictFootprint.conflictsWith is symmetric"):
    val left  = ConflictFootprint(reads = Set(refA1), writes = Set.empty)
    val right = ConflictFootprint(reads = Set.empty, writes = Set(refA1))

    assertEquals(left.conflictsWith(right), right.conflictsWith(left))

  test(
    "ConflictFootprint.conflictsWith stays symmetric for mixed read-write footprints",
  ):
    val left  = ConflictFootprint(reads = Set(refA1), writes = Set(refA2))
    val right = ConflictFootprint(reads = Set(refA2), writes = Set(refA1))

    assertEquals(left.conflictsWith(right), right.conflictsWith(left))

  test(
    "ConflictFootprint.conflictsWith treats empty footprints as non-conflicting",
  ):
    val footprint = ConflictFootprint(reads = Set(refA1), writes = Set(refA2))

    assertEquals(
      ConflictFootprint.empty.conflictsWith(ConflictFootprint.empty),
      false,
    )
    assertEquals(footprint.conflictsWith(ConflictFootprint.empty), false)
    assertEquals(ConflictFootprint.empty.conflictsWith(footprint), false)

  test(
    "ConflictFootprint.conflictsWith matches the aggregate verifier for two-item batches",
  ):
    val candidates = Vector(
      ConflictFootprint.empty,
      ConflictFootprint(reads = Set(refA1), writes = Set.empty),
      ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      ConflictFootprint(reads = Set(refA1), writes = Set(refA2)),
      ConflictFootprint(reads = Set(refB1), writes = Set.empty),
      ConflictFootprint(reads = Set.empty, writes = Set(refB1)),
    )

    candidates
      .combinations(2)
      .foreach:
        case Vector(left, right) =>
          val verifierResult =
            ConflictFootprintVerifier.verifyAll(
              Vector("left" -> left, "right" -> right),
            )
          assertEquals(left.conflictsWith(right), verifierResult.isLeft)
        case _ =>
          fail("expected two footprints per combination")

  test(
    "ConflictFootprint.fromAccessLog lifts full accessed keys into StateRef sets",
  ):
    val accessLog = AccessLog.empty
      .recordRead(prefixA, refA1.bytes)
      .recordWrite(prefixA, refA2.bytes)
      .recordWrite(prefixB, refB1.bytes)

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Right(
        ConflictFootprint(
          reads = Set(refA1),
          writes = Set(refA2, refB1),
        ),
      ),
    )

  test(
    "ConflictFootprint.fromAccessLog keeps the same key in both reads and writes when needed",
  ):
    val accessLog = AccessLog.empty
      .recordRead(prefixA, refA1.bytes)
      .recordWrite(prefixA, refA1.bytes)

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Right(
        ConflictFootprint(
          reads = Set(refA1),
          writes = Set(refA1),
        ),
      ),
    )

  test(
    "ConflictFootprint.fromAccessLog maps the empty log to the empty footprint",
  ):
    assertEquals(
      ConflictFootprint.fromAccessLog(AccessLog.empty),
      Right(ConflictFootprint.empty),
    )

  test(
    "ConflictFootprint.fromAccessLog preserves reads across multiple keys and prefixes",
  ):
    val accessLog = AccessLog.empty
      .recordRead(prefixA, refA1.bytes)
      .recordRead(prefixA, refA2.bytes)
      .recordRead(prefixB, refB1.bytes)

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Right(
        ConflictFootprint(
          reads = Set(refA1, refA2, refB1),
          writes = Set.empty,
        ),
      ),
    )

  test(
    "ConflictFootprint.fromAccessLog rejects keys that do not carry the recorded prefix",
  ):
    val malformedKey = refA1.bytes
    val accessLog = AccessLog(
      reads = Map(prefixB -> Set(malformedKey)),
      writes = Map.empty,
    )

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Left(
        ConflictFootprint.AccessLogInvariantViolation(
          tablePrefix = prefixB,
          key = malformedKey,
        ),
      ),
    )

  test(
    "ConflictFootprint.fromAccessLog rejects malformed keys in writes as well",
  ):
    val malformedKey = refA1.bytes
    val accessLog = AccessLog(
      reads = Map.empty,
      writes = Map(prefixB -> Set(malformedKey)),
    )

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Left(
        ConflictFootprint.AccessLogInvariantViolation(
          tablePrefix = prefixB,
          key = malformedKey,
        ),
      ),
    )

  test(
    "ConflictFootprint.fromAccessLog rejects the first malformed prefix deterministically",
  ):
    val malformedKey = refA1.bytes
    val accessLog = AccessLog(
      reads = Map(
        prefixA -> Set(refA1.bytes),
        prefixB -> Set(malformedKey),
      ),
      writes = Map.empty,
    )

    assertEquals(
      ConflictFootprint.fromAccessLog(accessLog),
      Left(
        ConflictFootprint.AccessLogInvariantViolation(
          tablePrefix = prefixB,
          key = malformedKey,
        ),
      ),
    )

  test(
    "ConflictFootprintVerifier accepts non-conflicting footprints regardless of scan order",
  ):
    val candidates = Vector(
      "tx-a" -> ConflictFootprint(reads = Set(refA1), writes = Set.empty),
      "tx-b" -> ConflictFootprint(reads = Set.empty, writes = Set(refA2)),
      "tx-c" -> ConflictFootprint(reads = Set(refB1), writes = Set.empty),
    )

    val results =
      candidates.permutations.map(ConflictFootprintVerifier.verifyAll).toVector

    assert(results.forall(_.isRight))
    assertEquals(
      results.flatMap(_.toOption).map(_.footprint).distinct,
      Vector(
        ConflictFootprint(
          reads = Set(refA1, refB1),
          writes = Set(refA2),
        ),
      ),
    )

  test("ConflictFootprintVerifier handles footprints that both read and write"):
    val candidates = Vector(
      "tx-a" -> ConflictFootprint(reads = Set(refA1), writes = Set(refA2)),
      "tx-b" -> ConflictFootprint(reads = Set(refB1), writes = Set.empty),
      "tx-c" -> ConflictFootprint(reads = Set.empty, writes = Set(refB2)),
    )

    val results =
      candidates.permutations.map(ConflictFootprintVerifier.verifyAll).toVector

    assert(results.forall(_.isRight))
    assertEquals(
      results.flatMap(_.toOption).map(_.footprint).distinct,
      Vector(
        ConflictFootprint(
          reads = Set(refA1, refB1),
          writes = Set(refA2, refB2),
        ),
      ),
    )

  test(
    "ConflictFootprintVerifier rejects conflicting footprints regardless of scan order",
  ):
    val conflicts = Vector(
      "tx-a" -> ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      "tx-b" -> ConflictFootprint(reads = Set(refA1), writes = Set.empty),
      "tx-c" -> ConflictFootprint(reads = Set.empty, writes = Set(refB1)),
    )

    val results =
      conflicts.permutations.map(ConflictFootprintVerifier.verifyAll).toVector

    assert(results.forall(_.isLeft))
    assertEquals(
      results.flatMap(_.left.toOption.map(_.kind)).toSet,
      Set(ConflictKind.ReadWrite),
    )
    assertEquals(
      results.flatMap(_.left.toOption.map(_.stateRef)).toSet,
      Set(refA1),
    )

  test(
    "ConflictFootprintVerifier rejects W∩W conflicts regardless of scan order",
  ):
    val conflicts = Vector(
      "tx-a" -> ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      "tx-b" -> ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      "tx-c" -> ConflictFootprint(reads = Set(refB1), writes = Set.empty),
    )

    val results =
      conflicts.permutations.map(ConflictFootprintVerifier.verifyAll).toVector

    assert(results.forall(_.isLeft))
    assertEquals(
      results.flatMap(_.left.toOption.map(_.kind)).toSet,
      Set(ConflictKind.WriteWrite),
    )
    assertEquals(
      results.flatMap(_.left.toOption.map(_.stateRef)).toSet,
      Set(refA1),
    )

  test(
    "AggregateFootprint.accept prefers W∩W when one candidate hits both conflict kinds",
  ):
    val conflicts = Vector(
      "tx-a" -> ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      "tx-b" -> ConflictFootprint(reads = Set(refA2), writes = Set(refA2)),
      "tx-c" -> ConflictFootprint(reads = Set(refA2), writes = Set(refA1)),
    )

    val aggregate =
      ConflictFootprintVerifier
        .verifyAll(conflicts.take(2))
        .toOption
        .get

    assertEquals(
      aggregate.accept(conflicts(2)._1, conflicts(2)._2).left.map(_.kind),
      Left(ConflictKind.WriteWrite),
    )

  test(
    "ConflictFootprintVerifier returns the empty aggregate for an empty batch",
  ):
    assertEquals(
      ConflictFootprintVerifier.verifyAll(
        Vector.empty[(String, ConflictFootprint)],
      ),
      Right(AggregateFootprint.empty),
    )

  test("ConflictFootprintVerifier accepts a single-item batch"):
    assertEquals(
      ConflictFootprintVerifier.verifyAll(
        Vector(
          "tx-a" -> ConflictFootprint(reads = Set(refA1), writes = Set(refA2)),
        ),
      ),
      Right(
        AggregateFootprint(
          readsSeen = Set(refA1),
          writesSeen = Set(refA2),
        ),
      ),
    )

  test(
    "ConflictFootprintVerifier accepts a single-item batch that both reads and writes one ref",
  ):
    assertEquals(
      ConflictFootprintVerifier.verifyAll(
        Vector(
          "tx-a" -> ConflictFootprint(reads = Set(refA1), writes = Set(refA1)),
        ),
      ),
      Right(
        AggregateFootprint(
          readsSeen = Set(refA1),
          writesSeen = Set(refA1),
        ),
      ),
    )

  test(
    "ConflictFootprintConformance accepts actual subsets of the declared footprint",
  ):
    val declared = ConflictFootprint(
      reads = Set(refA1, refA2),
      writes = Set(refB1),
    )
    val actual = ConflictFootprint(
      reads = Set(refA1),
      writes = Set(refB1),
    )

    assertEquals(
      ConflictFootprintConformance.validate(actual, declared),
      Right(()),
    )

  test("ConflictFootprintConformance accepts exact matches"):
    val footprint = ConflictFootprint(
      reads = Set(refA1, refB1),
      writes = Set(refA2, refB2),
    )

    assertEquals(
      ConflictFootprintConformance.validate(footprint, footprint),
      Right(()),
    )

  test("ConflictFootprintConformance reports unexpected reads and writes"):
    val declared = ConflictFootprint(
      reads = Set(refA1),
      writes = Set(refA2),
    )
    val actual = ConflictFootprint(
      reads = Set(refA1, refB1),
      writes = Set(refA2, refB1),
    )

    assertEquals(
      ConflictFootprintConformance.validate(actual, declared),
      Left(
        FootprintConformanceFailure(
          unexpectedReads = Set(refB1),
          unexpectedWrites = Set(refB1),
        ),
      ),
    )

  test(
    "ConflictFootprintConformance rejects reads that were only declared as writes",
  ):
    val declared = ConflictFootprint(
      reads = Set.empty,
      writes = Set(refA1),
    )
    val actual = ConflictFootprint(
      reads = Set(refA1),
      writes = Set(refA1),
    )

    assertEquals(
      ConflictFootprintConformance.validate(actual, declared),
      Left(
        FootprintConformanceFailure(
          unexpectedReads = Set(refA1),
          unexpectedWrites = Set.empty,
        ),
      ),
    )

  test(
    "ConflictFootprintConformance reports unexpected writes without unexpected reads",
  ):
    val declared = ConflictFootprint(
      reads = Set(refA1),
      writes = Set.empty,
    )
    val actual = ConflictFootprint(
      reads = Set(refA1),
      writes = Set(refA2),
    )

    assertEquals(
      ConflictFootprintConformance.validate(actual, declared),
      Left(
        FootprintConformanceFailure(
          unexpectedReads = Set.empty,
          unexpectedWrites = Set(refA2),
        ),
      ),
    )

  test(
    "ConflictFootprintConformance accepts empty actual footprints against declared supersets",
  ):
    assertEquals(
      ConflictFootprintConformance.validate(
        actual = ConflictFootprint.empty,
        declared = ConflictFootprint(
          reads = Set(refA1),
          writes = Set(refA2),
        ),
      ),
      Right(()),
    )

  test(
    "ConflictFootprintConformance accepts empty actual footprints against empty declarations",
  ):
    assertEquals(
      ConflictFootprintConformance.validate(
        actual = ConflictFootprint.empty,
        declared = ConflictFootprint.empty,
      ),
      Right(()),
    )

  test("FootprintDeriver provides a deterministic derivation seam"):
    given FootprintDeriver[String] = FootprintDeriver.instance:
      case "ok" =>
        Right(ConflictFootprint(reads = Set(refA1), writes = Set(refA2)))
      case other =>
        Left(
          FootprintDerivationFailure(
            reason = "unsupportedTx",
            detail = Some(other),
          ),
        )

    assertEquals(
      FootprintDeriver.derive("ok"),
      Right(ConflictFootprint(reads = Set(refA1), writes = Set(refA2))),
    )
    assertEquals(
      FootprintDeriver.derive("unsupported"),
      Left(
        FootprintDerivationFailure(
          reason = "unsupportedTx",
          detail = Some("unsupported"),
        ),
      ),
    )

  test(
    "FootprintDeriver.contramap reuses the underlying deterministic derivation",
  ):
    val base = FootprintDeriver.instance[String]:
      case "42" =>
        Right(ConflictFootprint(reads = Set(refA1), writes = Set.empty))
      case other =>
        Left(
          FootprintDerivationFailure(
            reason = "unsupportedTx",
            detail = Some(other),
          ),
        )
    val derived = base.contramap[Int](_.toString)

    assertEquals(
      derived.derive(42),
      Right(
        ConflictFootprint(
          reads = Set(refA1),
          writes = Set.empty,
        ),
      ),
    )

  test("FootprintDerivationFailure.withoutDetail clears the optional detail"):
    assertEquals(
      FootprintDerivationFailure.withoutDetail("unsupportedTx"),
      FootprintDerivationFailure(
        reason = "unsupportedTx",
        detail = None,
      ),
    )
