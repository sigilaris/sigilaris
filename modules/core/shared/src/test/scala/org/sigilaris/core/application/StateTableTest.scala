package org.sigilaris.core.application

import cats.Id
import cats.data.{EitherT, Kleisli}
import scodec.bits.hex

import munit.FunSuite

import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

class StateTableTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  test("StateTable get returns None for non-existent key"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("alice"))
    val initialState = StoreState.empty

    val result = table.get(key).runA(initialState).value

    result match
      case Right(maybeBalance) => assertEquals(maybeBalance, None)
      case Left(err) => fail(s"Expected Right(None), got Left($err)")

  test("StateTable put and get round-trip"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("alice"))
    val balance = 100L
    val initialState = StoreState.empty

    // Put then get
    val program = for
      _ <- table.put(key, balance)
      retrieved <- table.get(key)
    yield retrieved

    program.run(initialState).value match
      case Right((finalState, Some(retrievedBalance))) =>
        assertEquals(retrievedBalance, balance)
        // Verify state changed
        assert(finalState.trieState.root != initialState.trieState.root)
      case Right((_, None)) =>
        fail("Expected Some(balance), got None")
      case Left(err) =>
        fail(s"Expected Right(Some(balance)), got Left($err)")

  test("StateTable remove returns true for existing key"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("bob"))
    val balance = 200L
    val initialState = StoreState.empty

    // Put, then remove
    val program = for
      _ <- table.put(key, balance)
      removed <- table.remove(key)
      retrieved <- table.get(key)
    yield (removed, retrieved)

    val result = program.runA(initialState).value

    result match
      case Right((wasRemoved, maybeBalance)) =>
        assertEquals(wasRemoved, true)
        assertEquals(maybeBalance, None)
      case Left(err) =>
        fail(s"Expected Right, got Left($err)")

  test("StateTable remove returns false for non-existent key"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("charlie"))
    val initialState = StoreState.empty

    val result = table.remove(key).runA(initialState).value

    result match
      case Right(wasRemoved) => assertEquals(wasRemoved, false)
      case Left(err) => fail(s"Expected Right(false), got Left($err)")

  test("Branded keys are type-safe at compile time"):
    val prefix1 = hex"0001"
    val prefix2 = hex"0002"

    val balances = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix1)
    val scores = StateTable.atPrefix[Id, "scores", Utf8, Long](prefix2)

    val balanceKey = balances.brand(Utf8("alice"))
    val scoreKey = scores.brand(Utf8("alice"))

    // This should compile - using the correct key for each table
    val initialState = StoreState.empty
    val _ = balances.get(balanceKey).runA(initialState).value
    val _ = scores.get(scoreKey).runA(initialState).value

    // The following should NOT compile - keys are branded with different table types
    // Verify that using scoreKey with balances table fails at compile time
    assertNoDiff(
      compileErrors("balances.get(scoreKey)"),
      """|error:
         |Found:    (scoreKey : scores.Key)
         |Required: balances.Key
         |balances.get(scoreKey)
         |            ^
         |""".stripMargin
    )

    // Verify that using balanceKey with scores table fails at compile time
    assertNoDiff(
      compileErrors("scores.get(balanceKey)"),
      """|error:
         |Found:    (balanceKey : balances.Key)
         |Required: scores.Key
         |scores.get(balanceKey)
         |          ^
         |""".stripMargin
    )

  // Phase 8: Integration tests for AccessLog recording
  test("StateTable operations record accesses in AccessLog"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val aliceKey = table.brand(Utf8("alice"))
    val bobKey = table.brand(Utf8("bob"))
    val initialState = StoreState.empty

    // Perform operations and check AccessLog
    val charlieKey = table.brand(Utf8("charlie"))
    val program = for
      _ <- table.get(aliceKey)           // Read alice
      _ <- table.put(aliceKey, 100L)     // Write alice
      _ <- table.get(aliceKey)           // Read alice (deduplicated in log)
      _ <- table.get(bobKey)             // Read bob
      _ <- table.put(bobKey, 200L)       // Write bob
      _ <- table.put(charlieKey, 300L)   // Write charlie
    yield ()

    val result = program.run(initialState).value

    result match
      case Right((finalState, _)) =>
        val log = finalState.accessLog
        
        // Verify read count (2 unique keys: alice, bob)
        assertEquals(log.readCount, 2, "Expected 2 unique keys read")
        
        // Verify write count (3 unique keys: alice, bob, charlie)
        assertEquals(log.writeCount, 3, "Expected 3 unique keys written")
        
        // Verify the log contains the correct table prefix
        assert(log.reads.contains(prefix), "Reads should include table prefix")
        assert(log.writes.contains(prefix), "Writes should include table prefix")
        
      case Left(err) =>
        fail(s"Expected successful operations, got Left($err)")

  test("StateTable: parallel operations on different keys don't conflict"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val aliceKey = table.brand(Utf8("alice"))
    val bobKey = table.brand(Utf8("bob"))
    val initialState = StoreState.empty

    // Transaction 1: operations on alice
    val tx1 = for
      _ <- table.get(aliceKey)
      _ <- table.put(aliceKey, 100L)
    yield ()

    // Transaction 2: operations on bob
    val tx2 = for
      _ <- table.get(bobKey)
      _ <- table.put(bobKey, 200L)
    yield ()

    val result1 = tx1.run(initialState).value
    val result2 = tx2.run(initialState).value

    (result1, result2) match
      case (Right((state1, _)), Right((state2, _))) =>
        val log1 = state1.accessLog
        val log2 = state2.accessLog
        
        // Verify no conflict - different keys
        assertEquals(log1.conflictsWith(log2), false, 
          "Operations on different keys should not conflict")
        
      case _ =>
        fail("Expected both transactions to succeed")

  test("StateTable: overlapping writes cause W∩W conflict"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val aliceKey = table.brand(Utf8("alice"))
    val initialState = StoreState.empty

    // Transaction 1: write to alice
    val tx1 = table.put(aliceKey, 100L)

    // Transaction 2: write to alice
    val tx2 = table.put(aliceKey, 200L)

    val result1 = tx1.run(initialState).value
    val result2 = tx2.run(initialState).value

    (result1, result2) match
      case (Right((state1, _)), Right((state2, _))) =>
        val log1 = state1.accessLog
        val log2 = state2.accessLog
        
        // Verify W∩W conflict
        assertEquals(log1.conflictsWith(log2), true, 
          "Concurrent writes to same key should conflict (W∩W)")
        
      case _ =>
        fail("Expected both transactions to succeed")

  test("StateTable: read-write on same key causes R∩W conflict"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val aliceKey = table.brand(Utf8("alice"))
    val initialState = StoreState.empty

    // Transaction 1: read alice
    val tx1 = table.get(aliceKey)

    // Transaction 2: write alice
    val tx2 = table.put(aliceKey, 100L)

    val result1 = tx1.run(initialState).value
    val result2 = tx2.run(initialState).value

    (result1, result2) match
      case (Right((state1, _)), Right((state2, _))) =>
        val log1 = state1.accessLog
        val log2 = state2.accessLog
        
        // Verify R∩W conflict
        assertEquals(log1.conflictsWith(log2), true, 
          "Read and write on same key should conflict (R∩W)")
        
      case _ =>
        fail("Expected both transactions to succeed")
