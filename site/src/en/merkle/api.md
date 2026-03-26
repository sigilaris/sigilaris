# API Reference

[← Merkle Trie](README.md)

---

## MerkleTrieNode

Sealed trait representing a node in the Merkle Trie.

### Variants

#### Leaf
```scala
final case class Leaf(prefix: Nibbles, value: ByteVector) extends MerkleTrieNode
```

Terminal node storing a key-value pair.

**Fields:**
- `prefix: Nibbles` - The key suffix for this leaf
- `value: ByteVector` - The value stored in this leaf

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val leaf = MerkleTrieNode.leaf(
  prefix = ByteVector(0x12, 0x34).toNibbles,
  value = ByteVector.fromValidHex("abcd")
)
```

#### Branch
```scala
final case class Branch(prefix: Nibbles, children: Children) extends MerkleTrieNode
```

Branch node with only child nodes (no value at this node).

**Fields:**
- `prefix: Nibbles` - The common prefix for all children
- `children: Children` - Array of 16 optional child node hashes

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import org.sigilaris.core.merkle.MerkleTrieNode.*
import scodec.bits.ByteVector

val branch = MerkleTrieNode.branch(
  prefix = ByteVector.empty.toNibbles,
  children = Children.empty
)
```

#### BranchWithData
```scala
final case class BranchWithData(
  prefix: Nibbles,
  children: Children,
  value: ByteVector
) extends MerkleTrieNode
```

Branch node with both child nodes and a value stored at this node.

**Fields:**
- `prefix: Nibbles` - The common prefix for all children
- `children: Children` - Array of 16 optional child node hashes
- `value: ByteVector` - The value stored at this branch

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import org.sigilaris.core.merkle.MerkleTrieNode.*
import scodec.bits.ByteVector

val branchWithData = MerkleTrieNode.branchWithData(
  prefix = ByteVector(0x12).toNibbles,
  children = Children.empty,
  value = ByteVector.fromValidHex("data")
)
```

### Common Methods

All `MerkleTrieNode` variants support:

#### prefix
```scala
def prefix: Nibbles
```
Returns the prefix nibbles of this node.

#### getChildren
```scala
def getChildren: Option[Children]
```
Returns the children array if this node has children.

**Returns:**
- `Some(children)` for Branch or BranchWithData
- `None` for Leaf

#### getValue
```scala
def getValue: Option[ByteVector]
```
Returns the value stored in this node if present.

**Returns:**
- `Some(value)` for Leaf or BranchWithData
- `None` for Branch

#### setPrefix
```scala
def setPrefix(prefix: Nibbles): MerkleTrieNode
```
Returns a new node with the updated prefix.

**Parameters:**
- `prefix: Nibbles` - The new prefix

**Returns:** New node with updated prefix

#### setChildren
```scala
def setChildren(children: Children): MerkleTrieNode
```
Returns a new node with the updated children.

**Parameters:**
- `children: Children` - The new children array

**Returns:** New node with updated children

#### setValue
```scala
def setValue(value: ByteVector): MerkleTrieNode
```
Returns a new node with the updated value.

**Parameters:**
- `value: ByteVector` - The new value

**Returns:** New node with updated value

### Type Aliases

```scala
type MerkleHash = Hash.Value[MerkleTrieNode]
type MerkleRoot = MerkleHash
type Children = Vector[Option[MerkleHash]] :| Length[StrictEqual[16]]
```

**MerkleHash:** Hash value of a Merkle Trie node (32-byte Keccak-256)

**MerkleRoot:** Root hash of a Merkle Trie (alias for MerkleHash)

**Children:** Array of exactly 16 optional child node hashes

### Children Operations

```scala
extension (c: Children)
  def updateChild(i: Int, v: Option[MerkleHash]): Children
```

Updates a child at the given index (0-15).

**Parameters:**
- `i: Int` - Index (0-15)
- `v: Option[MerkleHash]` - New child hash value

**Returns:** Updated children array

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.MerkleTrieNode.*
import org.sigilaris.core.crypto.Hash

val children = Children.empty
val hash: MerkleHash = ??? // some hash value
val updated = children.updateChild(5, Some(hash))
```

---

## Nibbles

Opaque type representing a bit vector aligned to 4-bit boundaries.

### Creation

```scala
object Nibbles {
  val empty: Nibbles
  def combine(nibbles: Nibbles*): Nibbles
}
```

**empty:** Empty Nibbles value

**combine:** Combines multiple Nibbles into one

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.{BitVector, ByteVector}

val nibbles1 = ByteVector(0x12).toNibbles
val nibbles2 = ByteVector(0x34).toNibbles
val combined = Nibbles.combine(nibbles1, nibbles2)
// combined represents "1234"
```

### Extension Methods

#### value
```scala
def value: BitVector
```
Returns the underlying BitVector value.

#### bytes
```scala
def bytes: ByteVector
```
Converts to ByteVector.

#### nibbleSize
```scala
def nibbleSize: Long
```
Returns the number of nibbles (bit size / 4).

#### unCons
```scala
def unCons: Option[(Int, Nibbles)]
```
Splits the first nibble and the remainder.

**Returns:**
- `Some((head, tail))` where head is 0-15 and tail is remaining Nibbles
- `None` if empty

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0x1A, 0xBC).toNibbles
val result = nibbles.unCons
// first = 1, rest represents "ABC"
```

