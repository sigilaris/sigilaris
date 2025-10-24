package org.sigilaris.core
package application

import cats.Id
import cats.data.{EitherT, Kleisli}
import scodec.bits.hex

import munit.FunSuite

import datatype.Utf8
import merkle.{MerkleTrie, MerkleTrieNode, MerkleTrieState}

class StateTableTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  test("StateTable get returns None for non-existent key"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("alice"))
    val initialState = MerkleTrieState.empty

    val result = table.get(key).runA(initialState).value

    result match
      case Right(maybeBalance) => assertEquals(maybeBalance, None)
      case Left(err) => fail(s"Expected Right(None), got Left($err)")

  test("StateTable put and get round-trip"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("alice"))
    val balance = 100L
    val initialState = MerkleTrieState.empty

    // Put then get
    val program = for
      _ <- table.put(key, balance)
      retrieved <- table.get(key)
    yield retrieved

    program.run(initialState).value match
      case Right((finalState, Some(retrievedBalance))) =>
        assertEquals(retrievedBalance, balance)
        // Verify state changed
        assert(finalState.root != initialState.root)
      case Right((_, None)) =>
        fail("Expected Some(balance), got None")
      case Left(err) =>
        fail(s"Expected Right(Some(balance)), got Left($err)")

  test("StateTable remove returns true for existing key"):
    val prefix = hex"0001"
    val table = StateTable.atPrefix[Id, "balances", Utf8, Long](prefix)

    val key = table.brand(Utf8("bob"))
    val balance = 200L
    val initialState = MerkleTrieState.empty

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
    val initialState = MerkleTrieState.empty

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
    val initialState = MerkleTrieState.empty
    val _ = balances.get(balanceKey).runA(initialState).value
    val _ = scores.get(scoreKey).runA(initialState).value

    // The following should NOT compile - keys are branded with different table types
    compileErrors("balances.get(scoreKey)")
    compileErrors("scores.get(balanceKey)")
