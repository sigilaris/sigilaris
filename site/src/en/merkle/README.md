# Merkle Trie

[← Main](../../README.md) | [한국어 →](../../ko/merkle/README.md)

---

[API](api.md)

---

## Overview

The Sigilaris merkle package provides a high-performance, type-safe Merkle Trie implementation for cryptographically verified key-value storage. A Merkle Trie combines the benefits of tries (prefix trees) with Merkle trees, enabling efficient key-value operations with cryptographic verification of the entire tree state.

**Why do we need a Merkle Trie?** In blockchain and distributed systems, data integrity verification is critical. A Merkle Trie allows you to:
- Store key-value pairs efficiently with shared prefixes
- Prove the existence or absence of a key with a compact proof
- Verify the integrity of the entire tree using a single root hash
- Track incremental changes efficiently

**Key Features:**
- **Type-safe**: Leverages Scala 3 type system and functional effects
- **Cryptographic verification**: Each node has a hash computed from its contents
- **Efficient storage**: Path compression reduces tree depth
- **Streaming operations**: Stream key-value pairs in lexicographic order
- **Change tracking**: Diff mechanism tracks modifications efficiently
- **Cross-platform**: Works on both JVM and JavaScript

## Quick Start (30 seconds)

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.data.{EitherT, Kleisli}
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

// Create a simple in-memory node store
val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]

given MerkleTrie.NodeStore[IO] =
  Kleisli { hash =>
    EitherT.rightT[IO, String](store.get(hash))
  }

// Helper to save nodes to store
def saveNodes(state: MerkleTrieState): Unit =
  state.diff.toMap.foreach { case (hash, (node, _)) =>
    store.put(hash, node)
  }

// Start with an empty trie state
val initialState = MerkleTrieState.empty

// Insert some key-value pairs
val key1 = ByteVector(0x12, 0x34).toNibbles
val value1 = ByteVector.fromValidHex("abcd")

val key2 = ByteVector(0x12, 0x56).toNibbles
val value2 = ByteVector.fromValidHex("ef01")

val program = for
  _ <- MerkleTrie.put[IO](key1, value1)
  _ <- MerkleTrie.put[IO](key2, value2)
  result <- MerkleTrie.get[IO](key1)
yield result

// Run the program and get the final state
val (finalState, retrievedValue) = program.run(initialState).value.unsafeRunSync() match
  case Right(r) => r
  case Left(e) => throw new Exception(e)

// Save nodes to store for future queries
saveNodes(finalState)

// The retrieved value matches what we inserted
assert(retrievedValue.contains(value1))

// The final state has a root hash representing the entire tree
assert(finalState.root.isDefined)
```

That's it! The Merkle Trie automatically:
- Compresses paths with shared prefixes
- Computes cryptographic hashes for each node
- Maintains the root hash for the entire tree
- Tracks changes efficiently through diff

## Documentation

### Core Concepts
- **[API Reference](api.md)**: Detailed documentation for all types and operations

### Main Types

#### MerkleTrieNode
Represents a node in the trie with three variants:
- **Leaf**: Terminal node storing a key-value pair
- **Branch**: Branch node with 16 child slots (one for each nibble 0-F)
- **BranchWithData**: Branch node that also stores a value

Each node has:
- `prefix: Nibbles` - The key path segment for this node
- Optional `children: Children` - Array of 16 optional child node hashes
- Optional `value: ByteVector` - The data stored at this node

#### Nibbles
A bit vector aligned to 4-bit boundaries, representing hexadecimal digits (0-F). Used for key paths in the trie.

```scala mdoc:reset:silent
import org.sigilaris.core.merkle.Nibbles
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val bytes = ByteVector(0x12, 0x34, 0x56)
val nibbles = bytes.toNibbles

nibbles.hex // "123456"
nibbles.nibbleSize // 6

// Decompose into head and tail
val headTail = nibbles.unCons
// headTail = Some((1, nibbles representing "23456"))
```

#### MerkleTrieState
Tracks the current state of the trie:
- `root: Option[MerkleRoot]` - Current root hash
- `base: Option[MerkleRoot]` - Base root for tracking changes
- `diff: MerkleTrieStateDiff` - Accumulated differences from base

Enables efficient state management:
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.crypto.Hash

// Start with empty state
val state1 = MerkleTrieState.empty

// Or from an existing root hash
val rootHash: MerkleTrieNode.MerkleRoot = ??? // from somewhere
val state2 = MerkleTrieState.fromRoot(rootHash)
```

#### MerkleTrie Operations
All operations are effectful and maintain state through `StateT[EitherT[F, String, *], MerkleTrieState, A]`:

- **get**: Retrieve a value by key
- **put**: Insert or update a key-value pair
- **remove**: Delete a key-value pair
- **streamFrom**: Stream all key-value pairs from a given key
- **reverseStreamFrom**: Stream in reverse order

## Use Cases

