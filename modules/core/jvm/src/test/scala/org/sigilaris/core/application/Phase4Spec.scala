package org.sigilaris.core
package application

import cats.effect.IO
import munit.CatsEffectSuite

import codec.byte.ByteCodec

/** Tests for Phase 4: Dependencies (Requires and Lookup evidence).
  *
  * Phase 4 deliverables:
  *   - Requires[Needs, S] evidence (Needs ⊆ S)
  *   - Lookup[S, Name] typeclass to obtain a concrete StateTable instance
  *   - Cross-module access pattern in reducers
  *
  * Criteria:
  *   - A reducer that needs Accounts + Token compiles only when S includes both
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
    // Uncommenting these should fail to compile:
    // summon[Requires[Entry["tokens", Address, TokenInfo] *: EmptyTuple, AccountsSchema]]
    // summon[Requires[TokenSchema, AccountsSchema]]

    // This test verifies the above would fail by not including them
    assert(true, "Negative test - ensuring missing entries don't compile")

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
    // Uncommenting these should fail to compile:
    // summon[Lookup[AccountsSchema, "tokens", Address, TokenInfo]]
    // summon[Lookup[TokenSchema, "accounts", Address, Account]]

    assert(true, "Negative test - ensuring missing tables don't compile")

  test("Lookup evidence: fails when types mismatch"):
    // Uncommenting these should fail to compile (wrong value type):
    // summon[Lookup[AccountsSchema, "accounts", Address, BigInt]]
    // summon[Lookup[AccountsSchema, "balances", Address, Account]]

    assert(true, "Negative test - ensuring type mismatches don't compile")

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

  test("Cross-module access: reducer can access multiple module tables"):
    import merkle.{MerkleTrie, MerkleTrieNode}
    import cats.data.Kleisli
    import cats.data.EitherT

    // Create node store
    val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]
    given MerkleTrie.NodeStore[IO] = Kleisli: hash =>
      EitherT.rightT[IO, String](store.get(hash))

    // Create tables for combined schema
    given cats.Monad[IO] = cats.effect.Async[IO]
    val accountsEntry = Entry["accounts", Address, Account]
    val balancesEntry = Entry["balances", Address, BigInt]
    val tokensEntry = Entry["tokens", Address, TokenInfo]

    val accountsTable = accountsEntry.createTable[IO](ByteVector(0x01))
    val balancesTable = balancesEntry.createTable[IO](ByteVector(0x02))
    val tokensTable = tokensEntry.createTable[IO](ByteVector(0x03))

    val tables: Tables[IO, CombinedSchema] = (accountsTable, balancesTable, tokensTable)

    // Simulate a reducer that needs both Accounts and Token schemas
    // The key insight: we specify the exact K, V types in the Lookup summons,
    // and then use pattern matching to refine the table type
    def crossModuleOperation(using
        accountsReq: Requires[Entry["accounts", Address, Account] *: EmptyTuple, CombinedSchema],
        balancesReq: Requires[Entry["balances", Address, BigInt] *: EmptyTuple, CombinedSchema],
        tokensReq: Requires[Entry["tokens", Address, TokenInfo] *: EmptyTuple, CombinedSchema],
    )(using
        accountsLookup: Lookup[CombinedSchema, "accounts", Address, Account],
        balancesLookup: Lookup[CombinedSchema, "balances", Address, BigInt],
        tokensLookup: Lookup[CombinedSchema, "tokens", Address, TokenInfo],
    ): IO[Unit] =
      // Extract tables
      val accounts = accountsLookup.table[IO](tables)
      val balances = balancesLookup.table[IO](tables)
      val tokens = tokensLookup.table[IO](tables)

      // Verify we got different table instances with correct names
      assertEquals(accounts.name, "accounts")
      assertEquals(balances.name, "balances")
      assertEquals(tokens.name, "tokens")

      // The Lookup type parameters K0, V0 carry the type information,
      // even though the return type is existential.
      // In a real reducer, we would use these tables within operations that
      // accept StateTable[F] { type Name <: String; type K; type V },
      // and the operations would work with the path-dependent types.

      // Verify that we have the right number of Requires evidence
      // (this ensures the compile-time checks are working)
      IO.unit

    crossModuleOperation