#### stripPrefix
```scala
def stripPrefix(prefix: Nibbles): Option[Nibbles]
```
Removes the given prefix if present.

**Parameters:**
- `prefix: Nibbles` - The prefix to remove

**Returns:**
- `Some(remainder)` if prefix matched
- `None` if prefix not found

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0x12, 0x34).toNibbles
val prefix = ByteVector(0x12).toNibbles
val result = nibbles.stripPrefix(prefix)
// remainder represents "34"
```

#### Comparison Methods
```scala
def compareTo(that: Nibbles): Int
def <=(that: Nibbles): Boolean
def <(that: Nibbles): Boolean
def >=(that: Nibbles): Boolean
def >(that: Nibbles): Boolean
```

Lexicographic comparison of Nibbles.

**compareTo** returns:
- Negative if this < that
- 0 if equal
- Positive if this > that

#### hex
```scala
def hex: String
```
Converts to hexadecimal string.

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0xAB, 0xCD).toNibbles
nibbles.hex // "abcd"
```

### Conversion Extensions

#### BitVector to Nibbles
```scala
extension (bitVector: BitVector)
  def refineToNibble: Either[String, Nibbles]
  def assumeNibbles: Nibbles
```

**refineToNibble:** Safe conversion with validation

**assumeNibbles:** Unsafe conversion (throws if invalid)

#### ByteVector to Nibbles
```scala
extension (byteVector: ByteVector)
  def toNibbles: Nibbles
```

Converts ByteVector to Nibbles (always safe since ByteVector has multiple of 8 bits).

---

## MerkleTrieState

Represents the state of a Merkle Trie with change tracking.

### Structure
```scala
final case class MerkleTrieState(
  root: Option[MerkleRoot],
  base: Option[MerkleRoot],
  diff: MerkleTrieStateDiff
)
```

**Fields:**
- `root: Option[MerkleRoot]` - Current root hash
- `base: Option[MerkleRoot]` - Base root hash (for tracking changes)
- `diff: MerkleTrieStateDiff` - Accumulated differences from base

### Constructors

```scala
object MerkleTrieState {
  def empty: MerkleTrieState
  def fromRoot(root: MerkleRoot): MerkleTrieState
  def fromRootOption(root: Option[MerkleRoot]): MerkleTrieState
}
```

**empty:** Creates an empty state with no root or base

**fromRoot:** Creates a state from a root hash (sets both root and base)

**fromRootOption:** Creates a state from an optional root hash

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.crypto.Hash

val state1 = MerkleTrieState.empty

val rootHash: MerkleTrieNode.MerkleRoot = ??? // from somewhere
val state2 = MerkleTrieState.fromRoot(rootHash)
```

### Methods

#### rebase
```scala
def rebase(that: MerkleTrieState): Either[String, MerkleTrieState]
```

Rebases this state onto another state. Combines the differences when both states share the same base.

**Parameters:**
- `that: MerkleTrieState` - The state to rebase onto

**Returns:**
- `Right(rebased state)` on success
- `Left(error)` if bases don't match

---

## MerkleTrie Operations

All operations are effectful and return:
```scala
StateT[EitherT[F, String, *], MerkleTrieState, A]
```

This means they:
- Work within effect type `F` (e.g., `IO`, `Id`)
- May fail with a `String` error message
- Thread `MerkleTrieState` through the computation
- Return a result of type `A`

### NodeStore

```scala
type NodeStore[F[_]] = Kleisli[EitherT[F, String, *], MerkleHash, Option[MerkleTrieNode]]
```

Storage layer for retrieving nodes by hash. You must provide an implicit instance to use MerkleTrie operations.

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.data.{EitherT, Kleisli}
import org.sigilaris.core.merkle.*

val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]

given MerkleTrie.NodeStore[IO] = Kleisli { hash =>
  EitherT.rightT[IO, String](store.get(hash))
}
```

### get
```scala
def get[F[_]: Monad: NodeStore](
  key: Nibbles
): StateT[EitherT[F, String, *], MerkleTrieState, Option[ByteVector]]
```

Retrieves a value by key from the Merkle Trie.

**Parameters:**
- `key: Nibbles` - The key to look up

**Returns:** Stateful computation returning `Some(value)` if found, `None` otherwise

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

given MerkleTrie.NodeStore[IO] = ???

val key = ByteVector(0x12, 0x34).toNibbles
val program = MerkleTrie.get[IO](key)

val (state, result) = program.run(MerkleTrieState.empty).value.unsafeRunSync() match
  case Right(r) => r
  case Left(e) => throw new Exception(e)