### 1. Basic Key-Value Storage
```scala
import cats.effect.IO
import org.sigilaris.core.merkle.*
import scodec.bits.ByteVector

// Define your node store implementation
given MerkleTrie.NodeStore[IO] = ???

val key = ByteVector(0x01, 0x02).bits.assumeNibbles
val value = ByteVector.fromValidHex("deadbeef")

val program = for
  _ <- MerkleTrie.put[IO](key, value)
  retrieved <- MerkleTrie.get[IO](key)
yield retrieved

val (state, result) = program.run(MerkleTrieState.empty).value.unsafeRunSync().toOption.get
```

### 2. Streaming Key-Value Pairs
```scala
import fs2.Stream
import cats.effect.IO
import org.sigilaris.core.merkle.*

given MerkleTrie.NodeStore[IO] = ???

val startKey = ByteVector(0x00).bits.assumeNibbles

val program = for
  stream <- MerkleTrie.streamFrom[IO](startKey)
  pairs <- stream.compile.toList
yield pairs

// pairs contains all key-value pairs in lexicographic order
```

### 3. Merkle Proof Verification
```scala
import org.sigilaris.core.merkle.*

// After building a trie, you can use the root hash to verify integrity
val state: MerkleTrieState = ???
val rootHash = state.root // This hash uniquely identifies the entire tree state

// Anyone with this root hash can verify proofs about the tree's contents
```

### 4. Incremental Updates with Change Tracking
```scala
import cats.effect.IO
import org.sigilaris.core.merkle.*

given MerkleTrie.NodeStore[IO] = ???

val baseState = MerkleTrieState.fromRoot(???) // existing root

val updates = for
  _ <- MerkleTrie.put[IO](key1, value1)
  _ <- MerkleTrie.put[IO](key2, value2)
  _ <- MerkleTrie.remove[IO](key3)
yield ()

val (newState, _) = updates.run(baseState).value.unsafeRunSync().toOption.get

// newState.diff contains only the changed nodes
// This enables efficient syncing with other nodes
```

## Data Structure Details

### Trie Structure
The Merkle Trie uses a 16-way branching factor (hexadecimal), where each node can have up to 16 children corresponding to nibbles 0-F.

**Path Compression**: Nodes store a `prefix` to compress paths. For example, if a subtree contains only one leaf at path "abcdef", the trie doesn't create 6 levels of nodes. Instead, it stores a single leaf with prefix "abcdef".

**Example Tree**:
```
        Root (prefix: "")
         |
    Branch (prefix: "12")
       /    \
  Leaf     Leaf
  (34)     (56)
  val1     val2
```

### Hashing Strategy
Each node's hash is computed as:
```scala
hash(node) = keccak256(encode(node))
```

Where `encode(node)` includes:
- Node type (Leaf, Branch, or BranchWithData)
- Prefix nibbles
- Children hashes (for branches)
- Value bytes (for leaves and branches with data)

The root hash uniquely identifies the entire tree state. Any change to any node propagates up to create a new root hash.

## Performance Characteristics

### Time Complexity
- **get**: O(k) where k is the key length
- **put**: O(k) where k is the key length
- **remove**: O(k) where k is the key length
- **streamFrom**: O(n) where n is the number of results

### Space Complexity
- Path compression significantly reduces the number of nodes
- Each node requires storage for:
  - Prefix (variable length)
  - Up to 16 child hashes (32 bytes each for branches)
  - Value (variable length for leaves and branches with data)

### Memory Usage
- **Change Tracking**: The `MerkleTrieStateDiff` only stores changed nodes, enabling efficient incremental updates
- **Streaming**: Uses fs2 streams for memory-efficient iteration over large tries

## Type Conventions

### Nibbles Representation
- Each nibble is 4 bits (values 0-F)
- Keys are represented as sequences of nibbles
- Byte 0x1A becomes nibbles \[1, A\]

### Hash Values
- Node hashes are 32-byte Keccak-256 values
- Typed as `Hash.Value[MerkleTrieNode]` for type safety
- Root hash type is aliased as `MerkleRoot`

### Children Array
- Fixed-size array of 16 optional child hashes
- Type: `Vector[Option[MerkleHash]] :| Length[StrictEqual[16]]`
- Enforced at compile-time using iron refinement types

## Next Steps

- Read detailed API documentation in [API Reference](api.md)
- Explore the implementation in `modules/core/shared/src/main/scala/org/sigilaris/core/merkle/`
- Check the comprehensive test suite in `modules/core/shared/src/test/scala/org/sigilaris/core/merkle/`

## Limitations

- **In-memory diff tracking**: Large diffs may consume significant memory
- **No built-in persistence**: You must provide your own `NodeStore` implementation
- **Single-threaded updates**: State transitions are sequential (though reads can be parallelized)

## References

- [Merkle Patricia Trie Specification](https://ethereum.org/en/developers/docs/data-structures-and-encoding/patricia-merkle-trie/)
- [Tries (Prefix Trees) - Wikipedia](https://en.wikipedia.org/wiki/Trie)
- [Merkle Tree - Wikipedia](https://en.wikipedia.org/wiki/Merkle_tree)

---

[← Main](../../README.md) | [한국어 →](../../ko/merkle/README.md)
