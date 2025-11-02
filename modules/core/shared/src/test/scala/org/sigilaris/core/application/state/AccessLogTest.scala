package org.sigilaris.core.application.state

import munit.FunSuite
import scodec.bits.ByteVector

class AccessLogTest extends FunSuite:

  // Test fixtures
  val prefix1: ByteVector = ByteVector.fromValidHex("01")
  val prefix2: ByteVector = ByteVector.fromValidHex("02")
  val key1: ByteVector = ByteVector.fromValidHex("0101")
  val key2: ByteVector = ByteVector.fromValidHex("0102")
  val key3: ByteVector = ByteVector.fromValidHex("0103")

  test("AccessLog.empty: creates empty log"):
    val log = AccessLog.empty
    assertEquals(log.reads, Map.empty[ByteVector, Set[ByteVector]])
    assertEquals(log.writes, Map.empty[ByteVector, Set[ByteVector]])
    assertEquals(log.readCount, 0)
    assertEquals(log.writeCount, 0)

  test("recordRead: adds read to empty log"):
    val log = AccessLog.empty.recordRead(prefix1, key1)
    assertEquals(log.reads, Map(prefix1 -> Set(key1)))
    assertEquals(log.writes, Map.empty[ByteVector, Set[ByteVector]])
    assertEquals(log.readCount, 1)
    assertEquals(log.writeCount, 0)

  test("recordRead: accumulates multiple reads for same prefix"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
    assertEquals(log.reads, Map(prefix1 -> Set(key1, key2)))
    assertEquals(log.readCount, 2)

  test("recordRead: handles multiple prefixes"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix2, key2)
    assertEquals(log.reads, Map(prefix1 -> Set(key1), prefix2 -> Set(key2)))
    assertEquals(log.readCount, 2)

  test("recordRead: deduplicates same key"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key1) // Duplicate
    assertEquals(log.reads, Map(prefix1 -> Set(key1)))
    assertEquals(log.readCount, 1) // Not 2

  test("recordWrite: adds write to empty log"):
    val log = AccessLog.empty.recordWrite(prefix1, key1)
    assertEquals(log.writes, Map(prefix1 -> Set(key1)))
    assertEquals(log.reads, Map.empty[ByteVector, Set[ByteVector]])
    assertEquals(log.writeCount, 1)
    assertEquals(log.readCount, 0)

  test("recordWrite: accumulates multiple writes for same prefix"):
    val log = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key2)
    assertEquals(log.writes, Map(prefix1 -> Set(key1, key2)))
    assertEquals(log.writeCount, 2)

  test("recordWrite: handles multiple prefixes"):
    val log = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix2, key2)
    assertEquals(log.writes, Map(prefix1 -> Set(key1), prefix2 -> Set(key2)))
    assertEquals(log.writeCount, 2)

  test("recordWrite: deduplicates same key"):
    val log = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key1) // Duplicate
    assertEquals(log.writes, Map(prefix1 -> Set(key1)))
    assertEquals(log.writeCount, 1) // Not 2

  test("combine: empty logs"):
    val log1 = AccessLog.empty
    val log2 = AccessLog.empty
    val combined = log1.combine(log2)
    assertEquals(combined, AccessLog.empty)

  test("combine: non-empty with empty"):
    val log1 = AccessLog.empty.recordRead(prefix1, key1)
    val log2 = AccessLog.empty
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map(prefix1 -> Set(key1)))
    assertEquals(combined.writes, Map.empty[ByteVector, Set[ByteVector]])

  test("combine: empty with non-empty"):
    val log1 = AccessLog.empty
    val log2 = AccessLog.empty.recordWrite(prefix1, key1)
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map.empty[ByteVector, Set[ByteVector]])
    assertEquals(combined.writes, Map(prefix1 -> Set(key1)))

  test("combine: disjoint prefixes"):
    val log1 = AccessLog.empty.recordRead(prefix1, key1)
    val log2 = AccessLog.empty.recordRead(prefix2, key2)
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map(prefix1 -> Set(key1), prefix2 -> Set(key2)))

  test("combine: same prefix, different keys"):
    val log1 = AccessLog.empty.recordRead(prefix1, key1)
    val log2 = AccessLog.empty.recordRead(prefix1, key2)
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map(prefix1 -> Set(key1, key2)))

  test("combine: same prefix, overlapping keys"):
    val log1 = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
    val log2 = AccessLog.empty
      .recordRead(prefix1, key2) // key2 overlaps
      .recordRead(prefix1, key3)
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map(prefix1 -> Set(key1, key2, key3)))

  test("combine: reads and writes"):
    val log1 = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordWrite(prefix1, key2)
    val log2 = AccessLog.empty
      .recordRead(prefix1, key3)
      .recordWrite(prefix2, key1)
    val combined = log1.combine(log2)
    assertEquals(combined.reads, Map(prefix1 -> Set(key1, key3)))
    assertEquals(
      combined.writes,
      Map(prefix1 -> Set(key2), prefix2 -> Set(key1)),
    )

  test("conflictsWith: empty logs do not conflict"):
    val log1 = AccessLog.empty
    val log2 = AccessLog.empty
    assertEquals(log1.conflictsWith(log2), false)

  test("conflictsWith: disjoint reads do not conflict"):
    val log1 = AccessLog.empty.recordRead(prefix1, key1)
    val log2 = AccessLog.empty.recordRead(prefix1, key2)
    assertEquals(log1.conflictsWith(log2), false)

  test("conflictsWith: disjoint writes do not conflict"):
    val log1 = AccessLog.empty.recordWrite(prefix1, key1)
    val log2 = AccessLog.empty.recordWrite(prefix1, key2)
    assertEquals(log1.conflictsWith(log2), false)

  test("conflictsWith: W∩W conflict (same key written by both)"):
    val log1 = AccessLog.empty.recordWrite(prefix1, key1)
    val log2 = AccessLog.empty.recordWrite(prefix1, key1) // Same key
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: W∩W conflict (multiple keys, one overlaps)"):
    val log1 = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key2)
    val log2 = AccessLog.empty
      .recordWrite(prefix1, key2) // key2 overlaps
      .recordWrite(prefix1, key3)
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: R∩W conflict (read intersects write)"):
    val log1 = AccessLog.empty.recordRead(prefix1, key1)
    val log2 = AccessLog.empty.recordWrite(prefix1, key1) // Same key
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: W∩R conflict (write intersects read)"):
    val log1 = AccessLog.empty.recordWrite(prefix1, key1)
    val log2 = AccessLog.empty.recordRead(prefix1, key1) // Same key
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: multiple tables, no conflict"):
    val log1 = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordWrite(prefix1, key2)
    val log2 = AccessLog.empty
      .recordRead(prefix2, key1)
      .recordWrite(prefix2, key2)
    assertEquals(log1.conflictsWith(log2), false)

  test("conflictsWith: multiple tables, one conflict"):
    val log1 = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordRead(prefix2, key2)
    val log2 = AccessLog.empty
      .recordRead(prefix1, key3)
      .recordWrite(prefix2, key2) // Conflict on prefix2:key2
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: complex scenario with multiple conflicts"):
    val log1 = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordWrite(prefix1, key2)
      .recordRead(prefix2, key1)
    val log2 = AccessLog.empty
      .recordWrite(prefix1, key1) // R∩W conflict
      .recordRead(prefix1, key2) // W∩R conflict
      .recordWrite(prefix2, key1) // R∩W conflict
    assertEquals(log1.conflictsWith(log2), true)

  test("conflictsWith: symmetric (order doesn't matter)"):
    val log1 = AccessLog.empty.recordWrite(prefix1, key1)
    val log2 = AccessLog.empty.recordRead(prefix1, key1)
    assertEquals(log1.conflictsWith(log2), log2.conflictsWith(log1))

  test("readCount: counts all reads across all tables"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
      .recordRead(prefix2, key1)
      .recordRead(prefix2, key2)
      .recordRead(prefix2, key3)
    assertEquals(log.readCount, 5)

  test("writeCount: counts all writes across all tables"):
    val log = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key2)
      .recordWrite(prefix2, key1)
    assertEquals(log.writeCount, 3)

  test("readCount and writeCount: independent"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
      .recordWrite(prefix1, key3)
      .recordWrite(prefix2, key1)
    assertEquals(log.readCount, 2)
    assertEquals(log.writeCount, 2)

  test("exceedsLimits: empty log does not exceed any limits"):
    val log = AccessLog.empty
    assertEquals(log.exceedsLimits(maxReads = 0, maxWrites = 0), false)
    assertEquals(log.exceedsLimits(maxReads = 100, maxWrites = 100), false)

  test("exceedsLimits: within limits"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
      .recordWrite(prefix1, key3)
    assertEquals(log.exceedsLimits(maxReads = 2, maxWrites = 1), false)
    assertEquals(log.exceedsLimits(maxReads = 10, maxWrites = 10), false)

  test("exceedsLimits: exceeds read limit"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
      .recordRead(prefix1, key3)
    assertEquals(log.exceedsLimits(maxReads = 2, maxWrites = 10), true)

  test("exceedsLimits: exceeds write limit"):
    val log = AccessLog.empty
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key2)
      .recordWrite(prefix1, key3)
    assertEquals(log.exceedsLimits(maxReads = 10, maxWrites = 2), true)

  test("exceedsLimits: exceeds both limits"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordRead(prefix1, key2)
      .recordRead(prefix1, key3)
      .recordWrite(prefix1, key1)
      .recordWrite(prefix1, key2)
      .recordWrite(prefix1, key3)
    assertEquals(log.exceedsLimits(maxReads = 2, maxWrites = 2), true)

  test("exceedsLimits: exactly at limit is OK"):
    val log = AccessLog.empty
      .recordRead(prefix1, key1)
      .recordWrite(prefix1, key2)
    assertEquals(log.exceedsLimits(maxReads = 1, maxWrites = 1), false)

  test("real-world scenario: transaction creating account"):
    // Simulate CreateNamedAccount transaction
    val accountPrefix = ByteVector.fromValidHex("01")
    val nameKeyPrefix = ByteVector.fromValidHex("02")
    val aliceKey = ByteVector.fromValidHex("0101616C696365") // "alice"
    val aliceKeyId = ByteVector.fromValidHex("0102616C69636530") // alice+keyId

    val log = AccessLog.empty
      .recordRead(accountPrefix, aliceKey) // Check if account exists
      .recordWrite(accountPrefix, aliceKey) // Create account
      .recordWrite(nameKeyPrefix, aliceKeyId) // Register key

    assertEquals(log.readCount, 1)
    assertEquals(log.writeCount, 2)

  test("real-world scenario: conflicting concurrent creates"):
    // Two transactions trying to create same account
    val accountPrefix = ByteVector.fromValidHex("01")
    val aliceKey = ByteVector.fromValidHex("0101616C696365")

    val tx1Log = AccessLog.empty
      .recordRead(accountPrefix, aliceKey) // Check if account exists
      .recordWrite(accountPrefix, aliceKey) // Create account

    val tx2Log = AccessLog.empty
      .recordRead(accountPrefix, aliceKey) // Check if account exists
      .recordWrite(accountPrefix, aliceKey) // Create account

    // W∩W conflict on account creation
    assertEquals(tx1Log.conflictsWith(tx2Log), true)

  test("real-world scenario: non-conflicting parallel creates"):
    // Two transactions creating different accounts
    val accountPrefix = ByteVector.fromValidHex("01")
    val aliceKey = ByteVector.fromValidHex("0101616C696365")
    val bobKey = ByteVector.fromValidHex("0101626F62")

    val tx1Log = AccessLog.empty
      .recordRead(accountPrefix, aliceKey)
      .recordWrite(accountPrefix, aliceKey)

    val tx2Log = AccessLog.empty
      .recordRead(accountPrefix, bobKey)
      .recordWrite(accountPrefix, bobKey)

    // No conflict - different keys
    assertEquals(tx1Log.conflictsWith(tx2Log), false)

  test("real-world scenario: read-heavy transaction with write"):
    // Transaction reading multiple accounts and updating one
    val accountPrefix = ByteVector.fromValidHex("01")
    val key1 = ByteVector.fromValidHex("0101")
    val key2 = ByteVector.fromValidHex("0102")
    val key3 = ByteVector.fromValidHex("0103")
    val key4 = ByteVector.fromValidHex("0104")

    val log = AccessLog.empty
      .recordRead(accountPrefix, key1)
      .recordRead(accountPrefix, key2)
      .recordRead(accountPrefix, key3)
      .recordWrite(accountPrefix, key4)

    assertEquals(log.readCount, 3)
    assertEquals(log.writeCount, 1)

  test("real-world scenario: size limits for large batch"):
    // Large transaction exceeding limits
    val prefix = ByteVector.fromValidHex("01")
    var log = AccessLog.empty

    // Add 50 reads
    for i <- 1 to 50 do
      val key = ByteVector(i.toByte)
      log = log.recordRead(prefix, key)

    // Add 30 writes
    for i <- 51 to 80 do
      val key = ByteVector(i.toByte)
      log = log.recordWrite(prefix, key)

    assertEquals(log.readCount, 50)
    assertEquals(log.writeCount, 30)
    assertEquals(log.exceedsLimits(maxReads = 40, maxWrites = 40), true)
    assertEquals(log.exceedsLimits(maxReads = 100, maxWrites = 100), false)
