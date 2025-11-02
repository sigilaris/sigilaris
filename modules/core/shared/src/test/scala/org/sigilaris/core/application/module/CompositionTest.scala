package org.sigilaris.core.application.module

import cats.Id
import cats.data.{EitherT, Kleisli, StateT}
import scala.Tuple.++

import munit.FunSuite

import org.sigilaris.core.application.feature.accounts.domain.Account
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.module.blueprint.{
  Blueprint,
  ComposedBlueprint,
  ModuleBlueprint,
  StateReducer0,
}
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.module.runtime.{StateModule, StateReducer}
import org.sigilaris.core.application.support.compiletime.{PrefixFreePath, Requires, UniqueNames}
import org.sigilaris.core.application.transactions.model.{
  AccountSignature,
  ModuleId,
  ModuleRoutedTx,
  Signed,
  Tx,
  TxRegistry,
}
import org.sigilaris.core.crypto.Signature
import org.sigilaris.core.datatype.{UInt256, Utf8}
import org.sigilaris.core.failure.RoutingFailure
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}
import scodec.bits.ByteVector

@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))

class CompositionTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  // Helper to create a signed transaction with dummy signature for testing
  def signTx[A <: Tx](tx: A, account: Account): Signed[A] =
    val dummySig = Signature(
      v = 27,
      r = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
      s = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
    )
    Signed(AccountSignature(account, dummySig), tx)

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

    val reducer = new StateReducer0[Id, Schema1, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema1],
          requiresWrites: Requires[signedTx.value.Writes, Schema1],
          ownsTables: Tables[Id, Schema1],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  def createBlueprint2(): ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple] =
    val accountsEntry = Entry["accounts", Utf8, Utf8]
    val schema: Schema2 = accountsEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema2, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema2],
          requiresWrites: Requires[signedTx.value.Writes, Schema2],
          ownsTables: Tables[Id, Schema2],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  def createBlueprint3(): ModuleBlueprint[Id, "module3", Schema3, EmptyTuple, EmptyTuple] =
    val tokensEntry = Entry["tokens", Utf8, BigInt]
    val schema: Schema3 = tokensEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema3, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema3],
          requiresWrites: Requires[signedTx.value.Writes, Schema3],
          ownsTables: Tables[Id, Schema3],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module3", Schema3, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  test("composeBlueprint combines two blueprints with different schemas"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()

    // Should compile: schemas have different table names
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Verify schema is concatenated
    assertEquals(composed.owns.size, 2)
    // Verify it compiles - composed is of correct type (ComposedBlueprint, not ModuleBlueprint)
    val _: ComposedBlueprint[Id, "combined", Schema1 ++ Schema2, EmptyTuple, EmptyTuple] = composed

  test("composeBlueprint preserves UniqueNames evidence for disjoint schemas"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()

    // Should summon UniqueNames for combined schema - just check it compiles
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Verify evidence can be summoned and composed blueprint is correct
    assert(summon[UniqueNames[Schema1 ++ Schema2]] != null)
    assert(composed.owns.size == 2)

  test("composed blueprint can be mounted"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Should be able to mount composed blueprint using mountComposed
    type Path = "app" *: EmptyTuple
    val module = StateModule.mountComposed[Path](composed)

    // Verify tables are created
    assertEquals(module.tables.size, 2)

  test("composed blueprint has correct dependencies"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Phase 5.5: Needs must be EmptyTuple for composition
    val provider: TablesProvider[Id, EmptyTuple] = composed.provider
    assert(provider != null)

  test("nested composed blueprints can be mounted"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val bp3 = createBlueprint3()

    val composed12 = Blueprint.composeBlueprint[Id, "combined12"](bp1, bp2)
    val nested = Blueprint.composeBlueprint[Id, "nested"](composed12, bp3)

    type Path = "app" *: EmptyTuple
    val module = StateModule.mountComposed[Path](nested)

    assertEquals(module.tables.size, 3)

  test("mountAt helper works for nested paths"):
    val bp = createBlueprint1()

    // Mount at base path ("app",) with sub-path ("v1",)
    type Base = "app" *: EmptyTuple
    type Sub = "v1" *: EmptyTuple
    val module = Blueprint.mountAt[Base, Sub](bp)

    // Should have correct path type (includes reducer type parameter)
    val _: StateModule[Id, Base ++ Sub, Schema1, EmptyTuple, EmptyTuple, StateReducer[Id, Base ++ Sub, Schema1, EmptyTuple]] = module
    assertEquals(module.tables.size, 1)

  test("PrefixFreePath validation for composed schema"):
    val bp1 = createBlueprint1()
    val bp2 = createBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Should summon PrefixFreePath for mounted path
    type Path = "app" *: EmptyTuple
    type Combined = Schema1 ++ Schema2

    // Verify evidence can be summoned and schema is correct
    assert(summon[PrefixFreePath[Path, Combined]] != null)
    assertEquals(composed.owns.size, 2)

  // Test transactions with module routing
  case class Module1Tx(value: Long) extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId["module1" *: EmptyTuple] = ModuleId("module1" *: EmptyTuple)
    type Reads = Schema1
    type Writes = Schema1
    type Result = Long
    type Event = Nothing

  case class Module2Tx(name: String) extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId["module2" *: EmptyTuple] = ModuleId("module2" *: EmptyTuple)
    type Reads = Schema2
    type Writes = Schema2
    type Result = String
    type Event = Nothing

  case class Module3Tx(amount: BigInt) extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId["module3" *: EmptyTuple] = ModuleId("module3" *: EmptyTuple)
    type Reads = Schema3
    type Writes = Schema3
    type Result = BigInt
    type Event = Nothing

  case class UnroutedTx() extends Tx:
    type Reads = EmptyTuple
    type Writes = EmptyTuple
    type Result = Unit
    type Event = Nothing

  case class WrongPathTx() extends Tx with ModuleRoutedTx:
    val moduleId: ModuleId["module3" *: EmptyTuple] = ModuleId("module3" *: EmptyTuple)
    type Reads = EmptyTuple
    type Writes = EmptyTuple
    type Result = Unit
    type Event = Nothing

  def createRoutingBlueprint1(): ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple] =
    val balancesEntry = Entry["balances", Utf8, Long]
    val schema: Schema1 = balancesEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema1, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema1],
          requiresWrites: Requires[signedTx.value.Writes, Schema1],
          ownsTables: Tables[Id, Schema1],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case Module1Tx(value) =>
            StateT.pure((value.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))
          case _ =>
            StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  def createRoutingBlueprint2(): ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple] =
    val accountsEntry = Entry["accounts", Utf8, Utf8]
    val schema: Schema2 = accountsEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema2, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema2],
          requiresWrites: Requires[signedTx.value.Writes, Schema2],
          ownsTables: Tables[Id, Schema2],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case Module2Tx(name) =>
            StateT.pure((name.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))
          case _ =>
            StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  def createRoutingBlueprint3(): ModuleBlueprint[Id, "module3", Schema3, EmptyTuple, EmptyTuple] =
    val tokensEntry = Entry["tokens", Utf8, BigInt]
    val schema: Schema3 = tokensEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, Schema3, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, Schema3],
          requiresWrites: Requires[signedTx.value.Writes, Schema3],
          ownsTables: Tables[Id, Schema3],
          provider: TablesProvider[Id, EmptyTuple],
      ): StoreF[Id][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case Module3Tx(amount) =>
            StateT.pure((amount.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))
          case _ =>
            StateT.pure((null.asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[Id, "module3", Schema3, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[Id],
    )

  test("composeBlueprint routes Module1Tx to first reducer"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Mount to get actual tables
    type Path = "test" *: EmptyTuple
    val module = StateModule.mountComposed[Path](composed)

    val account = Account.Named(Utf8("testuser"))
    val tx = Module1Tx(42L)
    val signedTx = signTx(tx, account)
    val result = module.reducer.apply(signedTx)

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
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Mount to get actual tables
    type Path = "test" *: EmptyTuple
    val module = StateModule.mountComposed[Path](composed)

    val account = Account.Named(Utf8("testuser"))
    val tx = Module2Tx("test")
    val signedTx = signTx(tx, account)
    val result = module.reducer.apply(signedTx)

    val runResult = result.run(null).value
    runResult match
      case Right((_, (value, events))) =>
        assertEquals(value, "test")
        assertEquals(events, List.empty)
      case Left(err) =>
        fail(s"Expected success but got error: $err")

  test("composeBlueprint enforces ModuleRoutedTx constraint at compile time"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Verify that UnroutedTx does NOT compile with composed reducer
    // ComposedBlueprint's reducer requires T <: ModuleRoutedTx constraint
    val errors = compileErrors("""
      val account = Account.Named(Utf8("testuser"))
      val tx = UnroutedTx()
      val signedTx = signTx(tx, account)
      composed.reducer0.apply(signedTx)(using
        summon[Requires[signedTx.value.Reads, Schema1 ++ Schema2]],
        summon[Requires[signedTx.value.Writes, Schema1 ++ Schema2]],
      )
    """)

    // Verify error message indicates the ModuleRoutedTx constraint is not satisfied
    assertNoDiff(
      errors,
      """|error:
        |Found:    (signedTx :
        |  org.sigilaris.core.application.transactions.model.Signed[
        |    CompositionTest.this.UnroutedTx]
        |)
        |Required: org.sigilaris.core.application.transactions.model.Signed[
        |  org.sigilaris.core.application.transactions.model.Tx &
        |  org.sigilaris.core.application.transactions.model.ModuleRoutedTx]
         |      composed.reducer0.apply(signedTx)(using
         |                             ^
         |""".stripMargin
    )

    // Verify that routed transactions DO compile and work correctly
    type Path = "test" *: EmptyTuple
    val module = StateModule.mountComposed[Path](composed)

    val account = Account.Named(Utf8("testuser"))
    val routedTx = Module1Tx(100L)
    val signedRoutedTx = signTx(routedTx, account)
    val result = module.reducer.apply(signedRoutedTx).run(null).value

    result match
      case Right((_, (value, _))) =>
        assertEquals(value, 100L)
      case Left(err) =>
        fail(s"Routed transaction should succeed: $err")

  test("composeBlueprint fails for transaction with wrong module path"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val composed = Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)

    // Mount to get actual reducer
    type Path = "test" *: EmptyTuple
    val module = StateModule.mountComposed[Path](composed)

    val account = Account.Named(Utf8("testuser"))
    val tx = WrongPathTx()
    val signedTx = signTx(tx, account)
    val result = module.reducer.apply(signedTx).run(null).value

    result match
      case Left(_: RoutingFailure) =>
        // Successfully caught routing failure for wrong module path
        assert(true)
      case Left(other) =>
        fail(s"Expected RoutingFailure but got: $other")
      case Right(_) =>
        fail("Expected Left for wrong module path but got Right")

  test("nested composed blueprint routes across merged modules"):
    val bp1 = createRoutingBlueprint1()
    val bp2 = createRoutingBlueprint2()
    val bp3 = createRoutingBlueprint3()

    val composed12 = Blueprint.composeBlueprint[Id, "combined12"](bp1, bp2)
    val nested = Blueprint.composeBlueprint[Id, "nested"](composed12, bp3)

    type Path = "test" *: EmptyTuple
    val module = StateModule.mountComposed[Path](nested)

    val account = Account.Named(Utf8("testuser"))

    val tx1 = Module1Tx(7L)
    val signedTx1 = signTx(tx1, account)
    val result1 = module.reducer.apply(signedTx1).run(null).value
    result1 match
      case Right((_, (value, events))) =>
        assertEquals(value, 7L)
        assertEquals(events, List.empty)
      case Left(err) =>
        fail(s"Expected module1 routing to succeed but failed with $err")

    val tx3 = Module3Tx(BigInt(9))
    val signedTx3 = signTx(tx3, account)
    val result3 = module.reducer.apply(signedTx3).run(null).value
    result3 match
      case Right((_, (value, events))) =>
        assertEquals(value, BigInt(9))
        assertEquals(events, List.empty)
      case Left(err) =>
        fail(s"Expected module3 routing to succeed but failed with $err")
