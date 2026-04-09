package org.sigilaris.core.application.scheduling

enum ConflictKind:
  case WriteWrite
  case ReadWrite

final case class AggregateFootprint(
    readsSeen: Set[StateRef],
    writesSeen: Set[StateRef],
):
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

object AggregateFootprint:
  val empty: AggregateFootprint =
    AggregateFootprint(
      readsSeen = Set.empty,
      writesSeen = Set.empty,
    )

final case class FootprintConflict[A](
    // `item` is the later-processed footprint that failed admission against the
    // previously accumulated aggregate.
    item: A,
    stateRef: StateRef,
    kind: ConflictKind,
)

object ConflictFootprintVerifier:
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

final case class FootprintConformanceFailure(
    unexpectedReads: Set[StateRef],
    unexpectedWrites: Set[StateRef],
):
  def isEmpty: Boolean =
    unexpectedReads.isEmpty && unexpectedWrites.isEmpty

object ConflictFootprintConformance:
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
