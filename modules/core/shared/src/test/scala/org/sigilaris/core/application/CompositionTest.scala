package org.sigilaris.core
package application

import cats.Id
import cats.data.{EitherT, Kleisli, StateT}

import munit.FunSuite

import datatype.Utf8
import merkle.{MerkleTrie, MerkleTrieNode}

@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))

class CompositionTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  // Test schemas
  type BalancesEntry = Entry["balances", Utf8, Long]
  type AccountsEntry = Entry["accounts", Utf8, Utf8]
  type TokensEntry = Entry["tokens", Utf8, BigInt]

  type Schema1 = BalancesEntry *: EmptyTuple
  type Schema2 = AccountsEntry *: EmptyTuple
  type Schema3 = TokensEntry *: EmptyTuple

  // Helper to create a simple blueprint
  def createBlueprint1(): ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple] =
    val balancesEntry = Entry["balances", Utf8, Long]
    val schema: Schema1 = balancesEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema1]:
      def apply[T <: Tx](tx: T)(using
          Requires[tx.Reads, Schema1],
          Requires[tx.Writes, Schema1],
      ): StoreF[Id][(tx.Result, List[tx.Event])] =
        StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  def createBlueprint2(): ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple] =
    val accountsEntry = Entry["accounts", Utf8, Utf8]
    val schema: Schema2 = accountsEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema2]:
      def apply[T <: Tx](tx: T)(using
          Requires[tx.Reads, Schema2],
          Requires[tx.Writes, Schema2],
      ): StoreF[Id][(tx.Result, List[tx.Event])] =
        StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  test("composeBlueprint combines two blueprints with different schemas"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()

    // Should compile: schemas have different table names
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Verify schema is concatenated
    assertEquals(composed.schema.size, 2)
    // Verify it compiles - composed is of correct type
    val _: ModuleBlueprint[Id, "combined", Schema1 ++ Schema2, EmptyTuple ++ EmptyTuple, (EmptyTuple, EmptyTuple)] = composed

  test("composeBlueprint preserves UniqueNames evidence for disjoint schemas"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()

    // Should summon UniqueNames for combined schema - just check it compiles
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Verify evidence can be summoned and composed blueprint is correct
    assert(summon[UniqueNames[Schema1 ++ Schema2]] != null)
    assert(composed.schema.size == 2)

  test("composed blueprint can be mounted"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Should be able to mount composed blueprint
    type Path = "app" *: EmptyTuple
    type Combined = Schema1 ++ Schema2
    val module = StateModule.mount[Id, "combined", Path, Combined, EmptyTuple, (EmptyTuple, EmptyTuple)](composed)

    // Verify tables are created
    assertEquals(module.tables.size, 2)

  test("composed blueprint has correct dependencies"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Dependencies should be tuple of original dependencies
    val deps: (EmptyTuple, EmptyTuple) = composed.deps
    assertEquals(deps, (EmptyTuple, EmptyTuple))

  test("mountAt helper works for nested paths"):
    val bp = createBlueprint1()

    // Mount at base path ("app",) with sub-path ("v1",)
    type Base = "app" *: EmptyTuple
    type Sub = "v1" *: EmptyTuple
    val module = Blueprint.mountAt[Id, "module1", Base, Sub, Schema1, EmptyTuple, EmptyTuple](bp)

    // Should have correct path type
    val _: StateModule[Id, Base ++ Sub, Schema1, EmptyTuple, EmptyTuple] = module
    assertEquals(module.tables.size, 1)

  test("PrefixFreePath validation for composed schema"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Should summon PrefixFreePath for mounted path
    type Path = "app" *: EmptyTuple
    type Combined = Schema1 ++ Schema2

    // Verify evidence can be summoned and schema is correct
    assert(summon[PrefixFreePath[Path, Combined]] != null)
    assertEquals(composed.schema.size, 2)
