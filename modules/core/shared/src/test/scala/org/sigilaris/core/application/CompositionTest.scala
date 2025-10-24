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
    val _: ModuleBlueprint[Id, "combined", Schema1 ++ Schema2, EmptyTuple ++ EmptyTuple, EmptyTuple] = composed

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
    val module = StateModule.mount[Id, "combined", Path, Combined, EmptyTuple, EmptyTuple](composed)

    // Verify tables are created
    assertEquals(module.tables.size, 2)

  test("composed blueprint has correct dependencies"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    // Dependencies should be concatenated using tupleConcat
    val deps: EmptyTuple = composed.deps
    assertEquals(deps, EmptyTuple)

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

  // Test transactions with module routing
  case class Module1Tx(value: Long) extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId = ModuleId("module1" *: EmptyTuple)
    type Reads = Schema1
    type Writes = Schema1
    type Result = Long
    type Event = Nothing

  case class Module2Tx(name: String) extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId = ModuleId("module2" *: EmptyTuple)
    type Reads = Schema2
    type Writes = Schema2
    type Result = String
    type Event = Nothing

  case class UnroutedTx() extends Tx:
    type Reads = EmptyTuple
    type Writes = EmptyTuple
    type Result = Unit
    type Event = Nothing

  case class WrongPathTx() extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId = ModuleId("module3" *: EmptyTuple)
    type Reads = EmptyTuple
    type Writes = EmptyTuple
    type Result = Unit
    type Event = Nothing

  def createRoutingBlueprint1(): ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple] =
    val balancesEntry = Entry["balances", Utf8, Long]
    val schema: Schema1 = balancesEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema1]:
      def apply[T <: Tx](tx: T)(using
          Requires[tx.Reads, Schema1],
          Requires[tx.Writes, Schema1],
      ): StoreF[Id][(tx.Result, List[tx.Event])] =
        tx match
          case Module1Tx(value) =>
            StateT.pure((value.asInstanceOf[tx.Result], List.empty[tx.Event]))
          case _ =>
            StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  def createRoutingBlueprint2(): ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple] =
    val accountsEntry = Entry["accounts", Utf8, Utf8]
    val schema: Schema2 = accountsEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema2]:
      def apply[T <: Tx](tx: T)(using
          Requires[tx.Reads, Schema2],
          Requires[tx.Writes, Schema2],
      ): StoreF[Id][(tx.Result, List[tx.Event])] =
        tx match
          case Module2Tx(name) =>
            StateT.pure((name.asInstanceOf[tx.Result], List.empty[tx.Event]))
          case _ =>
            StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  test("composeBlueprint routes Module1Tx to first reducer"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    val tx = Module1Tx(42L)
    val result = composed.reducer0.apply(tx)(using
      summon[Requires[tx.Reads, Schema1 ++ Schema2]],
      summon[Requires[tx.Writes, Schema1 ++ Schema2]],
    )

    val runResult = result.run(null).value
    runResult match
      case Right((_, (value, events))) =>
        assertEquals(value, 42L)
        assertEquals(events, List.empty)
      case Left(err) =>
        fail(s"Expected success but got error: $err")

  test("composeBlueprint routes Module2Tx to second reducer"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    val tx = Module2Tx("test")
    val result = composed.reducer0.apply(tx)(using
      summon[Requires[tx.Reads, Schema1 ++ Schema2]],
      summon[Requires[tx.Writes, Schema1 ++ Schema2]],
    )

    val runResult = result.run(null).value
    runResult match
      case Right((_, (value, events))) =>
        assertEquals(value, "test")
        assertEquals(events, List.empty)
      case Left(err) =>
        fail(s"Expected success but got error: $err")

  test("composeBlueprint fails for transaction without ModuleRoutedTx"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    val tx = UnroutedTx()
    intercept[IllegalArgumentException]:
      composed.reducer0.apply(tx)(using
        summon[Requires[tx.Reads, Schema1 ++ Schema2]],
        summon[Requires[tx.Writes, Schema1 ++ Schema2]],
      ).run(null).value

  test("composeBlueprint fails for transaction with wrong module path"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined", "module1", Schema1, EmptyTuple, EmptyTuple, "module2", Schema2, EmptyTuple, EmptyTuple](bp1, bp2)

    val tx = WrongPathTx()
    intercept[IllegalArgumentException]:
      composed.reducer0.apply(tx)(using
        summon[Requires[tx.Reads, Schema1 ++ Schema2]],
        summon[Requires[tx.Writes, Schema1 ++ Schema2]],
      ).run(null).value

  test("tupleConcat produces flat concatenation"):
    val tuple1 = ("a", 1) *: EmptyTuple
    val tuple2 = ("b", 2) *: EmptyTuple
    val result = Blueprint.tupleConcat(tuple1, tuple2)

    // Verify flat structure
    assertEquals(result.size, 2)
    assertEquals(result(0), ("a", 1))
    assertEquals(result(1), ("b", 2))

  test("tupleConcat with EmptyTuple"):
    val tuple1 = ("a", 1) *: EmptyTuple
    val tuple2 = EmptyTuple
    val result = Blueprint.tupleConcat(tuple1, tuple2)

    assertEquals(result.size, 1)
    assertEquals(result(0), ("a", 1))

  test("tupleConcat preserves type-level concatenation"):
    type T1 = ("a", Int) *: EmptyTuple
    type T2 = ("b", String) *: EmptyTuple
    type Expected = T1 ++ T2

    val tuple1: T1 = ("a", 1) *: EmptyTuple
    val tuple2: T2 = ("b", "test") *: EmptyTuple
    val result: Expected = Blueprint.tupleConcat(tuple1, tuple2)

    // Verify runtime structure matches type-level expectation
    assertEquals(result.size, 2)
