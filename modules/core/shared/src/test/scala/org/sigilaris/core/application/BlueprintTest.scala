package org.sigilaris.core
package application

import cats.Id
import cats.data.{EitherT, Kleisli}
import scodec.bits.ByteVector

import munit.FunSuite

import datatype.Utf8
import merkle.{MerkleTrie, MerkleTrieNode}

class BlueprintTest extends FunSuite:

  // Empty node store for testing
  given MerkleTrie.NodeStore[Id] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
    EitherT.rightT[Id, String](None)

  // Simple test schema: one table
  type BalancesEntry = Entry["balances", Utf8, Long]
  type SimpleSchema = BalancesEntry *: EmptyTuple

  // Create a simple blueprint (no tables - just schema descriptor)
  def createSimpleBlueprint(): ModuleBlueprint[Id, "simple", SimpleSchema, EmptyTuple, EmptyTuple] =
    // Create the runtime Entry instance
    val balancesEntry = Entry["balances", Utf8, Long]
    val schema: SimpleSchema = balancesEntry *: EmptyTuple

    val reducer = new StateReducer0[Id, SimpleSchema]:
      def apply[T <: Tx](tx: T)(using
          Requires[tx.Reads, SimpleSchema],
          Requires[tx.Writes, SimpleSchema],
      ): StoreF[Id][(tx.Result, List[tx.Event])] =
        // Trivial implementation for testing
        import cats.data.StateT
        StateT.pure((null.asInstanceOf[tx.Result], List.empty[tx.Event]))

    new ModuleBlueprint[Id, "simple", SimpleSchema, EmptyTuple, EmptyTuple](
      schema = schema,
      reducer0 = reducer,
      txs = TxRegistry.empty,
      deps = EmptyTuple,
    )

  test("mount creates StateModule with path binding"):
    val blueprint = createSimpleBlueprint()

    // Mount at path ("app", "v1")
    type Path1 = ("app", "v1")
    val module1 = StateModule.mount[Id, "simple", Path1, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)

    // Verify module is created
    assert(module1 != null)
    assert(module1.tables != null)
    assert(module1.reducer != null)

  test("mounting same blueprint at different paths creates different modules"):
    val blueprint = createSimpleBlueprint()

    // Mount at two different paths
    type Path1 = ("app", "v1")
    type Path2 = ("app", "v2")

    val module1 = StateModule.mount[Id, "simple", Path1, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)
    val module2 = StateModule.mount[Id, "simple", Path2, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)

    // Verify they are different instances
    assert(module1 ne module2, "Modules should be different instances")

  test("encodePath produces different prefixes for different paths"):
    type Path1 = ("app", "v1")
    type Path2 = ("app", "v2")
    type Path3 = "other" *: EmptyTuple

    val prefix1 = encodePath[Path1]
    val prefix2 = encodePath[Path2]
    val prefix3 = encodePath[Path3]

    // All prefixes should be different
    assert(prefix1 != prefix2, "Different paths should produce different prefixes")
    assert(prefix1 != prefix3, "Different paths should produce different prefixes")
    assert(prefix2 != prefix3, "Different paths should produce different prefixes")

    // All should be non-empty
    assert(prefix1.nonEmpty, "Path prefix should not be empty")
    assert(prefix2.nonEmpty, "Path prefix should not be empty")
    assert(prefix3.nonEmpty, "Path prefix should not be empty")

  test("encodeSegment produces different encodings for different names"):
    val seg1 = encodeSegment["balances"]
    val seg2 = encodeSegment["accounts"]
    val seg3 = encodeSegment["metadata"]

    assert(seg1 != seg2)
    assert(seg1 != seg3)
    assert(seg2 != seg3)

    // All should be non-empty
    assert(seg1.nonEmpty)
    assert(seg2.nonEmpty)
    assert(seg3.nonEmpty)

  test("tablePrefix equals encodePath(Path) ++ encodeSegment(Name)"):
    type Path = ("app", "v1")
    type Name = "balances"

    val computedPrefix = tablePrefix[Path, Name]
    val manualPrefix = encodePath[Path] ++ encodeSegment[Name]

    assertEquals(computedPrefix, manualPrefix)

  test("tablePrefix is prefix-free for different table names"):
    type Path = "app" *: EmptyTuple

    val prefix1 = tablePrefix[Path, "balances"]
    val prefix2 = tablePrefix[Path, "accounts"]
    val prefix3 = tablePrefix[Path, "metadata"]

    // No prefix should be a prefix of another
    assert(!prefix1.startsWith(prefix2), s"prefix1 should not start with prefix2")
    assert(!prefix1.startsWith(prefix3), s"prefix1 should not start with prefix3")
    assert(!prefix2.startsWith(prefix1), s"prefix2 should not start with prefix1")
    assert(!prefix2.startsWith(prefix3), s"prefix2 should not start with prefix3")
    assert(!prefix3.startsWith(prefix1), s"prefix3 should not start with prefix1")
    assert(!prefix3.startsWith(prefix2), s"prefix3 should not start with prefix2")

  test("tablePrefix is prefix-free across different paths"):
    val prefix1 = tablePrefix[("app", "v1"), "balances"]
    val prefix2 = tablePrefix[("app", "v2"), "balances"]
    val prefix3 = tablePrefix["app" *: EmptyTuple, "balances"]

    // Different paths should produce non-overlapping prefixes
    assert(prefix1 != prefix2)
    assert(prefix1 != prefix3)
    assert(prefix2 != prefix3)

    // Verify prefix-free property
    assert(!prefix1.startsWith(prefix2))
    assert(!prefix2.startsWith(prefix1))
    assert(!prefix1.startsWith(prefix3))
    assert(!prefix3.startsWith(prefix1))
    assert(!prefix2.startsWith(prefix3))
    assert(!prefix3.startsWith(prefix2))

  test("encodeSegment with same content produces same encoding"):
    val seg1a = encodeSegment["test"]
    val seg1b = encodeSegment["test"]

    assertEquals(seg1a, seg1b, "Same segment should encode identically")

  test("encodePath with same path produces same encoding"):
    type Path = ("app", "v1")

    val path1 = encodePath[Path]
    val path2 = encodePath[Path]

    assertEquals(path1, path2, "Same path should encode identically")

  test("lenBytes encodes non-negative integers"):
    val len0 = lenBytes(0)
    val len1 = lenBytes(1)
    val len10 = lenBytes(10)
    val len255 = lenBytes(255)

    assert(len0.nonEmpty)
    assert(len1.nonEmpty)
    assert(len10.nonEmpty)
    assert(len255.nonEmpty)

    // Different lengths should produce different encodings
    assert(len0 != len1)
    assert(len1 != len10)
    assert(len10 != len255)

  test("encodeSegment format: length-prefix + bytes + null terminator"):
    val segment = encodeSegment["abc"]

    // "abc" is 3 bytes in UTF-8
    // Format should be: lenBytes(3) ++ ByteVector("abc") ++ 0x00
    val expected = lenBytes(3) ++ ByteVector("abc".getBytes("UTF-8")) ++ ByteVector(0x00)

    assertEquals(segment, expected)

  test("mounted tables use path-specific prefixes"):
    val blueprint = createSimpleBlueprint()

    // Mount at different paths
    type Path1 = ("app", "v1")
    type Path2 = ("app", "v2")

    val module1 = StateModule.mount[Id, "simple", Path1, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)
    val module2 = StateModule.mount[Id, "simple", Path2, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)

    // Extract the first (and only) table from each module
    // The type is Tables[Id, SimpleSchema] = TableOf[Id, BalancesEntry] *: EmptyTuple
    val table1: TableOf[Id, BalancesEntry] = module1.tables.asInstanceOf[TableOf[Id, BalancesEntry] *: EmptyTuple].head
    val table2: TableOf[Id, BalancesEntry] = module2.tables.asInstanceOf[TableOf[Id, BalancesEntry] *: EmptyTuple].head

    // Verify tables are different instances
    assert(table1 ne table2, "Each mount should create fresh table instances")

    // Verify prefixes by doing a put/get and checking the keys in the trie
    import merkle.MerkleTrieState
    val key = table1.brand(Utf8("test"))
    val value = 42L

    // Put to table1
    val (state1, _) = table1.put(key, value).run(MerkleTrieState.empty).value match
      case Right(result) => result
      case Left(err) => fail(s"Put failed: $err")

    // Put same logical key to table2
    val key2 = table2.brand(Utf8("test"))
    val (state2, _) = table2.put(key2, value).run(MerkleTrieState.empty).value match
      case Right(result) => result
      case Left(err) => fail(s"Put failed: $err")

    // The states should have different roots because the actual keys differ
    assert(state1.root != state2.root, "Different paths should produce different trie keys")

  test("mounted tables carry correct computed prefixes"):
    val blueprint = createSimpleBlueprint()

    type Path = ("app", "prod")
    val module = StateModule.mount[Id, "simple", Path, SimpleSchema, EmptyTuple, EmptyTuple](blueprint)

    // The expected prefix for "balances" table at path ("app", "prod")
    val expectedPrefix = tablePrefix[Path, "balances"]

    // Extract the table using TableOf type alias
    val table: TableOf[Id, BalancesEntry] = module.tables.asInstanceOf[TableOf[Id, BalancesEntry] *: EmptyTuple].head

    // Verify by encoding a key and checking it starts with the expected prefix
    val testKey = Utf8("alice")
    val encodedKey = codec.byte.ByteCodec[Utf8].encode(testKey)
    val fullKey = expectedPrefix ++ encodedKey

    // Put a value using the table
    import merkle.MerkleTrieState
    import merkle.Nibbles.*
    import codec.byte.ByteDecoder.ops.*

    val key = table.brand(testKey)
    val (state, _) = table.put(key, 100L).run(MerkleTrieState.empty).value match
      case Right(result) => result
      case Left(err) => fail(s"Put failed: $err")

    // The state should contain the full key (prefix ++ encoded key) as nibbles
    val fullKeyNibbles = fullKey.toNibbles

    // Verify the key exists in the trie by trying to get it
    val retrieved = merkle.MerkleTrie.get[Id](fullKeyNibbles).runA(state).value match
      case Right(Some(bytes)) => bytes
      case Right(None) => fail("Key should exist in trie")
      case Left(err) => fail(s"Get failed: $err")

    // Decode and verify the value
    val decodedValue = retrieved.to[Long] match
      case Right(v) => v
      case Left(err) => fail(s"Decode failed: $err")

    assertEquals(decodedValue, 100L, "Retrieved value should match")
