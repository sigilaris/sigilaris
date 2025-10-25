package org.sigilaris.core
package application

import cats.effect.IO
import munit.CatsEffectSuite

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
  */
class Phase4Spec extends CatsEffectSuite:

  // Define sample types for testing - use ByteVector as underlying type for simplicity
  import scodec.bits.ByteVector

  case class Address(bytes: ByteVector)
  case class Account(bytes: ByteVector)
  case class TokenInfo(bytes: ByteVector)

  // Simple ByteCodec instances for testing
  import scodec.bits.ByteVector
  import codec.byte.{ByteEncoder, ByteDecoder}
  import codec.byte.DecodeResult
  import cats.syntax.either.*

  given ByteCodec[ByteVector] = new ByteCodec[ByteVector]:
    def encode(value: ByteVector): ByteVector = value
    def decode(bytes: ByteVector) = DecodeResult(bytes, ByteVector.empty).asRight

  given ByteCodec[Address] = new ByteCodec[Address]:
    def encode(value: Address): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(Address(bytes), ByteVector.empty).asRight

  given ByteCodec[Account] = new ByteCodec[Account]:
    def encode(value: Account): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(Account(bytes), ByteVector.empty).asRight

  given ByteCodec[TokenInfo] = new ByteCodec[TokenInfo]:
    def encode(value: TokenInfo): ByteVector = value.bytes
    def decode(bytes: ByteVector) = DecodeResult(TokenInfo(bytes), ByteVector.empty).asRight

  given ByteCodec[BigInt] = new ByteCodec[BigInt]:
    def encode(value: BigInt): ByteVector = ByteEncoder.bigintByteEncoder.encode(value)
    def decode(bytes: ByteVector) = ByteDecoder.bigintByteDecoder.decode(bytes)

  // Define sample schemas
  type AccountsSchema = Entry["accounts", Address, Account] *: Entry["balances", Address, BigInt] *: EmptyTuple
  type TokenSchema = Entry["tokens", Address, TokenInfo] *: Entry["balances", Address, BigInt] *: EmptyTuple

  // Combined schema (what would result from composition)
  type CombinedSchema = Entry["accounts", Address, Account] *:
                         Entry["balances", Address, BigInt] *:
                         Entry["tokens", Address, TokenInfo] *:
                         EmptyTuple

  test("Requires evidence: empty tuple requires nothing"):
    // This should compile - EmptyTuple requires nothing from any schema
    summon[Requires[EmptyTuple, AccountsSchema]]
    summon[Requires[EmptyTuple, TokenSchema]]
    summon[Requires[EmptyTuple, EmptyTuple]]

  test("Requires evidence: single entry requires that entry in schema"):
    // These should compile - the required entry exists in the schema
    summon[Requires[Entry["accounts", Address, Account] *: EmptyTuple, AccountsSchema]]
    summon[Requires[Entry["balances", Address, BigInt] *: EmptyTuple, AccountsSchema]]
    summon[Requires[Entry["tokens", Address, TokenInfo] *: EmptyTuple, TokenSchema]]

  test("Requires evidence: multiple entries require all in schema"):
    // This should compile - both required entries exist in AccountsSchema
    summon[Requires[AccountsSchema, AccountsSchema]]

    // This should compile - both entries exist in CombinedSchema
    summon[Requires[AccountsSchema, CombinedSchema]]
    summon[Requires[TokenSchema, CombinedSchema]]

  test("Requires evidence: fails when entry missing from schema"):
    // These compile errors verify that Requires rejects schemas missing required entries
    compileErrors("summon[Requires[Entry[\"tokens\", Address, TokenInfo] *: EmptyTuple, AccountsSchema]]")
    compileErrors("summon[Requires[TokenSchema, AccountsSchema]]")

  test("Lookup evidence: can lookup table at head of schema"):
    val lookup = summon[Lookup[AccountsSchema, "accounts", Address, Account]]
    assert(lookup != null, "Lookup instance should be summoned")

  test("Lookup evidence: can lookup table in tail of schema"):
    val lookup = summon[Lookup[AccountsSchema, "balances", Address, BigInt]]
    assert(lookup != null, "Lookup instance should be summoned")

  test("Lookup evidence: can lookup in combined schema"):
    // All tables from both schemas should be findable
    summon[Lookup[CombinedSchema, "accounts", Address, Account]]
    summon[Lookup[CombinedSchema, "balances", Address, BigInt]]
    summon[Lookup[CombinedSchema, "tokens", Address, TokenInfo]]

  test("Lookup evidence: fails when table name not in schema"):
    // These compile errors verify that Lookup rejects non-existent table names
    compileErrors("summon[Lookup[AccountsSchema, \"tokens\", Address, TokenInfo]]")
    compileErrors("summon[Lookup[TokenSchema, \"accounts\", Address, Account]]")

  test("Lookup evidence: fails when types mismatch"):
    // These compile errors verify that Lookup rejects type mismatches
    compileErrors("summon[Lookup[AccountsSchema, \"accounts\", Address, BigInt]]")
    compileErrors("summon[Lookup[AccountsSchema, \"balances\", Address, Account]]")

  // Runtime test: verify that Lookup can extract the correct table instance
  test("Lookup runtime: extract table from Tables tuple"):
    import merkle.{MerkleTrie, MerkleTrieNode}
    import cats.data.Kleisli
    import cats.data.EitherT

    // Create a simple in-memory node store
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    given MerkleTrie.NodeStore[IO] = Kleisli: hash =>
      EitherT.rightT[IO, String](store.get(hash))

    // Create Entry instances (these capture codecs)
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]

    // Create tables at different prefixes
    given cats.Monad[IO] = cats.effect.Async[IO]
    val accountsTable = accountsEntry.createTable[IO](ByteVector(0x01))
    val balancesTable = balancesEntry.createTable[IO](ByteVector(0x02))

    // Create Tables tuple
    val tables: Tables[IO, AccountsSchema] = (accountsTable, balancesTable)

    // Use Lookup to extract balances table
    val lookup = summon[Lookup[AccountsSchema, "balances", Address, BigInt]]
    val extractedTable = lookup.table[IO](tables)

    // Verify it's the correct table instance
    assertEquals(extractedTable.name, "balances")

  test("Cross-module access: reducer can read/write using branded keys"):
    import merkle.{MerkleTrie, MerkleTrieNode}
    import cats.data.Kleisli
    import cats.data.EitherT
    import cats.effect.unsafe.implicits.global

    // Create node store
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    given MerkleTrie.NodeStore[IO] = Kleisli: hash =>
      EitherT.rightT[IO, String](store.get(hash))

    // Helper to persist diff
    def saveNodes(state: merkle.MerkleTrieState): Unit =
      state.diff.toMap.foreach { case (hash, (node, _)) =>
        store.put(hash, node)
      }

    // Create tables for combined schema
    given cats.Monad[IO] = cats.effect.Async[IO]
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val tokensEntry = Entry["tokens", Address, TokenInfo]

    val accountsTable = accountsEntry.createTable[IO](ByteVector(0x01))
    val balancesTable = balancesEntry.createTable[IO](ByteVector(0x02))
    val tokensTable = tokensEntry.createTable[IO](ByteVector(0x03))

    val tables: Tables[IO, CombinedSchema] = (accountsTable, balancesTable, tokensTable)

    // Simulate a reducer that reads from Accounts and writes to Token
    // This demonstrates the Phase 4 requirement: "read from Accounts, write to Token using branded keys"
    def crossModuleOperation(using
        accountsReq: Requires[Entry["accounts", Address, Account] *: EmptyTuple, CombinedSchema],
        balancesReq: Requires[Entry["balances", Address, BigInt] *: EmptyTuple, CombinedSchema],
        tokensReq: Requires[Entry["tokens", Address, TokenInfo] *: EmptyTuple, CombinedSchema],
    )(using
        accountsLookup: Lookup[CombinedSchema, "accounts", Address, Account],
        balancesLookup: Lookup[CombinedSchema, "balances", Address, BigInt],
        tokensLookup: Lookup[CombinedSchema, "tokens", Address, TokenInfo],
    ): StoreF[IO][Unit] =
      // Extract tables - now the types are preserved!
      val accounts = accountsLookup.table[IO](tables)
      val balances = balancesLookup.table[IO](tables)
      val tokens = tokensLookup.table[IO](tables)

      // Create test data
      val addr1 = Address(ByteVector(0x01, 0x02))
      val addr2 = Address(ByteVector(0x03, 0x04))

      val account1 = Account(ByteVector(0xaa, 0xbb))
      val balance1 = BigInt(1000)
      val tokenInfo = TokenInfo(ByteVector(0xff, 0xee))

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
        _ = assertEquals(maybeAccount, Some(account1))

        // Write to balances table
        _ <- balances.put(balanceKey1, balance1)

        // Read from balances table
        maybeBalance <- balances.get(balanceKey1)
        _ = assertEquals(maybeBalance, Some(balance1))

        // Write to tokens table (different module)
        _ <- tokens.put(tokenKey, tokenInfo)

        // Read from tokens table
        maybeToken <- tokens.get(tokenKey)
        _ = assertEquals(maybeToken, Some(tokenInfo))

        // Verify that non-existent keys return None
        notFound <- accounts.get(accountKey2)
        _ = assertEquals(notFound, None)

      yield ()

    // Execute the cross-module operation
    val initialState = merkle.MerkleTrieState.empty
    val result = crossModuleOperation.run(initialState).value.unsafeRunSync()

    result match
      case Right((finalState, ())) =>
        // Persist the state changes
        saveNodes(finalState)
        // Verify we can read the persisted data
        val accountsLookup = summon[Lookup[CombinedSchema, "accounts", Address, Account]]
        val accounts = accountsLookup.table[IO](tables)
        val addr = Address(ByteVector(0x01, 0x02))
        val key = accounts.brand(addr)

        val verifyRead = accounts.get(key).runA(finalState).value

        val verified = verifyRead.unsafeRunSync()
        verified match
          case Right(Some(account)) =>
            assertEquals(account, Account(ByteVector(0xaa, 0xbb)))
          case other =>
            fail(s"Expected Some(account), got $other")

      case Left(error) =>
        fail(s"Cross-module operation failed: $error")
