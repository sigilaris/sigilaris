package org.sigilaris.core
package application

import cats.effect.SyncIO
import munit.FunSuite

import codec.byte.ByteCodec

/** Tests for Phase 5: Assembly (extend, aggregate, ModuleFactory).
  *
  * Phase 5 deliverables:
  *   - extend: merge two StateModules at the same Path
  *   - aggregate: combine ModuleFactories for flexible deployment
  *   - mergeReducers: strategy for combining reducers
  *   - Shared vs Sandboxed assembly examples
  *
  * Criteria:
  *   - Shared: one module mounted, both use same tables
  *   - Sandboxed: same blueprint mounted at different paths, isolated state
  *   - extend combines schemas, transactions, and reducers correctly
  *   - aggregate enables reusable module combinations
  *
  * Note: Uses SyncIO for cross-platform compatibility (JVM + JS).
  */
class Phase5Spec extends FunSuite:

  // Define sample types for testing
  import scodec.bits.ByteVector
  import codec.byte.{ByteEncoder, ByteDecoder}
  import codec.byte.DecodeResult
  import cats.syntax.either.*

  case class Address(bytes: ByteVector)
  case class Account(bytes: ByteVector)
  case class Balance(amount: BigInt)
  case class GroupInfo(bytes: ByteVector)

  // ByteCodec instances
  given ByteCodec[ByteVector] = new ByteCodec[ByteVector]:
    def encode(value: ByteVector): ByteVector = value
    def decode(bytes: ByteVector) = DecodeResult(bytes, ByteVector.empty).asRight

  given ByteCodec[Address] = new ByteCodec[Address]:
    def encode(value: Address): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(Address(bytes), ByteVector.empty).asRight

  given ByteCodec[Account] = new ByteCodec[Account]:
    def encode(value: Account): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(Account(bytes), ByteVector.empty).asRight

  given ByteCodec[Balance] = new ByteCodec[Balance]:
    def encode(value: Balance): ByteVector = ByteEncoder.bigintByteEncoder.encode(value.amount)
    def decode(bytes: ByteVector) =
      ByteDecoder.bigintByteDecoder.decode(bytes).map(r => r.copy(value = Balance(r.value)))

  given ByteCodec[BigInt] = new ByteCodec[BigInt]:
    def encode(value: BigInt): ByteVector = ByteEncoder.bigintByteEncoder.encode(value)
    def decode(bytes: ByteVector) = ByteDecoder.bigintByteDecoder.decode(bytes)

  given ByteCodec[GroupInfo] = new ByteCodec[GroupInfo]:
    def encode(value: GroupInfo): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(GroupInfo(bytes), ByteVector.empty).asRight

  // Define sample schemas
  type AccountsSchema = Entry["accounts", Address, Account] *:
                         Entry["balances", Address, BigInt] *:
                         EmptyTuple

  type GroupSchema = Entry["groups", Address, GroupInfo] *:
                      Entry["members", Address, BigInt] *:
                      EmptyTuple

  // Sample transactions
  case class CreateAccount(address: Address, account: Account) extends Tx:
    type Reads = EmptyTuple
    type Writes = Entry["accounts", Address, Account] *: EmptyTuple
    type Result = Unit
    type Event = AccountCreated

  case class AccountCreated(address: Address)

  case class CreateGroup(address: Address, group: GroupInfo) extends Tx:
    type Reads = EmptyTuple
    type Writes = Entry["groups", Address, GroupInfo] *: EmptyTuple
    type Result = Unit
    type Event = GroupCreated

  case class GroupCreated(address: Address)

  // Helper to create node store
  def createNodeStore(): merkle.MerkleTrie.NodeStore[SyncIO] =
    import cats.data.{Kleisli, EitherT}
    import merkle.{MerkleTrieNode}
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    Kleisli: hash =>
      EitherT.rightT[SyncIO, String](store.get(hash))

  // Helper to create sample blueprints
  def createAccountsBlueprint(): ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple] =
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val schema: AccountsSchema = (accountsEntry, balancesEntry)

    val reducer = new StateReducer0[SyncIO, AccountsSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, AccountsSchema],
          requiresWrites: Requires[tx.Writes, AccountsSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        // Simplified reducer for testing
        import cats.data.StateT
        StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  def createGroupBlueprint(): ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple] =
    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigInt]
    val schema: GroupSchema = (groupsEntry, membersEntry)

    val reducer = new StateReducer0[SyncIO, GroupSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, GroupSchema],
          requiresWrites: Requires[tx.Writes, GroupSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        import cats.data.StateT
        StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  test("extend: merge two modules at same path"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Mount both blueprints at the same path
    val accountsBP = createAccountsBlueprint()
    val groupBP = createGroupBlueprint()

    val accountsModule = StateModule.mount[SyncIO, "accounts", ("app" *: EmptyTuple), AccountsSchema, EmptyTuple, EmptyTuple](accountsBP)
    val groupModule = StateModule.mount[SyncIO, "group", ("app" *: EmptyTuple), GroupSchema, EmptyTuple, EmptyTuple](groupBP)

    // Extend them
    val extended = StateModule.extend(accountsModule, groupModule)

    // Verify the combined module has tables from both
    val tables = extended.tables
    assert(tables.size == 4, s"Expected 4 tables, got ${tables.size}")

  test("ModuleFactory: create factory from blueprint"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    val accountsBP = createAccountsBlueprint()
    val factory = StateModule.ModuleFactory.fromBlueprint(accountsBP)

    // Build at one path
    val module1 = factory.build[("app" *: EmptyTuple)]
    assert(module1.tables.size == 2)

  test("Shared assembly: single Accounts mounted, both modules use same tables"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Mount accounts once
    val accountsBP = createAccountsBlueprint()

    val accountsModule = StateModule.mount[SyncIO, "accounts", ("app" *: EmptyTuple), AccountsSchema, EmptyTuple, EmptyTuple](accountsBP)

    // Verify tables exist and can be accessed
    val tables = accountsModule.tables
    assertEquals(tables.size, 2)

    // In a shared assembly, other modules would reference these same tables
    // via Lookup evidence, as demonstrated in Phase4Spec

  test("Sandboxed assembly: two Accounts mounted at different paths"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create factory
    val accountsBP = createAccountsBlueprint()
    val factory = StateModule.ModuleFactory.fromBlueprint(accountsBP)

    // Build at two different paths
    val groupAccounts = factory.build[("app" *: "group" *: EmptyTuple)]
    val tokenAccounts = factory.build[("app" *: "token" *: EmptyTuple)]

    // Verify both have tables, but at different prefixes
    assertEquals(groupAccounts.tables.size, 2)
    assertEquals(tokenAccounts.tables.size, 2)

    // The tables are independent - different prefixes mean isolated state
    // This is verified by the prefix encoding in PathEncoding

  test("Factory pattern: build at same path, then extend"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    val accountsBP = createAccountsBlueprint()
    val groupBP = createGroupBlueprint()

    val accountsFactory = StateModule.ModuleFactory.fromBlueprint(accountsBP)
    val groupFactory = StateModule.ModuleFactory.fromBlueprint(groupBP)

    // ✅ PRODUCTION-READY PATTERN: Build factories at same path, then extend
    // This is the proven, safe approach with transaction execution tests
    val accounts = accountsFactory.build[("app" *: EmptyTuple)]
    val group = groupFactory.build[("app" *: EmptyTuple)]
    val extended = StateModule.extend(accounts, group)

    // Verify combined module has tables from both
    assertEquals(extended.tables.size, 4)

    // Note: StateModule.aggregate exists but is private[application] (fabricates evidence)
    // It remains internal until proper subset derivation is implemented

  test("Reducer merging: r1 succeeds - transaction executed and result returned"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create blueprints with actual transaction handling
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, AccountsSchema],
          requiresWrites: Requires[tx.Writes, AccountsSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        tx match
          case CreateAccount(addr, acc) =>
            import cats.data.StateT
            // Return a successful result with event
            StateT.pure((().asInstanceOf[tx.Result], List(AccountCreated(addr).asInstanceOf[tx.Event])))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      schema = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigInt]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    val groupReducer = new StateReducer0[SyncIO, GroupSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, GroupSchema],
          requiresWrites: Requires[tx.Writes, GroupSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        import cats.data.StateT
        StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      schema = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    val accountsModule = StateModule.mount[SyncIO, "accounts", ("app" *: EmptyTuple), AccountsSchema, EmptyTuple, EmptyTuple](accountsBP)
    val groupModule = StateModule.mount[SyncIO, "group", ("app" *: EmptyTuple), GroupSchema, EmptyTuple, EmptyTuple](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute a transaction through the merged reducer
    val tx = CreateAccount(Address(ByteVector(0x01)), Account(ByteVector(0xaa)))
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(tx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, ((), events))) =>
        // Verify the transaction was executed and event was emitted
        assertEquals(events.length, 1)
        assert(events.head.isInstanceOf[AccountCreated])
        val event = events.head.asInstanceOf[AccountCreated]
        assertEquals(event.address, Address(ByteVector(0x01)))
      case Left(error) =>
        fail(s"Transaction execution failed: $error")

  test("Reducer merging: r1 fails, r2 succeeds - fallback works"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create accounts blueprint that will fail on CreateGroup
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, AccountsSchema],
          requiresWrites: Requires[tx.Writes, AccountsSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        tx match
          case _: CreateGroup =>
            // Explicitly fail for CreateGroup transactions
            import cats.data.StateT
            import cats.data.EitherT
            StateT.liftF(EitherT.leftT[SyncIO, (tx.Result, List[tx.Event])](
              failure.TrieFailure("Accounts module cannot handle CreateGroup")
            ))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      schema = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    // Create group blueprint that will succeed on CreateGroup
    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigInt]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    val groupReducer = new StateReducer0[SyncIO, GroupSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, GroupSchema],
          requiresWrites: Requires[tx.Writes, GroupSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        tx match
          case CreateGroup(addr, grp) =>
            import cats.data.StateT
            // Return a successful result with event
            StateT.pure((().asInstanceOf[tx.Result], List(GroupCreated(addr).asInstanceOf[tx.Event])))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      schema = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    val accountsModule = StateModule.mount[SyncIO, "accounts", ("app" *: EmptyTuple), AccountsSchema, EmptyTuple, EmptyTuple](accountsBP)
    val groupModule = StateModule.mount[SyncIO, "group", ("app" *: EmptyTuple), GroupSchema, EmptyTuple, EmptyTuple](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute a CreateGroup transaction - should fallback to r2
    val tx = CreateGroup(Address(ByteVector(0x02)), GroupInfo(ByteVector(0xbb)))
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(tx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, ((), events))) =>
        // Verify the transaction was executed via fallback and event was emitted
        assertEquals(events.length, 1)
        assert(events.head.isInstanceOf[GroupCreated])
        val event = events.head.asInstanceOf[GroupCreated]
        assertEquals(event.address, Address(ByteVector(0x02)))
      case Left(error) =>
        fail(s"Transaction execution failed: $error")

  test("Reducer merging: r1 succeeds with empty events - no fallback to r2"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    // r1 always succeeds but returns NO events (e.g., a query operation)
    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, AccountsSchema],
          requiresWrites: Requires[tx.Writes, AccountsSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        import cats.data.StateT
        // Successfully handle transaction with NO events
        StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      schema = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigInt]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    // r2 should NOT be called if r1 succeeds
    var r2Called = false
    val groupReducer = new StateReducer0[SyncIO, GroupSchema]:
      def apply[T <: Tx](tx: T)(using
          requiresReads: Requires[tx.Reads, GroupSchema],
          requiresWrites: Requires[tx.Writes, GroupSchema],
      ): StoreF[SyncIO][(tx.Result, List[tx.Event])] =
        r2Called = true  // Track if r2 was called
        import cats.data.StateT
        StateT.pure((().asInstanceOf[tx.Result], List.empty[tx.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      schema = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

    val accountsModule = StateModule.mount[SyncIO, "accounts", ("app" *: EmptyTuple), AccountsSchema, EmptyTuple, EmptyTuple](accountsBP)
    val groupModule = StateModule.mount[SyncIO, "group", ("app" *: EmptyTuple), GroupSchema, EmptyTuple, EmptyTuple](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute CreateAccount - should succeed in r1 with empty events, NOT fallback to r2
    val tx = CreateAccount(Address(ByteVector(0x03)), Account(ByteVector(0xcc)))
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(tx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, (txResult, txEvents))) =>
        // Verify r1 handled it successfully with empty events
        assertEquals(txEvents.length, 0)
        // Verify r2 was NOT called (no fallback on empty events)
        assertEquals(r2Called, false, "r2 should not be called when r1 succeeds with empty events")
      case Left(err) =>
        fail(s"Transaction execution failed: $err")
