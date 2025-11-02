package org.sigilaris.core.application.state

import scodec.bits.ByteVector

/** Access log for tracking transaction reads and writes.
  *
  * Phase 8: AccessLog accumulates all read and write operations performed by a
  * transaction, enabling efficient conflict detection between concurrent
  * transactions.
  *
  * The log maps table identifiers (byte prefixes) to sets of accessed keys
  * (full byte keys including prefix). This byte-level approach ensures correct
  * conflict detection across different table types.
  *
  * ADR-0009 Phase 8 requirements:
  *   - Store table identifier (path + table name encoded as bytes)
  *   - Store accessed keys (full key bytes including table prefix)
  *   - Support conflict detection predicates (W∩W, R∩W)
  *   - Provide size control options for memory management
  *
  * @param reads
  *   map from table prefix to set of read key bytes
  * @param writes
  *   map from table prefix to set of written key bytes
  */
final case class AccessLog(
    reads: Map[ByteVector, Set[ByteVector]],
    writes: Map[ByteVector, Set[ByteVector]],
):
  /** Combine this log with another, preserving all accesses. */
  def combine(other: AccessLog): AccessLog =
    AccessLog(
      reads = AccessLog.mergeMaps(reads, other.reads),
      writes = AccessLog.mergeMaps(writes, other.writes),
    )

  /** Record a read operation.
    *
    * @param tablePrefix
    *   the table's byte prefix (encodePath(Path) ++ encodeSegment(Name))
    * @param key
    *   the full key bytes (tablePrefix ++ encodeKey(k))
    */
  def recordRead(tablePrefix: ByteVector, key: ByteVector): AccessLog =
    copy(reads = AccessLog.addKey(reads, tablePrefix, key))

  /** Record a write operation.
    *
    * @param tablePrefix
    *   the table's byte prefix (encodePath(Path) ++ encodeSegment(Name))
    * @param key
    *   the full key bytes (tablePrefix ++ encodeKey(k))
    */
  def recordWrite(tablePrefix: ByteVector, key: ByteVector): AccessLog =
    copy(writes = AccessLog.addKey(writes, tablePrefix, key))

  /** Check if this log conflicts with another.
    *
    * Conflict detection (ADR-0009 Phase 8):
    *   - W∩W conflict: both logs write to the same key
    *   - R∩W conflict: one reads while the other writes to the same key
    *
    * @param other
    *   the other access log
    * @return
    *   true if there is a conflict, false otherwise
    */
  def conflictsWith(other: AccessLog): Boolean =
    // W∩W: our writes intersect their writes
    val writeWriteConflict = AccessLog.hasIntersection(writes, other.writes)

    // R∩W: our reads intersect their writes, or our writes intersect their reads
    val readWriteConflict = AccessLog.hasIntersection(reads, other.writes) ||
      AccessLog.hasIntersection(writes, other.reads)

    writeWriteConflict || readWriteConflict

  /** Get total number of read operations across all tables. */
  def readCount: Int = reads.values.map(_.size).sum

  /** Get total number of write operations across all tables. */
  def writeCount: Int = writes.values.map(_.size).sum

  /** Check if this log exceeds size limits.
    *
    * Phase 8 size control: prevent unbounded memory growth by capping the
    * number of tracked operations.
    *
    * @param maxReads
    *   maximum allowed read operations
    * @param maxWrites
    *   maximum allowed write operations
    * @return
    *   true if limits are exceeded
    */
  def exceedsLimits(maxReads: Int, maxWrites: Int): Boolean =
    readCount > maxReads || writeCount > maxWrites

object AccessLog:
  /** Empty access log with no recorded operations. */
  val empty: AccessLog = AccessLog(Map.empty, Map.empty)

  /** Merge two maps by combining their value sets.
    *
    * @param m1
    *   first map
    * @param m2
    *   second map
    * @return
    *   merged map with union of value sets
    */
  private def mergeMaps(
      m1: Map[ByteVector, Set[ByteVector]],
      m2: Map[ByteVector, Set[ByteVector]],
  ): Map[ByteVector, Set[ByteVector]] =
    m2.foldLeft(m1): (acc, kv) =>
      val (prefix, keys) = kv
      acc.updated(prefix, acc.getOrElse(prefix, Set.empty) ++ keys)

  /** Add a key to a map's set.
    *
    * @param m
    *   the map
    * @param prefix
    *   the table prefix
    * @param key
    *   the key to add
    * @return
    *   updated map
    */
  private def addKey(
      m: Map[ByteVector, Set[ByteVector]],
      prefix: ByteVector,
      key: ByteVector,
  ): Map[ByteVector, Set[ByteVector]] =
    m.updated(prefix, m.getOrElse(prefix, Set.empty) + key)

  /** Check if two access maps have any key intersection.
    *
    * @param m1
    *   first access map
    * @param m2
    *   second access map
    * @return
    *   true if any table prefix has overlapping keys
    */
  private def hasIntersection(
      m1: Map[ByteVector, Set[ByteVector]],
      m2: Map[ByteVector, Set[ByteVector]],
  ): Boolean =
    m1.exists: (prefix, keys1) =>
      m2.get(prefix).exists: keys2 =>
        keys1.exists(keys2.contains)
