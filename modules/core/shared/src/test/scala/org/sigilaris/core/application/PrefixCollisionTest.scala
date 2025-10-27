package org.sigilaris.core
package application

import cats.Id
import cats.data.{EitherT, Kleisli}

import munit.FunSuite

import datatype.Utf8
import merkle.{MerkleTrie, MerkleTrieNode}

/** Tests for compile-time prefix collision detection.
  *
  * ADR-0009 Phase 3 requirements:
  * - Provide minimal `PrefixFreePath` by checking encoded prefixes with a runtime validator in tests
  * - Validate prefix-free over all tables in composed module
  * - Criteria: collisions are detected
  *
  * Test strategy (TDD):
  * 1. Write tests that SHOULD fail to compile when there are prefix collisions
  * 2. Verify current permissive implementation allows these (tests will use compileErrors)
  * 3. Implement actual PrefixFreePath validation
  * 4. Verify tests pass (collision cases fail to compile, valid cases compile)
  */
@SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.AsInstanceOf"))
class PrefixCollisionTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  // Test Case 1: Identical table names in same schema
  test("PrefixFreePath should reject identical table names in same schema"):
    // This should NOT compile - duplicate table names
    val errors = compileErrors("""
      type DuplicateSchema = Entry["accounts", Utf8, Long] *:
                             Entry["accounts", Utf8, Utf8] *:
                             EmptyTuple
      summon[PrefixFreePath["app" *: EmptyTuple, DuplicateSchema]]
    """)

    // Verify key parts of error message
    // Note: We use key assertions instead of assertNoDiff because:
    // 1. NotGiven is from stdlib and we can't customize its @implicitNotFound
    // 2. The error trace is very long and type-parameter-order-sensitive
    // 3. But the key information is still clear and verifiable
    assert(errors.contains("Cannot prove that all table prefixes are prefix-free"),
           "Should show top-level PrefixFreePath constraint failure")
    assert(errors.contains("accounts"),
           "Should mention the duplicate table name")
    assert(errors.contains("NotGiven") || errors.contains("DifferentNames"),
           "Should show the underlying constraint in error trace")

  // Test Case 2: Prefix relationship between table names
  test("PrefixFreePath should reject table names with prefix relationship"):
    // If encoding is naive, "user" could be a prefix of "user_data"
    // With proper length-prefix encoding, this should be safe
    // But we should still detect if the encoded bytes have prefix relationship
    type PrefixRelatedSchema = Entry["user", Utf8, Long] *:
                                Entry["user_data", Utf8, Utf8] *:
                                EmptyTuple

    // This test verifies our encoding is actually prefix-free
    // With length-prefix encoding, these should be OK
    val evidence = summon[PrefixFreePath["app" *: EmptyTuple, PrefixRelatedSchema]]
    assert(evidence != null, "Length-prefix encoding should make these prefix-free")

  // Test Case 3: Same table name at different depths (edge case)
  test("PrefixFreePath should handle same table name at different path depths"):
    type Schema1 = Entry["data", Utf8, Long] *: EmptyTuple
    type Schema2 = Entry["data", Utf8, Utf8] *: EmptyTuple

    // Same table name but different paths should be OK
    val evidence1 = summon[PrefixFreePath[("app", "v1"), Schema1]]
    val evidence2 = summon[PrefixFreePath[("app", "v2"), Schema2]]

    assert(evidence1 != null && evidence2 != null)

  // Test Case 4: Composed schema with duplicate table names
  test("PrefixFreePath should reject composition with duplicate table names"):
    val errors = compileErrors("""
      type Schema1 = Entry["balances", Utf8, Long] *: EmptyTuple
      type Schema2 = Entry["balances", Utf8, Utf8] *: EmptyTuple
      type Combined = Schema1 ++ Schema2
      summon[PrefixFreePath["app" *: EmptyTuple, Combined]]
    """)

    // Verify key parts of the error message
    // With type alias, DifferentNames appears directly in the error (not NotGiven)
    assert(errors.contains("Cannot prove that all table prefixes are prefix-free"), "Should mention prefix-free constraint")
    assert(errors.contains("balances"), "Should mention the duplicate table name 'balances'")
    assert(errors.contains("DifferentNames"), "Should show DifferentNames constraint failure")

  // Test Case 5: Valid case - should compile successfully
  test("PrefixFreePath should accept valid prefix-free schema"):
    type ValidSchema = Entry["accounts", Utf8, Long] *:
                       Entry["balances", Utf8, Long] *:
                       Entry["metadata", Utf8, Utf8] *:
                       EmptyTuple

    val evidence = summon[PrefixFreePath["app" *: EmptyTuple, ValidSchema]]
    assert(evidence != null, "Valid prefix-free schema should compile")

  // Test Case 6: Empty schema - always valid
  test("PrefixFreePath should accept empty schema"):
    val evidence = summon[PrefixFreePath["app" *: EmptyTuple, EmptyTuple]]
    assert(evidence != null, "Empty schema is trivially prefix-free")

  // Test Case 7: Single table - always valid
  test("PrefixFreePath should accept single-table schema"):
    type SingleSchema = Entry["accounts", Utf8, Long] *: EmptyTuple
    val evidence = summon[PrefixFreePath["app" *: EmptyTuple, SingleSchema]]
    assert(evidence != null, "Single table is trivially prefix-free")

  // Test Case 8: Actual byte-level prefix collision detection
  test("Runtime validator: detect byte-level prefix collisions"):
    import scodec.bits.ByteVector

    // Simulate what the compile-time check should do at runtime
    val prefix1 = tablePrefix[("app" *: EmptyTuple), "accounts"]
    val prefix2 = tablePrefix[("app" *: EmptyTuple), "balances"]
    val prefix3 = tablePrefix[("app" *: EmptyTuple), "metadata"]

    // None should be a prefix of another
    def isPrefix(a: ByteVector, b: ByteVector): Boolean =
      a.length <= b.length && b.take(a.length) == a

    // Check all pairs
    assert(!isPrefix(prefix1, prefix2) || prefix1 == prefix2, "prefix1 should not be prefix of prefix2")
    assert(!isPrefix(prefix2, prefix1) || prefix1 == prefix2, "prefix2 should not be prefix of prefix1")
    assert(!isPrefix(prefix1, prefix3) || prefix1 == prefix3, "prefix1 should not be prefix of prefix3")
    assert(!isPrefix(prefix3, prefix1) || prefix1 == prefix3, "prefix3 should not be prefix of prefix1")
    assert(!isPrefix(prefix2, prefix3) || prefix2 == prefix3, "prefix2 should not be prefix of prefix3")
    assert(!isPrefix(prefix3, prefix2) || prefix2 == prefix3, "prefix3 should not be prefix of prefix2")

  // Test Case 9: Verify encoding produces different prefixes
  test("Runtime validator: verify all table prefixes are distinct"):
    val prefix1 = tablePrefix[("app" *: EmptyTuple), "accounts"]
    val prefix2 = tablePrefix[("app" *: EmptyTuple), "balances"]
    val prefix3 = tablePrefix[("app" *: EmptyTuple), "metadata"]

    // All should be distinct
    assert(prefix1 != prefix2, "accounts and balances should have different prefixes")
    assert(prefix1 != prefix3, "accounts and metadata should have different prefixes")
    assert(prefix2 != prefix3, "balances and metadata should have different prefixes")

  // Test Case 10: Blueprint composition should validate prefix-free property
  test("composeBlueprint should validate prefix-free property"):
    // This should fail to compile due to duplicate "data" table name
    val errors = compileErrors("""
      type Schema1 = Entry["data", Utf8, Long] *: EmptyTuple
      type Schema2 = Entry["data", Utf8, Utf8] *: EmptyTuple

      val bp1Entry = Entry["data", Utf8, Long]
      val schema1: Schema1 = bp1Entry *: EmptyTuple

      val reducer1 = new StateReducer0[Id, Schema1]:
        def apply[T <: Tx](tx: T)(using
            Requires[tx.Reads, Schema1],
            Requires[tx.Writes, Schema1],
        ): StoreF[Id][(tx.Result, List[tx.Event])] =
          import cats.data.StateT
          StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

      val bp1 = new ModuleBlueprint[Id, "module1", Schema1, EmptyTuple, EmptyTuple](
        schema = schema1,
        reducer0 = reducer1,
        txs = TxRegistry.empty,
        deps = EmptyTuple,
      )

      val bp2Entry = Entry["data", Utf8, Utf8]
      val schema2: Schema2 = bp2Entry *: EmptyTuple

      val reducer2 = new StateReducer0[Id, Schema2]:
        def apply[T <: Tx](tx: T)(using
            Requires[tx.Reads, Schema2],
            Requires[tx.Writes, Schema2],
        ): StoreF[Id][(tx.Result, List[tx.Event])] =
          import cats.data.StateT
          StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

      val bp2 = new ModuleBlueprint[Id, "module2", Schema2, EmptyTuple, EmptyTuple](
        schema = schema2,
        reducer0 = reducer2,
        txs = TxRegistry.empty,
        deps = EmptyTuple,
      )

      Blueprint.composeBlueprint[Id, "combined"](bp1, bp2)
    """)

    // Verify key parts of the error message
    assert(errors.contains("Cannot prove that all table names in the schema are unique") ||
           errors.contains("Cannot prove that all table prefixes are prefix-free"),
           "Should mention uniqueness or prefix-free constraint")
    assert(errors.contains("data"), "Should mention the duplicate table name 'data'")
    assert(errors.contains("DifferentNames") || errors.contains("UniqueNames"), "Should show the constraint in error trace")
