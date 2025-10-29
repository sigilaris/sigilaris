package org.sigilaris.core
package application

import cats.effect.SyncIO
import munit.FunSuite

import codec.byte.ByteCodec

/** Tests for Phase 4: Dependencies (Requires and Lookup evidence).
  *
  * Phase 4 deliverables:
  *   - Requires[Needs, S] evidence (Needs ⊆ S)
  *   - Lookup[S, Name, K, V] typeclass to obtain a concrete StateTable instance
  *     with exact Name/K/V types preserved for compile-time safety
  *   - Cross-module access pattern in reducers
  *
  * Criteria:
  *   - A reducer that needs Accounts + Token compiles only when S includes both
  *   - Lookup.table returns StateTable[F] { type Name = Name; type K = K; type V = V }
  *     enabling branded key operations without unsafe casts
  *   - Runtime: read from Accounts, write to Token using branded keys
  *
  * Note: Uses SyncIO for cross-platform compatibility (JVM + JS).
  */
class Phase4Test extends FunSuite:

  // Use built-in types with existing codecs - no custom codec implementation needed!
  import datatype.{Utf8, BigNat}
  import scodec.bits.ByteVector

  // Define sample types - codecs will be auto-derived from field codecs
  case class Address(value: Utf8)
  case class Account(data: Utf8)
  case class TokenInfo(info: Utf8)

  // Define sample schemas
  type AccountsSchema = Entry["accounts", Address, Account] *: Entry["balances", Address, BigNat] *: EmptyTuple
  type TokenSchema = Entry["tokens", Address, TokenInfo] *: Entry["balances", Address, BigNat] *: EmptyTuple

  // Combined schema (what would result from composition)
  type CombinedSchema = Entry["accounts", Address, Account] *:
                         Entry["balances", Address, BigNat] *:
                         Entry["tokens", Address, TokenInfo] *:
                         EmptyTuple

  test("Requires evidence: empty tuple requires nothing"):
    // This should compile - EmptyTuple requires nothing from any schema
    summon[Requires[EmptyTuple, AccountsSchema]]
    summon[Requires[EmptyTuple, TokenSchema]]
    summon[Requires[EmptyTuple, EmptyTuple]]

  test("Requires evidence: single entry requires that entry in schema"):
    // These should compile - the required entry exists in the schema
    summon[Requires[EntryTuple["accounts", Address, Account], AccountsSchema]]
    summon[Requires[EntryTuple["balances", Address, BigNat], AccountsSchema]]
    summon[Requires[EntryTuple["tokens", Address, TokenInfo], TokenSchema]]

  test("Requires evidence: multiple entries require all in schema"):
    // This should compile - both required entries exist in AccountsSchema
    summon[Requires[AccountsSchema, AccountsSchema]]

    // This should compile - both entries exist in CombinedSchema
    summon[Requires[AccountsSchema, CombinedSchema]]
    summon[Requires[TokenSchema, CombinedSchema]]

  test("Requires evidence: fails when entry missing from schema"):
    // These compile errors verify that Requires rejects schemas missing required entries
    val err1 = compileErrors("summon[Requires[EntryTuple[\"tokens\", Address, TokenInfo], AccountsSchema]]")
    assert(err1.contains("Cannot prove that all required tables are in the schema"))
    assert(err1.contains("Required tables"))
    assert(err1.contains("Available schema"))

    val err2 = compileErrors("summon[Requires[TokenSchema, AccountsSchema]]")
    assert(err2.contains("Cannot prove that all required tables are in the schema"))
    assert(err2.contains("Transaction requires a table that doesn't exist in the module"))

  test("Lookup evidence: can lookup table at head of schema"):
    val lookup = summon[Lookup[AccountsSchema, "accounts", Address, Account]]
    assert(lookup != null, "Lookup instance should be summoned")

  test("Lookup evidence: can lookup table in tail of schema"):
    val lookup = summon[Lookup[AccountsSchema, "balances", Address, BigNat]]
    assert(lookup != null, "Lookup instance should be summoned")

  test("Lookup evidence: can lookup in combined schema"):
    // All tables from both schemas should be findable
    summon[Lookup[CombinedSchema, "accounts", Address, Account]]
    summon[Lookup[CombinedSchema, "balances", Address, BigNat]]
    summon[Lookup[CombinedSchema, "tokens", Address, TokenInfo]]

  test("Lookup evidence: fails when table name not in schema"):
    // These compile errors verify that Lookup rejects non-existent table names
    val err1 = compileErrors("summon[Lookup[AccountsSchema, \"tokens\", Address, TokenInfo]]")
    assert(err1.contains("Cannot find table"))
    assert(err1.contains("doesn't exist in the schema"))

    val err2 = compileErrors("summon[Lookup[TokenSchema, \"accounts\", Address, Account]]")
    assert(err2.contains("Cannot find table"))

  test("Lookup evidence: fails when types mismatch"):
    // These compile errors verify that Lookup rejects type mismatches
    val err1 = compileErrors("summon[Lookup[AccountsSchema, \"accounts\", Address, BigNat]]")
    assert(err1.contains("Cannot find table"))
    assert(err1.contains("Table exists but with different key/value types"))

    val err2 = compileErrors("summon[Lookup[AccountsSchema, \"balances\", Address, Account]]")
    assert(err2.contains("Cannot find table"))
    assert(err2.contains("Table exists but with different key/value types"))

  // Runtime test: verify that Lookup can extract the correct table instance
  test("Lookup runtime: extract table from Tables tuple"):
    import merkle.{MerkleTrie, MerkleTrieNode}
    import cats.data.Kleisli
    import cats.data.EitherT

    // Create a simple in-memory node store
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    given MerkleTrie.NodeStore[SyncIO] = Kleisli: hash =>
      EitherT.rightT[SyncIO, String](store.get(hash))

    // Create Entry instances (these capture codecs)
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigNat]

    // Create tables at different prefixes
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]
    val accountsTable = accountsEntry.createTable[SyncIO](ByteVector(0x01))
    val balancesTable = balancesEntry.createTable[SyncIO](ByteVector(0x02))

    // Create Tables tuple
    val tables: Tables[SyncIO, AccountsSchema] = (accountsTable, balancesTable)

    // Use Lookup to extract balances table
    val lookup = summon[Lookup[AccountsSchema, "balances", Address, BigNat]]
    val extractedTable = lookup.table[SyncIO](tables)

    // Verify it's the correct table instance
    assertEquals(extractedTable.name, "balances")

  test("Cross-module access: reducer can read/write using branded keys"):
    import merkle.{MerkleTrie, MerkleTrieNode}
    import cats.data.Kleisli
    import cats.data.EitherT

    // Create node store
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    given MerkleTrie.NodeStore[SyncIO] = Kleisli: hash =>
      EitherT.rightT[SyncIO, String](store.get(hash))

    // Helper to persist diff
    def saveNodes(state: merkle.MerkleTrieState): Unit =
      state.diff.toMap.foreach { case (hash, (node, _)) =>
        store.put(hash, node)
      }

    // Create tables for combined schema
    given cats.Monad[SyncIO] = cats.effect.Sync[SyncIO]
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigNat]
    val tokensEntry = Entry["tokens", Address, TokenInfo]

    val accountsTable = accountsEntry.createTable[SyncIO](ByteVector(0x01))
    val balancesTable = balancesEntry.createTable[SyncIO](ByteVector(0x02))
    val tokensTable = tokensEntry.createTable[SyncIO](ByteVector(0x03))

    val tables: Tables[SyncIO, CombinedSchema] = (accountsTable, balancesTable, tokensTable)

    // Simulate a reducer that reads from Accounts and writes to Token
    // This demonstrates the Phase 4 requirement: "read from Accounts, write to Token using branded keys"
    def crossModuleOperation(using
        accountsReq: Requires[EntryTuple["accounts", Address, Account], CombinedSchema],
        balancesReq: Requires[EntryTuple["balances", Address, BigNat], CombinedSchema],
        tokensReq: Requires[EntryTuple["tokens", Address, TokenInfo], CombinedSchema],
    )(using
        accountsLookup: Lookup[CombinedSchema, "accounts", Address, Account],
        balancesLookup: Lookup[CombinedSchema, "balances", Address, BigNat],
        tokensLookup: Lookup[CombinedSchema, "tokens", Address, TokenInfo],
    ): StoreF[SyncIO][(Option[Account], Option[BigNat], Option[TokenInfo], Option[Account])] =
      // Extract tables - now the types are preserved!
      val accounts = accountsLookup.table[SyncIO](tables)
      val balances = balancesLookup.table[SyncIO](tables)
      val tokens = tokensLookup.table[SyncIO](tables)

      // Create test data
      val addr1 = Address(Utf8("addr1"))
      val addr2 = Address(Utf8("addr2"))

      val account1 = Account(Utf8("account_data_1"))
      val balance1 = BigNat.unsafeFromLong(1000L)
      val tokenInfo = TokenInfo(Utf8("token_info_1"))

      // Brand keys with table-specific types - this is compile-time safe!
      val accountKey1 = accounts.brand(addr1)
      val accountKey2 = accounts.brand(addr2)
      val balanceKey1 = balances.brand(addr1)
      val tokenKey = tokens.brand(addr1)

      // The following would NOT compile (key type mismatch):
      // balances.get(accountKey1)  // Error: accountKey1 is branded for accounts table
      // accounts.get(balanceKey1)  // Error: balanceKey1 is branded for balances table

      for
        // Write to accounts table
        _ <- accounts.put(accountKey1, account1)

        // Read from accounts table
        maybeAccount <- accounts.get(accountKey1)

        // Write to balances table
        _ <- balances.put(balanceKey1, balance1)

        // Read from balances table
        maybeBalance <- balances.get(balanceKey1)

        // Write to tokens table (different module)
        _ <- tokens.put(tokenKey, tokenInfo)

        // Read from tokens table
        maybeToken <- tokens.get(tokenKey)

        // Verify that non-existent keys return None
        notFound <- accounts.get(accountKey2)

      yield (maybeAccount, maybeBalance, maybeToken, notFound)

    // Execute the cross-module operation
    val initialState = merkle.MerkleTrieState.empty
    val result = crossModuleOperation.run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, (maybeAccount, maybeBalance, maybeToken, notFound))) =>
        // Verify all results
        assertEquals(maybeAccount, Some(Account(Utf8("account_data_1"))))
        assertEquals(maybeBalance, Some(BigNat.unsafeFromLong(1000L)))
        assertEquals(maybeToken, Some(TokenInfo(Utf8("token_info_1"))))
        assertEquals(notFound, None)

        // Persist the state changes
        saveNodes(finalState)

        // Verify we can read the persisted data
        val accountsLookup = summon[Lookup[CombinedSchema, "accounts", Address, Account]]
        val accounts = accountsLookup.table[SyncIO](tables)
        val addr = Address(Utf8("addr1"))
        val key = accounts.brand(addr)

        val verifyRead = accounts.get(key).runA(finalState).value
        val verified = verifyRead.unsafeRunSync()

        verified match
          case Right(Some(account)) =>
            assertEquals(account, Account(Utf8("account_data_1")))
          case other =>
            fail(s"Expected Some(account), got $other")

      case Left(error) =>
        fail(s"Cross-module operation failed: $error")
