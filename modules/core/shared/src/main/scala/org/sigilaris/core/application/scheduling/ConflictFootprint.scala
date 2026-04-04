package org.sigilaris.core.application.scheduling

import scodec.bits.ByteVector

import org.sigilaris.core.application.state.AccessLog

final case class ConflictFootprint(
    reads: Set[StateRef],
    writes: Set[StateRef],
):
  def combine(
      other: ConflictFootprint,
  ): ConflictFootprint =
    ConflictFootprint(
      reads = reads ++ other.reads,
      writes = writes ++ other.writes,
    )

  def conflictsWith(
      other: ConflictFootprint,
  ): Boolean =
    AggregateFootprint(
      readsSeen = reads,
      writesSeen = writes,
    ).accept((), other).isLeft

  def unexpectedAgainst(
      declared: ConflictFootprint,
  ): ConflictFootprint =
    ConflictFootprint(
      reads = reads.diff(declared.reads),
      writes = writes.diff(declared.writes),
    )

object ConflictFootprint:
  final case class AccessLogInvariantViolation(
      tablePrefix: ByteVector,
      key: ByteVector,
  )

  val empty: ConflictFootprint =
    ConflictFootprint(
      reads = Set.empty,
      writes = Set.empty,
    )

  def fromAccessLog(
      accessLog: AccessLog,
  ): Either[AccessLogInvariantViolation, ConflictFootprint] =
    for
      reads <- flatten(accessLog.reads)
      writes <- flatten(accessLog.writes)
    yield ConflictFootprint(reads = reads, writes = writes)

  private def flatten(
      entries: Map[ByteVector, Set[ByteVector]],
  ): Either[AccessLogInvariantViolation, Set[StateRef]] =
    // AccessLog stores full key bytes in the value sets, so the map key is only
    // grouping metadata and must not be re-applied here. Correctness therefore
    // depends on the AccessLog invariant that each stored key already carries
    // the same prefix as the map entry it was recorded under.
    val initial: Either[AccessLogInvariantViolation, Set[StateRef]] =
      scala.Right[AccessLogInvariantViolation, Set[StateRef]](Set.empty[StateRef])
    entries.toVector.sortBy(_._1.toHex).foldLeft(initial):
      case (Right(acc), (tablePrefix, keys)) =>
        val perPrefixInitial: Either[AccessLogInvariantViolation, Set[StateRef]] =
          scala.Right[AccessLogInvariantViolation, Set[StateRef]](acc)
        keys.toVector.sortBy(_.toHex).foldLeft(perPrefixInitial):
          case (Right(current), key) =>
            Either.cond(
              key.startsWith(tablePrefix),
              current + StateRef.fromBytes(key),
              AccessLogInvariantViolation(
                tablePrefix = tablePrefix,
                key = key,
              ),
            )
          case (left @ Left(_), _) =>
            left
      case (left @ Left(_), _) =>
        left
