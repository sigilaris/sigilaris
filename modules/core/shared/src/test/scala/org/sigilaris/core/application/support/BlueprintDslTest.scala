package org.sigilaris.core.application.support

import cats.Id
import cats.data.{EitherT, Kleisli}
import munit.FunSuite

import org.sigilaris.core.application.domain.{Entry, KeyOf as DomainKeyOf, StoreF, Tables}
import org.sigilaris.core.application.module.{ModuleBlueprint, StateModule, StateReducer, StateReducer0, TablesProvider}
import org.sigilaris.core.application.module.SchemaMapper.given
import org.sigilaris.core.application.transactions.{Signed, Tx, TxRegistry}
import org.sigilaris.core.application.support.{Lookup, Requires}
import org.sigilaris.core.application.support.Lookup.given
import org.sigilaris.core.application.support.PathEncoder.given
import org.sigilaris.core.application.support.PrefixFreePath.given
import org.sigilaris.core.application.support.UniqueNames.given
import org.sigilaris.core.assembly.BlueprintDsl
import org.sigilaris.core.assembly.EntrySyntax.entry
import org.sigilaris.core.assembly.TablesAccessOps.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.datatype.Utf8
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

class BlueprintDslTest extends FunSuite:
  given cats.Monad[Id] = cats.catsInstancesForId
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  private type TestEntry      = Entry["balances", Utf8, Long]
  private type TestSchema     = TestEntry *: EmptyTuple
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

  test("TablesProviderOps.toTablesProvider re-exposes mounted tables"):
    val module = BlueprintDsl.mount("app" -> testBlueprint)
    val provider = module.toTablesProvider

    // Ensure the provider exposes the same tables as the mounted module.
    // Tuple equality holds because Tables is an alias for tuples of StateTable values.
    assertEquals(provider.tables, module.tables)

  test("TablesAccessOps.providedTable resolves dependency table by name"):
    val module = BlueprintDsl.mount("app" -> testBlueprint)
    val provider: TablesProvider[Id, TestSchema] = module.toTablesProvider
    val balancesTable = provider.providedTable["balances", Utf8, Long]
    val brandedKey = balancesTable.brand(Utf8("alice"))
    // If brand succeeds, the lookup evidence matched the expected schema.
    assertEquals(DomainKeyOf.unwrap(brandedKey), Utf8("alice"))

  test("TablesAccessOps.deriveLookup mirrors summonInline"):
    val lookup = deriveLookup[TestSchema, "balances", Utf8, Long]
    val module = BlueprintDsl.mount("app" -> testBlueprint)
    val provider: TablesProvider[Id, TestSchema] = module.toTablesProvider
    val table = lookup.table(provider.tables)
    val brandedKey = table.brand(Utf8("bob"))
    assertEquals(DomainKeyOf.unwrap(brandedKey), Utf8("bob"))