```

### put
```scala
def put[F[_]: Monad: NodeStore](
  key: Nibbles,
  value: ByteVector
): StateT[EitherT[F, String, *], MerkleTrieState, Unit]
```

Inserts or updates a key-value pair in the Merkle Trie.

**Parameters:**
- `key: Nibbles` - The key to insert
- `value: ByteVector` - The value to associate with the key

**Returns:** Stateful computation that updates the trie

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

given MerkleTrie.NodeStore[IO] = ???

val key = ByteVector(0x12, 0x34).toNibbles
val value = ByteVector.fromValidHex("abcd")

val program = MerkleTrie.put[IO](key, value)

val (state, _) = program.run(MerkleTrieState.empty).value.unsafeRunSync() match
  case Right(r) => r
  case Left(e) => throw new Exception(e)

// state.root now contains the new root hash
```

### remove
```scala
def remove[F[_]: Monad: NodeStore](
  key: Nibbles
): StateT[EitherT[F, String, *], MerkleTrieState, Boolean]
```

Removes a key-value pair from the Merkle Trie.

**Parameters:**
- `key: Nibbles` - The key to remove

**Returns:** Stateful computation returning `true` if key was found and removed, `false` otherwise

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

given MerkleTrie.NodeStore[IO] = ???

val key = ByteVector(0x12, 0x34).toNibbles
val program = MerkleTrie.remove[IO](key)

val (state, wasRemoved) = program.run(MerkleTrieState.empty).value.unsafeRunSync() match
  case Right(r) => r
  case Left(e) => throw new Exception(e)
```

### streamFrom
```scala
def streamFrom[F[_]: Monad: NodeStore](
  key: Nibbles
): StateT[EitherT[F, String, *], MerkleTrieState, Stream[EitherT[F, String, *], (Nibbles, ByteVector)]]
```

Streams all key-value pairs starting from the given key (inclusive) in lexicographic order.

**Parameters:**
- `key: Nibbles` - The starting key (inclusive)

**Returns:** Stateful computation returning a stream of `(key, value)` pairs

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

given MerkleTrie.NodeStore[IO] = ???

val startKey = ByteVector(0x00).toNibbles
val (state, pairs) = (for
  stream <- MerkleTrie.streamFrom[IO](startKey)
yield stream).run(MerkleTrieState.empty).value.unsafeRunSync() match
  case Right((st, stream)) => (st, stream.compile.toList.value.unsafeRunSync().toOption.get)
  case Left(e) => throw new Exception(e)
```

### reverseStreamFrom
```scala
def reverseStreamFrom[F[_]: Monad: NodeStore](
  keyPrefix: Nibbles,
  keySuffix: Option[Nibbles]
): StateT[EitherT[F, String, *], MerkleTrieState, Stream[EitherT[F, String, *], (Nibbles, ByteVector)]]
```

Streams key-value pairs in reverse lexicographic order.

**Parameters:**
- `keyPrefix: Nibbles` - The key prefix (inclusive)
- `keySuffix: Option[Nibbles]` - Optional suffix to limit the range (exclusive)

**Returns:** Stateful computation returning a reverse stream of `(key, value)` pairs

Returns pairs starting with `keyPrefix`, up to (but not including) `keyPrefix + keySuffix` if suffix is provided.

**Example:**
```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

given MerkleTrie.NodeStore[IO] = ???

val prefix = ByteVector(0x12).toNibbles
val (state, pairs) = (for
  stream <- MerkleTrie.reverseStreamFrom[IO](prefix, None)
yield stream).run(MerkleTrieState.empty).value.unsafeRunSync() match
  case Right((st, stream)) => (st, stream.compile.toList.value.unsafeRunSync().toOption.get)
  case Left(e) => throw new Exception(e)
```

### Helper Functions

#### getNode
```scala
def getNode[F[_]: Monad](state: MerkleTrieState)(using NodeStore[F]): EitherT[F, String, Option[MerkleTrieNode]]
```

Retrieves the root node from state. Checks the diff first, then falls back to the node store.

#### getNodeAndStateRoot
```scala
def getNodeAndStateRoot[F[_]: Monad](state: MerkleTrieState)(using NodeStore[F]): EitherT[F, String, Option[(MerkleTrieNode, MerkleHash)]]
```

Retrieves the root node and its hash from state.

#### getCommonPrefixNibbleAndRemainders
```scala
def getCommonPrefixNibbleAndRemainders(
  nibbles0: Nibbles,
  nibbles1: Nibbles
): (Nibbles, Nibbles, Nibbles)
```

Computes the common prefix and remainders of two Nibbles.

**Parameters:**
- `nibbles0: Nibbles` - First Nibbles
- `nibbles1: Nibbles` - Second Nibbles

**Returns:** `(common prefix, remainder0, remainder1)`

**Example:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val n1 = ByteVector(0x12, 0x34).toNibbles // "1234"
val n2 = ByteVector(0x12, 0x56).toNibbles // "1256"

val (common, rem1, rem2) = MerkleTrie.getCommonPrefixNibbleAndRemainders(n1, n2)
// common = "12", rem1 = "34", rem2 = "56"
```

---

[← Merkle Trie](README.md)
