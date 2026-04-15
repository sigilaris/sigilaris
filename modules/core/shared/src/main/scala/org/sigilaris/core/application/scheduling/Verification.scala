package org.sigilaris.core.application.scheduling

/** The kind of state conflict detected between two footprints. */
enum ConflictKind:
  /** Both footprints write to the same state reference. */
  case WriteWrite
  /** One footprint reads and the other writes to the same state reference. */
  case ReadWrite

/** Accumulated reads and writes seen so far during batch conflict checking.
  *
  * @param readsSeen all state references read by previously accepted items
  * @param writesSeen all state references written by previously accepted items
  */
final case class AggregateFootprint(
    readsSeen: Set[StateRef],
    writesSeen: Set[StateRef],
):
  /** Attempts to accept a new item's footprint into the aggregate, failing on conflict.
    *
    * @tparam A the item type
    * @param item the item being accepted (used in conflict reporting)
    * @param footprint the item's conflict footprint
    * @return either a conflict or the updated aggregate
    */
  def accept[A](
      item: A,
      footprint: ConflictFootprint,
  ): Either[FootprintConflict[A], AggregateFootprint] =
    writeWriteConflict(footprint)
      .map: stateRef =>
        FootprintConflict(
          item = item,
          stateRef = stateRef,
          kind = ConflictKind.WriteWrite,
        )
      .orElse:
        readWriteConflict(footprint).map: stateRef =>
          FootprintConflict(
            item = item,
            stateRef = stateRef,
            kind = ConflictKind.ReadWrite,
          )
      .toLeft:
        AggregateFootprint(
          readsSeen = readsSeen ++ footprint.reads,
          writesSeen = writesSeen ++ footprint.writes,
        )

  /** Converts this aggregate back to a ConflictFootprint.
    *
    * @return the accumulated footprint
    */
  def footprint: ConflictFootprint =
    ConflictFootprint(reads = readsSeen, writes = writesSeen)

  private def writeWriteConflict(
      footprint: ConflictFootprint,
  ): Option[StateRef] =
    firstStateRef(footprint.writes.intersect(writesSeen))

  private def readWriteConflict(
      footprint: ConflictFootprint,
  ): Option[StateRef] =
    // TODO Phase 3+: split read-after-write vs write-after-read diagnostics if
    // downstream admission/reporting needs direction-specific errors.
    firstStateRef(
      footprint.reads.intersect(writesSeen) ++
        footprint.writes.intersect(readsSeen),
    )

  private def firstStateRef(
      refs: Set[StateRef],
  ): Option[StateRef] =
    refs.reduceOption: (left, right) =>
      if compareStateRefs(left, right) <= 0 then left else right

  private def compareStateRefs(
      left: StateRef,
      right: StateRef,
  ): Int =
    // Keep the comparator local so `StateRef` ordering stays explicit even
    // though scodec does not provide a project-wide Ordering[ByteVector] here.
    val leftBytes  = left.bytes.toArray
    val rightBytes = right.bytes.toArray
    val sharedSize = math.min(leftBytes.length, rightBytes.length)

    @annotation.tailrec
    def loop(index: Int): Int =
      if index >= sharedSize then
        java.lang.Integer.compare(leftBytes.length, rightBytes.length)
      else
        val leftByte  = leftBytes(index) & 0xff
        val rightByte = rightBytes(index) & 0xff
        val compared  = java.lang.Integer.compare(leftByte, rightByte)
        if compared != 0 then compared
        else loop(index + 1)

    loop(0)

/** Companion for [[AggregateFootprint]]. */
object AggregateFootprint:
  /** An empty aggregate with no reads or writes seen. */
  val empty: AggregateFootprint =
    AggregateFootprint(
      readsSeen = Set.empty,
      writesSeen = Set.empty,
    )

/** Describes a detected conflict between a new item's footprint and the aggregate.
  *
  * @tparam A the item type
  * @param item the later-processed item that failed admission
  * @param stateRef the state reference where the conflict occurred
  * @param kind the kind of conflict (write-write or read-write)
  */
final case class FootprintConflict[A](
    item: A,
    stateRef: StateRef,
    kind: ConflictKind,
)

/** Verifies that a collection of footprints are mutually conflict-free. */
object ConflictFootprintVerifier:
  /** Verifies all items are conflict-free by sequentially accepting each into an aggregate.
    *
    * @tparam A the item type
    * @param items the items with their footprints to verify
    * @return either the first conflict found or the final aggregate footprint
    */
  def verifyAll[A](
      items: Iterable[(A, ConflictFootprint)],
  ): Either[FootprintConflict[A], AggregateFootprint] =
    @annotation.tailrec
    def loop(
        remaining: Iterator[(A, ConflictFootprint)],
        aggregate: AggregateFootprint,
    ): Either[FootprintConflict[A], AggregateFootprint] =
      if !remaining.hasNext then
        scala.Right[FootprintConflict[A], AggregateFootprint](aggregate)
      else
        val (item, footprint) = remaining.next()
        aggregate.accept(item, footprint) match
          case Right(updated) =>
            loop(remaining, updated)
          case left @ Left(_) =>
            left

    loop(items.iterator, AggregateFootprint.empty)

/** Failure indicating the actual footprint exceeded the declared footprint.
  *
  * @param unexpectedReads state references read that were not declared
  * @param unexpectedWrites state references written that were not declared
  */
final case class FootprintConformanceFailure(
    unexpectedReads: Set[StateRef],
    unexpectedWrites: Set[StateRef],
):
  /** Returns true if there are no unexpected reads or writes.
    *
    * @return true if this failure is empty (no violations)
    */
  def isEmpty: Boolean =
    unexpectedReads.isEmpty && unexpectedWrites.isEmpty

/** Validates that an actual footprint conforms to (is a subset of) a declared footprint. */
object ConflictFootprintConformance:
  /** Validates actual reads/writes are subsets of declared reads/writes.
    *
    * @param actual the actual footprint observed during execution
    * @param declared the declared footprint from the scheduling phase
    * @return either a conformance failure or unit on success
    */
  def validate(
      actual: ConflictFootprint,
      declared: ConflictFootprint,
  ): Either[FootprintConformanceFailure, Unit] =
    // ADR-0020 keeps read and write conformance separate: schedulable execution
    // must witness actualReads ⊆ declaredReads and actualWrites ⊆ declaredWrites.
    val unexpected = actual.unexpectedAgainst(declared)
    val failure =
      FootprintConformanceFailure(
        unexpectedReads = unexpected.reads,
        unexpectedWrites = unexpected.writes,
      )
    Either.cond(
      failure.isEmpty,
      (),
      failure,
    )
