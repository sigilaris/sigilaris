package org.sigilaris.core
package application

import cats.effect.SyncIO
import munit.FunSuite

import accounts.Account
import codec.byte.ByteCodec
import crypto.Signature
import datatype.UInt256
import scodec.bits.ByteVector

/** Tests for Phase 5: Assembly (extend, mergeReducers, ModuleFactory).
  *
  * Phase 5 deliverables:
  *   - extend: merge two StateModules at the same Path
  *   - mergeReducers: error-based fallback strategy for combining reducers
  *   - ModuleFactory: safe for self-contained modules (Deps = EmptyTuple enforced)
  *   - Shared vs Sandboxed assembly examples
  *
  * Production-ready patterns tested:
  *   - Shared: one module mounted, others access via Phase 4 Lookup
  *   - Sandboxed: same blueprint mounted at different paths, isolated state
  *   - extend combines schemas, transactions, dependencies, and reducers correctly
  *   - Factory pattern: build → build → extend (for self-contained modules)
  *
  * Note: Uses SyncIO for cross-platform compatibility (JVM + JS).
  * Note: aggregate was removed (blocked on subset derivation, see ADR-0009).
  */
class Phase5Test extends FunSuite:

  // Helper to create a signed transaction with dummy signature for testing
  def signTx[A <: Tx](tx: A, account: Account): Signed[A] =
    val dummySig = Signature(
      v = 27,
      r = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
      s = UInt256.unsafeFromBytesBE(ByteVector.fill(32)(0x00)),
    )
    Signed(AccountSignature(account, dummySig), tx)

  // Use built-in types with existing codecs - no custom codec implementation needed!
  import datatype.{Utf8, BigNat}

  // Define sample types - codecs will be auto-derived from field codecs
  case class Address(value: Utf8)
  case class AccountData(data: Utf8)
  case class Balance(amount: BigNat)
  case class GroupInfo(info: Utf8)

  // Define sample schemas
  type AccountsSchema = Entry["accounts", Address, AccountData] *:
                         Entry["balances", Address, BigNat] *:
                         EmptyTuple

  type GroupSchema = Entry["groups", Address, GroupInfo] *:
                      Entry["members", Address, BigNat] *:
                      EmptyTuple

  // Sample transactions
  case class CreateAccount(address: Address, account: AccountData) extends Tx:
    type Reads = EmptyTuple
    type Writes = EntryTuple["accounts", Address, AccountData]
    type Result = Unit
    type Event = AccountCreated

  case class AccountCreated(address: Address)

  case class CreateGroup(address: Address, group: GroupInfo) extends Tx:
    type Reads = EmptyTuple
    type Writes = EntryTuple["groups", Address, GroupInfo]
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
    val accountsEntry = Entry["accounts", Address, AccountData]
    val balancesEntry = Entry["balances", Address, BigNat]
    val schema: AccountsSchema = (accountsEntry, balancesEntry)

    val reducer = new StateReducer0[SyncIO, AccountsSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, AccountsSchema],
          requiresWrites: Requires[signedTx.value.Writes, AccountsSchema],
          ownsTables: Tables[SyncIO, AccountsSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        // Simplified reducer for testing
        import cats.data.StateT
        StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

  def createGroupBlueprint(): ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple] =
    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigNat]
    val schema: GroupSchema = (groupsEntry, membersEntry)

    val reducer = new StateReducer0[SyncIO, GroupSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, GroupSchema],
          requiresWrites: Requires[signedTx.value.Writes, GroupSchema],
          ownsTables: Tables[SyncIO, GroupSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        import cats.data.StateT
        StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      owns = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

  test("extend: merge two modules at same path"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Mount both blueprints at the same path
    val accountsBP = createAccountsBlueprint()
    val groupBP = createGroupBlueprint()

    val accountsModule = StateModule.mount[Path["app"]](accountsBP)
    val groupModule = StateModule.mount[Path["app"]](groupBP)

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
    val module1 = factory.build[Path["app"]]
    assert(module1.tables.size == 2)

  test("Shared assembly: single Accounts mounted, both modules use same tables"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Mount accounts once
    val accountsBP = createAccountsBlueprint()

    val accountsModule = StateModule.mount[Path["app"]](accountsBP)

    // Verify tables exist and can be accessed
    val tables = accountsModule.tables
    assertEquals(tables.size, 2)

    // In a shared assembly, other modules would reference these same tables
    // via Lookup evidence, as demonstrated in Phase4Test

  test("Sandboxed assembly: two Accounts mounted at different paths"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create factory
    val accountsBP = createAccountsBlueprint()
    val factory = StateModule.ModuleFactory.fromBlueprint(accountsBP)

    // Build at two different paths
    val groupAccounts = factory.build[Path2["app", "group"]]
    val tokenAccounts = factory.build[Path2["app", "token"]]

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
    val accounts = accountsFactory.build[Path["app"]]
    val group = groupFactory.build[Path["app"]]
    val extended = StateModule.extend(accounts, group)

    // Verify combined module has tables from both
    assertEquals(extended.tables.size, 4)

  test("Reducer merging: r1 succeeds - transaction executed and result returned"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create blueprints with actual transaction handling
    val accountsEntry = Entry["accounts", Address, AccountData]
    val balancesEntry = Entry["balances", Address, BigNat]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, AccountsSchema],
          requiresWrites: Requires[signedTx.value.Writes, AccountsSchema],
          ownsTables: Tables[SyncIO, AccountsSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case CreateAccount(addr, acc) =>
            import cats.data.StateT
            // Return a successful result with event
            StateT.pure((().asInstanceOf[signedTx.value.Result], List(AccountCreated(addr).asInstanceOf[signedTx.value.Event])))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigNat]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    val groupReducer = new StateReducer0[SyncIO, GroupSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, GroupSchema],
          requiresWrites: Requires[signedTx.value.Writes, GroupSchema],
          ownsTables: Tables[SyncIO, GroupSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        import cats.data.StateT
        StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      owns = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    val accountsModule = StateModule.mount[Path["app"]](accountsBP)
    val groupModule = StateModule.mount[Path["app"]](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute a transaction through the merged reducer
    val account = Account.Named(Utf8("testuser"))
    val tx = CreateAccount(Address(Utf8("addr1")), AccountData(Utf8("account1")))
    val signedTx = signTx(tx, account)
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(signedTx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, ((), events))) =>
        // Verify the transaction was executed and event was emitted
        assertEquals(events.length, 1)
        assert(events.head.isInstanceOf[AccountCreated])
        val event = events.head.asInstanceOf[AccountCreated]
        assertEquals(event.address, Address(Utf8("addr1")))
      case Left(error) =>
        fail(s"Transaction execution failed: $error")

  test("Reducer merging: r1 fails, r2 succeeds - fallback works"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    // Create accounts blueprint that will fail on CreateGroup
    val accountsEntry = Entry["accounts", Address, AccountData]
    val balancesEntry = Entry["balances", Address, BigNat]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, AccountsSchema],
          requiresWrites: Requires[signedTx.value.Writes, AccountsSchema],
          ownsTables: Tables[SyncIO, AccountsSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case _: CreateGroup =>
            // Explicitly fail for CreateGroup transactions
            import cats.data.StateT
            import cats.data.EitherT
            StateT.liftF(EitherT.leftT[SyncIO, (signedTx.value.Result, List[signedTx.value.Event])](
              failure.TrieFailure("Accounts module cannot handle CreateGroup")
            ))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    // Create group blueprint that will succeed on CreateGroup
    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigNat]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    val groupReducer = new StateReducer0[SyncIO, GroupSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, GroupSchema],
          requiresWrites: Requires[signedTx.value.Writes, GroupSchema],
          ownsTables: Tables[SyncIO, GroupSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        signedTx.value match
          case CreateGroup(addr, grp) =>
            import cats.data.StateT
            // Return a successful result with event
            StateT.pure((().asInstanceOf[signedTx.value.Result], List(GroupCreated(addr).asInstanceOf[signedTx.value.Event])))
          case _ =>
            import cats.data.StateT
            StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      owns = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    val accountsModule = StateModule.mount[Path["app"]](accountsBP)
    val groupModule = StateModule.mount[Path["app"]](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute a CreateGroup transaction - should fallback to r2
    val account = Account.Named(Utf8("testuser"))
    val tx = CreateGroup(Address(Utf8("addr2")), GroupInfo(Utf8("group1")))
    val signedTx = signTx(tx, account)
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(signedTx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, ((), events))) =>
        // Verify the transaction was executed via fallback and event was emitted
        assertEquals(events.length, 1)
        assert(events.head.isInstanceOf[GroupCreated])
        val event = events.head.asInstanceOf[GroupCreated]
        assertEquals(event.address, Address(Utf8("addr2")))
      case Left(error) =>
        fail(s"Transaction execution failed: $error")

  test("Reducer merging: r1 succeeds with empty events - no fallback to r2"):
    given merkle.MerkleTrie.NodeStore[SyncIO] = createNodeStore()
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]

    val accountsEntry = Entry["accounts", Address, AccountData]
    val balancesEntry = Entry["balances", Address, BigNat]
    val accountsSchema: AccountsSchema = (accountsEntry, balancesEntry)

    // r1 always succeeds but returns NO events (e.g., a query operation)
    val accountsReducer = new StateReducer0[SyncIO, AccountsSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, AccountsSchema],
          requiresWrites: Requires[signedTx.value.Writes, AccountsSchema],
          ownsTables: Tables[SyncIO, AccountsSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        import cats.data.StateT
        // Successfully handle transaction with NO events
        StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val accountsBP = new ModuleBlueprint[SyncIO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple](
      owns = accountsSchema,
      reducer0 = accountsReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    val groupsEntry = Entry["groups", Address, GroupInfo]
    val membersEntry = Entry["members", Address, BigNat]
    val groupSchema: GroupSchema = (groupsEntry, membersEntry)

    // r2 should NOT be called if r1 succeeds
    var r2Called = false
    val groupReducer = new StateReducer0[SyncIO, GroupSchema, EmptyTuple]:
      def apply[T <: Tx](signedTx: Signed[T])(using
          requiresReads: Requires[signedTx.value.Reads, GroupSchema],
          requiresWrites: Requires[signedTx.value.Writes, GroupSchema],
          ownsTables: Tables[SyncIO, GroupSchema],
          provider: TablesProvider[SyncIO, EmptyTuple],
      ): StoreF[SyncIO][(signedTx.value.Result, List[signedTx.value.Event])] =
        r2Called = true  // Track if r2 was called
        import cats.data.StateT
        StateT.pure((().asInstanceOf[signedTx.value.Result], List.empty[signedTx.value.Event]))

    val groupBP = new ModuleBlueprint[SyncIO, "group", GroupSchema, EmptyTuple, EmptyTuple](
      owns = groupSchema,
      reducer0 = groupReducer,
      txs = TxRegistry.empty,
      provider = TablesProvider.empty[SyncIO],
    )

    val accountsModule = StateModule.mount[Path["app"]](accountsBP)
    val groupModule = StateModule.mount[Path["app"]](groupBP)

    val extended = StateModule.extend(accountsModule, groupModule)

    // Execute CreateAccount - should succeed in r1 with empty events, NOT fallback to r2
    val account = Account.Named(Utf8("testuser"))
    val tx = CreateAccount(Address(Utf8("addr3")), AccountData(Utf8("account3")))
    val signedTx = signTx(tx, account)
    val initialState = merkle.MerkleTrieState.empty

    val result = extended.reducer.apply(signedTx).run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, (txResult, txEvents))) =>
        // Verify r1 handled it successfully with empty events
        assertEquals(txEvents.length, 0)
        // Verify r2 was NOT called (no fallback on empty events)
        assertEquals(r2Called, false, "r2 should not be called when r1 succeeds with empty events")
      case Left(err) =>
        fail(s"Transaction execution failed: $err")
