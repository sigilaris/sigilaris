package org.sigilaris.core
package application
package support

import cats.Id
import cats.data.{EitherT, Kleisli}
import munit.FunSuite

import datatype.Utf8
import merkle.{MerkleTrie, MerkleTrieNode}

import EntrySyntax.entry

class BlueprintDslTest extends FunSuite:
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  private type TestEntry      = EntryTuple["balances", Utf8, Long]
  private type TestSchema     = TestEntry
  private type TestNeeds      = EmptyTuple
  private type TestTxs        = EmptyTuple
  private type TestModuleName = "test"

  private def testBlueprint: ModuleBlueprint[Id, TestModuleName, TestSchema, TestNeeds, TestTxs] =
    val balancesEntry = entry"balances"[Utf8, Long]
    val schema: TestSchema = balancesEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, TestSchema, TestNeeds]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          Requires[signedTx.value.Reads, TestSchema],
          Requires[signedTx.value.Writes, TestSchema],
          Tables[Id, TestSchema],
          TablesProvider[Id, TestNeeds],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        cats.data.StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, TestModuleName, TestSchema, TestNeeds, TestTxs](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  test("entry\"name\" creates Entry instance with literal type"):
    val balances = entry"balances"[Utf8, Long]
    assertEquals(balances.tableName, "balances")

  test("BlueprintDsl.mount mounts blueprint at literal path segment"):
    val bp = testBlueprint
    val module = BlueprintDsl.mount("app" -> bp)

    val typed: StateModule[Id, ("app" *: EmptyTuple), TestSchema, TestNeeds, TestTxs, StateReducer[Id, ("app" *: EmptyTuple), TestSchema, TestNeeds]] = module
    assert(typed eq module)
