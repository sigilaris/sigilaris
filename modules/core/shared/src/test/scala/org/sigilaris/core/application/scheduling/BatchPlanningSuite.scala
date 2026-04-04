package org.sigilaris.core.application.scheduling

import scala.collection.mutable.ArrayBuffer

import munit.FunSuite
import scodec.bits.ByteVector

import org.sigilaris.core.application.execution.TxExecution
import org.sigilaris.core.application.state.{AccessLog, StoreState}
import org.sigilaris.core.crypto.Hash
import org.sigilaris.core.datatype.UInt256
import org.sigilaris.core.merkle.{MerkleTrieNode, MerkleTrieState}

final class BatchPlanningSuite extends FunSuite:

  private val prefixA = ByteVector.fromValidHex("01")
  private val prefixB = ByteVector.fromValidHex("02")
  private val keyA1   = ByteVector.fromValidHex("aa")
  private val keyA2   = ByteVector.fromValidHex("ab")
  private val keyB1   = ByteVector.fromValidHex("ba")

  private val refA1 = StateRef.tableKey(prefixA, keyA1)
  private val refA2 = StateRef.tableKey(prefixA, keyA2)
  private val refB1 = StateRef.tableKey(prefixB, keyB1)
  private val tablePrefixesByRef = Map(
    refA1 -> prefixA,
    refA2 -> prefixA,
    refB1 -> prefixB,
  )

  private final case class Candidate(
      id: String,
      derived: Either[FootprintDerivationFailure, ConflictFootprint],
      actual: Either[
        ConflictFootprint.AccessLogInvariantViolation,
        ConflictFootprint,
      ],
  )

  private given FootprintDeriver[Candidate] = FootprintDeriver.instance(_.derived)

  private def schedulableCandidate(
      id: String,
      declared: ConflictFootprint,
  ): Candidate =
    Candidate(
      id = id,
      derived = Right(declared),
      actual = Right(declared),
    )

  private def schedulableCandidate(
      id: String,
      declared: ConflictFootprint,
      actual: Either[
        ConflictFootprint.AccessLogInvariantViolation,
        ConflictFootprint,
      ],
  ): Candidate =
    Candidate(
      id = id,
      derived = Right(declared),
      actual = actual,
    )

  private def compatibilityCandidate(
      id: String,
      reason: String,
      detail: Option[String] = None,
  ): Candidate =
    Candidate(
      id = id,
      derived = Left(FootprintDerivationFailure(reason = reason, detail = detail)),
      actual = Right(ConflictFootprint.empty),
    )

  private def accessLogFor(
      footprint: ConflictFootprint,
  ): AccessLog =
    val withReads = footprint.reads.foldLeft(AccessLog.empty): (acc, stateRef) =>
      acc.recordRead(tablePrefixesByRef(stateRef), stateRef.bytes)
    footprint.writes.foldLeft(withReads): (acc, stateRef) =>
      acc.recordWrite(tablePrefixesByRef(stateRef), stateRef.bytes)

  private def executionFor(
      actual: Either[
        ConflictFootprint.AccessLogInvariantViolation,
        ConflictFootprint,
      ],
      nextTrieState: MerkleTrieState,
  ): TxExecution[Unit, Nothing] =
    actual match
      case Right(footprint) =>
        TxExecution(
          nextTrieState = nextTrieState,
          actualAccessLog = accessLogFor(footprint),
          actualFootprint = Right(footprint),
          result = (),
          events = Nil,
        )
      case Left(violation) =>
        TxExecution(
          nextTrieState = nextTrieState,
          actualAccessLog = AccessLog.empty.recordWrite(violation.tablePrefix, violation.key),
          actualFootprint = Left(violation),
          result = (),
          events = Nil,
        )

  private def trieState(
      marker: Int,
  ): MerkleTrieState =
    MerkleTrieState.fromRoot(
      Hash.Value[MerkleTrieNode](
        UInt256.unsafeFromBytesBE(ByteVector.fill(32)(marker)),
      )
    )

  private def executionFor(
      state: StoreState,
      actual: Either[
        ConflictFootprint.AccessLogInvariantViolation,
        ConflictFootprint,
      ],
  ): TxExecution[Unit, Nothing] =
    executionFor(
      actual = actual,
      nextTrieState = state.trieState,
    )

  private def expectSchedulable(
      result: Either[FootprintConflict[Candidate], BatchPlan[Candidate]],
  ): SchedulableBatchPlan[Candidate] =
    result match
      case Right(BatchPlan.Schedulable(plan)) =>
        plan
      case other =>
        fail(s"Expected schedulable plan, got: $other")

  test("BatchPlanner produces a schedulable plan for an all-schedulable conflict-free batch"):
    val candidates = Vector(
      schedulableCandidate(
        "tx-a",
        ConflictFootprint(reads = Set(refA1), writes = Set.empty),
      ),
      schedulableCandidate(
        "tx-b",
        ConflictFootprint(reads = Set.empty, writes = Set(refB1)),
      ),
    )

    val plan = expectSchedulable(BatchPlanner.planWithDeriver(candidates))

    assertEquals(plan.items.map(_.item.id), Vector("tx-a", "tx-b"))
    assertEquals(
      plan.aggregate.footprint,
      ConflictFootprint(
        reads = Set(refA1),
        writes = Set(refB1),
      ),
    )

  test("BatchPlanner rejects conflicting all-schedulable batches before execution"):
    val result = BatchPlanner.planWithDeriver(
      Vector(
        schedulableCandidate(
          "tx-a",
          ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
        ),
        schedulableCandidate(
          "tx-b",
          ConflictFootprint(reads = Set(refA1), writes = Set.empty),
        ),
      ),
    )

    assertEquals(
      result.left.map(conflict => (conflict.item.id, conflict.kind, conflict.stateRef)),
      Left(("tx-b", ConflictKind.ReadWrite, refA1)),
    )

  test("BatchPlanner rejects W∩W conflicts before execution"):
    val result = BatchPlanner.planWithDeriver(
      Vector(
        schedulableCandidate(
          "tx-a",
          ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
        ),
        schedulableCandidate(
          "tx-b",
          ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
        ),
      ),
    )

    assertEquals(
      result.left.map(conflict => (conflict.item.id, conflict.kind, conflict.stateRef)),
      Left(("tx-b", ConflictKind.WriteWrite, refA1)),
    )

  test("BatchPlanner rejects read-before-write conflicts before execution"):
    val result = BatchPlanner.planWithDeriver(
      Vector(
        schedulableCandidate(
          "tx-a",
          ConflictFootprint(reads = Set(refA1), writes = Set.empty),
        ),
        schedulableCandidate(
          "tx-b",
          ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
        ),
      ),
    )

    assertEquals(
      result.left.map(conflict => (conflict.item.id, conflict.kind, conflict.stateRef)),
      Left(("tx-b", ConflictKind.ReadWrite, refA1)),
    )

  test("BatchPlanner routes mixed batches to compatibility mode without partially scheduling a subset"):
    val result = BatchPlanner.planWithDeriver(
      Vector(
        schedulableCandidate(
          "tx-a",
          ConflictFootprint(reads = Set(refA1), writes = Set.empty),
        ),
        compatibilityCandidate(
          "tx-b",
          reason = "dynamicDiscovery",
          detail = Some("prefix scan"),
        ),
      ),
    )

    result match
      case Right(BatchPlan.Compatibility(plan)) =>
        assertEquals(plan.mode, CompatibilityMode.MixedBatch)
        assertEquals(plan.items.size, 2)
        assertEquals(
          plan.items.map(_.classification),
          Vector(
            SchedulingClassification.Schedulable(
              ConflictFootprint(reads = Set(refA1), writes = Set.empty),
            ),
            SchedulingClassification.Compatibility(
              CompatibilityReason(
                reason = "dynamicDiscovery",
                detail = Some("prefix scan"),
              ),
            ),
          ),
        )
      case other =>
        fail(s"Expected compatibility fallback plan, got: $other")

  test("BatchPlanner routes compatibility-only batches to compatibility mode"):
    val result = BatchPlanner.planWithDeriver(
      Vector(
        compatibilityCandidate("tx-a", "dynamicDiscovery"),
        compatibilityCandidate("tx-b", "automaticInputSelection"),
      ),
    )

    result match
      case Right(BatchPlan.Compatibility(plan)) =>
        assertEquals(plan.mode, CompatibilityMode.CompatibilityOnly)
        assert(plan.items.forall(_.schedulable.isEmpty))
      case other =>
        fail(s"Expected compatibility plan, got: $other")

  test("BatchPlanner treats the empty batch as an empty schedulable plan"):
    val result = BatchPlanner.planWithDeriver(Vector.empty[Candidate])

    result match
      case Right(BatchPlan.Schedulable(plan)) =>
        assertEquals(plan.items, Vector.empty)
        assertEquals(plan.aggregate, AggregateFootprint.empty)
      case other =>
        fail(s"Expected empty schedulable plan, got: $other")

  test("SchedulableBatchExecutor chains via nextState so trie state and witness logs propagate correctly"):
    val plan = expectSchedulable(
      BatchPlanner.planWithDeriver(
        Vector(
          schedulableCandidate(
            "tx-a",
            ConflictFootprint(reads = Set(refA1), writes = Set.empty),
          ),
          schedulableCandidate(
            "tx-b",
            ConflictFootprint(reads = Set.empty, writes = Set(refA2)),
          ),
        ),
      ),
    )
    val seenLogs = ArrayBuffer.empty[AccessLog]
    val seenTrieStates = ArrayBuffer.empty[MerkleTrieState]
    val nextTrieStates = Map(
      "tx-a" -> trieState(1),
      "tx-b" -> trieState(2),
    )

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, candidate) =>
          seenLogs += state.accessLog
          seenTrieStates += state.trieState
          Right(
            executionFor(
              candidate.actual,
              nextTrieState = nextTrieStates(candidate.id),
            )
          )

    assert(result.isRight)
    assertEquals(seenLogs.toVector, Vector(AccessLog.empty, AccessLog.empty))
    assertEquals(
      seenTrieStates.toVector,
      Vector(StoreState.empty.trieState, nextTrieStates("tx-a")),
    )
    assertEquals(result.toOption.get.nextState.accessLog, AccessLog.empty)
    assertEquals(result.toOption.get.nextState.trieState, nextTrieStates("tx-b"))
    assertEquals(
      result.toOption.get.items.map(_.planned.item.id),
      Vector("tx-a", "tx-b"),
    )

  test("SchedulableBatchExecutor rejects actual accesses outside the declared footprint"):
    val declared = ConflictFootprint(
      reads = Set(refA1),
      writes = Set.empty,
    )
    val actual = ConflictFootprint(
      reads = Set(refA1, refB1),
      writes = Set.empty,
    )
    val candidate = schedulableCandidate("tx-a", declared, Right(actual))
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(candidate)))

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          Right(executionFor(state, scheduled.actual))

    result match
      case Left(SchedulableExecutionFailure.ConformanceFailed(item, failure)) =>
        assertEquals(item.id, "tx-a")
        assertEquals(
          failure,
          FootprintConformanceFailure(
            unexpectedReads = Set(refB1),
            unexpectedWrites = Set.empty,
          ),
        )
      case other =>
        fail(s"Expected conformance failure, got: $other")

  test("SchedulableBatchExecutor accepts actual footprints that are strict subsets of the declaration"):
    val declared = ConflictFootprint(
      reads = Set(refA1, refB1),
      writes = Set(refA2),
    )
    val actual = ConflictFootprint(
      reads = Set(refA1),
      writes = Set.empty,
    )
    val candidate = schedulableCandidate("tx-a", declared, Right(actual))
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(candidate)))

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          Right(executionFor(state, scheduled.actual))

    assert(result.isRight)
    assertEquals(result.toOption.get.items.map(_.planned.item.id), Vector("tx-a"))

  test("SchedulableBatchExecutor rejects unexpected writes outside the declared footprint"):
    val declared = ConflictFootprint(
      reads = Set.empty,
      writes = Set(refA1),
    )
    val actual = ConflictFootprint(
      reads = Set.empty,
      writes = Set(refA1, refB1),
    )
    val candidate = schedulableCandidate("tx-a", declared, Right(actual))
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(candidate)))

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          Right(executionFor(state, scheduled.actual))

    result match
      case Left(SchedulableExecutionFailure.ConformanceFailed(item, failure)) =>
        assertEquals(item.id, "tx-a")
        assertEquals(
          failure,
          FootprintConformanceFailure(
            unexpectedReads = Set.empty,
            unexpectedWrites = Set(refB1),
          ),
        )
      case other =>
        fail(s"Expected write-side conformance failure, got: $other")

  test("SchedulableBatchExecutor stops on a second-item failure and attributes it to the failing item"):
    val first = schedulableCandidate(
      "tx-a",
      ConflictFootprint(reads = Set(refA1), writes = Set.empty),
    )
    val second = schedulableCandidate(
      "tx-b",
      declared = ConflictFootprint(reads = Set(refB1), writes = Set.empty),
      actual = Right(
        ConflictFootprint(reads = Set(refA2, refB1), writes = Set.empty),
      ),
    )
    val third = schedulableCandidate(
      "tx-c",
      ConflictFootprint(reads = Set.empty, writes = Set(refA2)),
    )
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(first, second, third)))
    val executedIds = ArrayBuffer.empty[String]

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          executedIds += scheduled.id
          Right(executionFor(state, scheduled.actual))

    assertEquals(executedIds.toVector, Vector("tx-a", "tx-b"))
    result match
      case Left(SchedulableExecutionFailure.ConformanceFailed(item, failure)) =>
        assertEquals(item.id, "tx-b")
        assertEquals(
          failure,
          FootprintConformanceFailure(
            unexpectedReads = Set(refA2),
            unexpectedWrites = Set.empty,
          ),
        )
      case other =>
        fail(s"Expected second-item conformance failure, got: $other")

  test("SchedulableBatchExecutor stops on a second-item unexpected-write failure"):
    val first = schedulableCandidate(
      "tx-a",
      ConflictFootprint(reads = Set(refA1), writes = Set.empty),
    )
    val second = schedulableCandidate(
      "tx-b",
      declared = ConflictFootprint(reads = Set.empty, writes = Set(refB1)),
      actual = Right(
        ConflictFootprint(reads = Set.empty, writes = Set(refA2, refB1)),
      ),
    )
    val third = schedulableCandidate(
      "tx-c",
      ConflictFootprint(reads = Set.empty, writes = Set(refA2)),
    )
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(first, second, third)))
    val executedIds = ArrayBuffer.empty[String]

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          executedIds += scheduled.id
          Right(executionFor(state, scheduled.actual))

    assertEquals(executedIds.toVector, Vector("tx-a", "tx-b"))
    result match
      case Left(SchedulableExecutionFailure.ConformanceFailed(item, failure)) =>
        assertEquals(item.id, "tx-b")
        assertEquals(
          failure,
          FootprintConformanceFailure(
            unexpectedReads = Set.empty,
            unexpectedWrites = Set(refA2),
          ),
        )
      case other =>
        fail(s"Expected second-item write-side conformance failure, got: $other")

  test("SchedulableBatchExecutor surfaces actual-footprint invariant violations"):
    val violation = ConflictFootprint.AccessLogInvariantViolation(
      tablePrefix = prefixB,
      key = refA1.bytes,
    )
    val candidate = schedulableCandidate(
      "tx-a",
      declared = ConflictFootprint(reads = Set.empty, writes = Set(refA1)),
      actual = Left(violation),
    )
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(candidate)))

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          Right(executionFor(state, scheduled.actual))

    result match
      case Left(SchedulableExecutionFailure.ActualFootprintUnavailable(item, actualViolation)) =>
        assertEquals(item.id, "tx-a")
        assertEquals(actualViolation, violation)
      case other =>
        fail(s"Expected invariant violation, got: $other")

  test("SchedulableBatchExecutor propagates execution failures without masking the item"):
    val candidate = schedulableCandidate(
      "tx-a",
      declared = ConflictFootprint(reads = Set(refA1), writes = Set.empty),
    )
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(candidate)))

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (_, scheduled) =>
          Left(s"boom:${scheduled.id}")

    result match
      case Left(SchedulableExecutionFailure.ExecutionFailed(item, cause)) =>
        assertEquals(item.id, "tx-a")
        assertEquals(cause, "boom:tx-a")
      case other =>
        fail(s"Expected execution failure, got: $other")

  test("SchedulableBatchExecutor stops on a second-item execution callback failure"):
    val first = schedulableCandidate(
      "tx-a",
      ConflictFootprint(reads = Set(refA1), writes = Set.empty),
    )
    val second = schedulableCandidate(
      "tx-b",
      ConflictFootprint(reads = Set.empty, writes = Set(refB1)),
    )
    val third = schedulableCandidate(
      "tx-c",
      ConflictFootprint(reads = Set.empty, writes = Set(refA2)),
    )
    val plan = expectSchedulable(BatchPlanner.planWithDeriver(Vector(first, second, third)))
    val executedIds = ArrayBuffer.empty[String]

    val result =
      SchedulableBatchExecutor.executeSequentially(StoreState.empty, plan):
        (state, scheduled) =>
          executedIds += scheduled.id
          if scheduled.id == "tx-b" then Left("boom:tx-b")
          else Right(executionFor(state, scheduled.actual))

    assertEquals(executedIds.toVector, Vector("tx-a", "tx-b"))
    result match
      case Left(SchedulableExecutionFailure.ExecutionFailed(item, cause)) =>
        assertEquals(item.id, "tx-b")
        assertEquals(cause, "boom:tx-b")
      case other =>
        fail(s"Expected second-item execution failure, got: $other")
