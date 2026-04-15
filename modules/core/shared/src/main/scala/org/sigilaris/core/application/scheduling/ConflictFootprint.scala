package org.sigilaris.core.application.scheduling

import scodec.bits.ByteVector

import org.sigilaris.core.application.state.AccessLog

/** Describes the set of state references a transaction reads from and writes to.
  *
  * Used by the batch scheduler to detect read-write and write-write conflicts
  * between transactions within a batch.
  *
  * @param reads the state references read by the transaction
  * @param writes the state references written by the transaction
  */
final case class ConflictFootprint(
    reads: Set[StateRef],
    writes: Set[StateRef],
):
  /** Combines this footprint with another by unioning reads and writes.
    *
    * @param other the other footprint
    * @return the combined footprint
    */
  def combine(
      other: ConflictFootprint,
  ): ConflictFootprint =
    ConflictFootprint(
      reads = reads ++ other.reads,
      writes = writes ++ other.writes,
    )

  /** Tests whether this footprint conflicts with another.
    *
    * @param other the other footprint to test against
    * @return true if there is a read-write or write-write overlap
    */
  def conflictsWith(
      other: ConflictFootprint,
  ): Boolean =
    AggregateFootprint(
      readsSeen = reads,
      writesSeen = writes,
    ).accept((), other).isLeft

  /** Computes the reads and writes in this footprint that are not in the declared footprint.
    *
    * @param declared the declared footprint to compare against
    * @return a footprint containing only the unexpected state references
    */
  def unexpectedAgainst(
      declared: ConflictFootprint,
  ): ConflictFootprint =
    ConflictFootprint(
      reads = reads.diff(declared.reads),
      writes = writes.diff(declared.writes),
    )

/** Companion for [[ConflictFootprint]], providing construction from access logs. */
object ConflictFootprint:
  /** Indicates an access log entry where the stored key does not have the expected table prefix.
    *
    * @param tablePrefix the expected table prefix
    * @param key the actual key that violates the invariant
    */
  final case class AccessLogInvariantViolation(
      tablePrefix: ByteVector,
      key: ByteVector,
  )

  /** An empty footprint with no reads or writes. */
  val empty: ConflictFootprint =
    ConflictFootprint(
      reads = Set.empty,
      writes = Set.empty,
    )

  /** Constructs a ConflictFootprint from an access log, validating the prefix invariant.
    *
    * @param accessLog the access log recorded during transaction execution
    * @return either an invariant violation or the derived footprint
    */
  def fromAccessLog(
      accessLog: AccessLog,
  ): Either[AccessLogInvariantViolation, ConflictFootprint] =
    for
      reads  <- flatten(accessLog.reads)
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
      scala.Right[AccessLogInvariantViolation, Set[StateRef]]:
        Set.empty[StateRef]
    entries.toVector
      .sortBy(_._1.toHex)
      .foldLeft(initial):
        case (Right(acc), (tablePrefix, keys)) =>
          val perPrefixInitial
              : Either[AccessLogInvariantViolation, Set[StateRef]] =
            scala.Right[AccessLogInvariantViolation, Set[StateRef]](acc)
          keys.toVector
            .sortBy(_.toHex)
            .foldLeft(perPrefixInitial):
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
