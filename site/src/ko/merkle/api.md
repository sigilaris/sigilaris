# API 레퍼런스

[← Merkle Trie](README.md)

---

## MerkleTrieNode

Merkle Trie의 노드를 나타내는 봉인된 trait.

### 변형 (Variants)

#### Leaf
```scala
final case class Leaf(prefix: Nibbles, value: ByteVector) extends MerkleTrieNode
```

키-값 쌍을 저장하는 말단 노드.

**필드:**
- `prefix: Nibbles` - 이 리프의 키 접미사
- `value: ByteVector` - 이 리프에 저장된 값

**예제:**
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

자식 노드만 가진 분기 노드 (이 노드에 값 없음).

**필드:**
- `prefix: Nibbles` - 모든 자식에 공통인 접두사
- `children: Children` - 16개의 선택적 자식 노드 해시 배열

**예제:**
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

자식 노드와 값을 모두 가진 분기 노드.

**필드:**
- `prefix: Nibbles` - 모든 자식에 공통인 접두사
- `children: Children` - 16개의 선택적 자식 노드 해시 배열
- `value: ByteVector` - 이 분기에 저장된 값

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import org.sigilaris.core.merkle.MerkleTrieNode.*
import scodec.bits.ByteVector
import scodec.bits.ByteVector

val branchWithData = MerkleTrieNode.branchWithData(
  prefix = ByteVector(0x12).toNibbles,
  children = Children.empty,
  value = ByteVector.fromValidHex("data")
)
```

### 공통 메서드

모든 `MerkleTrieNode` 변형은 다음을 지원:

#### prefix
```scala
def prefix: Nibbles
```
이 노드의 접두사 니블을 반환.

#### getChildren
```scala
def getChildren: Option[Children]
```
이 노드가 자식을 가지면 자식 배열을 반환.

**반환:**
- Branch 또는 BranchWithData의 경우 `Some(children)`
- Leaf의 경우 `None`

#### getValue
```scala
def getValue: Option[ByteVector]
```
이 노드에 저장된 값을 반환 (있는 경우).

**반환:**
- Leaf 또는 BranchWithData의 경우 `Some(value)`
- Branch의 경우 `None`

#### setPrefix
```scala
def setPrefix(prefix: Nibbles): MerkleTrieNode
```
업데이트된 접두사를 가진 새 노드를 반환.

**매개변수:**
- `prefix: Nibbles` - 새로운 접두사

**반환:** 업데이트된 접두사를 가진 새 노드

#### setChildren
```scala
def setChildren(children: Children): MerkleTrieNode
```
업데이트된 자식을 가진 새 노드를 반환.

**매개변수:**
- `children: Children` - 새로운 자식 배열

**반환:** 업데이트된 자식을 가진 새 노드

#### setValue
```scala
def setValue(value: ByteVector): MerkleTrieNode
```
업데이트된 값을 가진 새 노드를 반환.

**매개변수:**
- `value: ByteVector` - 새로운 값

**반환:** 업데이트된 값을 가진 새 노드

### 타입 별칭

```scala
type MerkleHash = Hash.Value[MerkleTrieNode]
type MerkleRoot = MerkleHash
type Children = Vector[Option[MerkleHash]] :| Length[StrictEqual[16]]
```

**MerkleHash:** Merkle Trie 노드의 해시 값 (32바이트 Keccak-256)

**MerkleRoot:** Merkle Trie의 루트 해시 (MerkleHash의 별칭)

**Children:** 정확히 16개의 선택적 자식 노드 해시 배열

### Children 연산

```scala
extension (c: Children)
  def updateChild(i: Int, v: Option[MerkleHash]): Children
```

주어진 인덱스(0-15)의 자식을 업데이트.

**매개변수:**
- `i: Int` - 인덱스 (0-15)
- `v: Option[MerkleHash]` - 새로운 자식 해시 값

**반환:** 업데이트된 자식 배열

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.MerkleTrieNode.*
import scodec.bits.ByteVector
import org.sigilaris.core.crypto.Hash

val children = Children.empty
val hash: MerkleHash = ??? // 어떤 해시 값
val updated = children.updateChild(5, Some(hash))
```

---

## Nibbles

4비트 경계에 정렬된 비트 벡터를 나타내는 불투명 타입.

### 생성

```scala
object Nibbles {
  val empty: Nibbles
  def combine(nibbles: Nibbles*): Nibbles
}
```

**empty:** 빈 Nibbles 값

**combine:** 여러 Nibbles를 하나로 결합

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.{BitVector, ByteVector}

val nibbles1 = ByteVector(0x12).toNibbles
val nibbles2 = ByteVector(0x34).toNibbles
val combined = Nibbles.combine(nibbles1, nibbles2)
// combined는 "1234"를 나타냄
```

### 확장 메서드

#### value
```scala
def value: BitVector
```
기본 BitVector 값을 반환.

#### bytes
```scala
def bytes: ByteVector
```
ByteVector로 변환.

#### nibbleSize
```scala
def nibbleSize: Long
```
니블의 개수 (비트 크기 / 4)를 반환.

#### unCons
```scala
def unCons: Option[(Int, Nibbles)]
```
첫 번째 니블과 나머지를 분리.

**반환:**
- `Some((head, tail))` - head는 0-15, tail은 나머지 Nibbles
- 비어있으면 `None`

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0x1A, 0xBC).toNibbles
val result = nibbles.unCons
// first = 1, rest는 "ABC"를 나타냄
```

#### stripPrefix
```scala
def stripPrefix(prefix: Nibbles): Option[Nibbles]
```
주어진 접두사를 제거 (존재하는 경우).

**매개변수:**
- `prefix: Nibbles` - 제거할 접두사

**반환:**
- 접두사가 일치하면 `Some(remainder)`
- 접두사를 찾을 수 없으면 `None`

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0x12, 0x34).toNibbles // "1234"
val prefix = ByteVector(0x12).toNibbles
val result = nibbles.stripPrefix(prefix)
// remainder는 "34"를 나타냄
```

#### 비교 메서드
```scala
def compareTo(that: Nibbles): Int
def <=(that: Nibbles): Boolean
def <(that: Nibbles): Boolean
def >=(that: Nibbles): Boolean
def >(that: Nibbles): Boolean
```

Nibbles의 사전순 비교.

**compareTo** 반환:
- this < that이면 음수
- 같으면 0
- this > that이면 양수

#### hex
```scala
def hex: String
```
16진수 문자열로 변환.

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val nibbles = ByteVector(0xAB, 0xCD).toNibbles
nibbles.hex // "abcd"
```

### 변환 확장

#### BitVector를 Nibbles로
```scala
extension (bitVector: BitVector)
  def refineToNibble: Either[String, Nibbles]
  def assumeNibbles: Nibbles
```

**refineToNibble:** 검증을 포함한 안전한 변환

**assumeNibbles:** 안전하지 않은 변환 (유효하지 않으면 예외 발생)

#### ByteVector를 Nibbles로
```scala
extension (byteVector: ByteVector)
  def toNibbles: Nibbles
```

ByteVector를 Nibbles로 변환 (ByteVector는 항상 8의 배수 비트를 가지므로 항상 안전).

---

## MerkleTrieState

변경 추적 기능을 가진 Merkle Trie의 상태를 나타냄.

### 구조
```scala
final case class MerkleTrieState(
  root: Option[MerkleRoot],
  base: Option[MerkleRoot],
  diff: MerkleTrieStateDiff
)
```

**필드:**
- `root: Option[MerkleRoot]` - 현재 루트 해시
- `base: Option[MerkleRoot]` - 기준 루트 해시 (변경 추적용)
- `diff: MerkleTrieStateDiff` - 기준으로부터의 누적 차이

### 생성자

```scala
object MerkleTrieState {
  def empty: MerkleTrieState
  def fromRoot(root: MerkleRoot): MerkleTrieState
  def fromRootOption(root: Option[MerkleRoot]): MerkleTrieState
}
```

**empty:** 루트나 기준이 없는 빈 상태 생성

**fromRoot:** 루트 해시로부터 상태 생성 (root와 base 모두 설정)

**fromRootOption:** 선택적 루트 해시로부터 상태 생성

**예제:**
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.crypto.Hash

val state1 = MerkleTrieState.empty

val rootHash: MerkleTrieNode.MerkleRoot = ??? // 어딘가로부터
val state2 = MerkleTrieState.fromRoot(rootHash)
```

### 메서드

#### rebase
```scala
def rebase(that: MerkleTrieState): Either[String, MerkleTrieState]
```

이 상태를 다른 상태 위에 리베이스. 두 상태가 동일한 기준을 공유할 때 차이를 결합.

**매개변수:**
- `that: MerkleTrieState` - 리베이스할 상태

**반환:**
- 성공 시 `Right(rebased state)`
- 기준이 일치하지 않으면 `Left(error)`

---

## MerkleTrie 연산

모든 연산은 이펙트풀하며 다음을 반환:
```scala
StateT[EitherT[F, String, *], MerkleTrieState, A]
```

이는 다음을 의미:
- 이펙트 타입 `F` 내에서 작동 (예: `IO`, `Id`)
- `String` 오류 메시지와 함께 실패할 수 있음
- 계산을 통해 `MerkleTrieState`를 스레딩
- `A` 타입의 결과를 반환

### NodeStore

```scala
type NodeStore[F[_]] = Kleisli[EitherT[F, String, *], MerkleHash, Option[MerkleTrieNode]]
```

해시로 노드를 검색하는 저장소 계층. MerkleTrie 연산을 사용하려면 암묵적 인스턴스를 제공해야 함.

**예제:**
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

Merkle Trie에서 키로 값을 검색.

**매개변수:**
- `key: Nibbles` - 찾을 키

**반환:** 찾으면 `Some(value)`, 아니면 `None`을 반환하는 상태풀 계산

**예제:**
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

Merkle Trie에 키-값 쌍을 삽입 또는 업데이트.

**매개변수:**
- `key: Nibbles` - 삽입할 키
- `value: ByteVector` - 키와 연결할 값

**반환:** 트라이를 업데이트하는 상태풀 계산

**예제:**
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

// state.root는 이제 새로운 루트 해시를 포함
```

### remove
```scala
def remove[F[_]: Monad: NodeStore](
  key: Nibbles
): StateT[EitherT[F, String, *], MerkleTrieState, Boolean]
```

Merkle Trie에서 키-값 쌍을 제거.

**매개변수:**
- `key: Nibbles` - 제거할 키

**반환:** 키를 찾아 제거하면 `true`, 아니면 `false`를 반환하는 상태풀 계산

**예제:**
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

주어진 키(포함)부터 사전순으로 모든 키-값 쌍을 스트리밍.

**매개변수:**
- `key: Nibbles` - 시작 키 (포함)

**반환:** `(key, value)` 쌍의 스트림을 반환하는 상태풀 계산

**예제:**
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

역 사전순으로 키-값 쌍을 스트리밍.

**매개변수:**
- `keyPrefix: Nibbles` - 키 접두사 (포함)
- `keySuffix: Option[Nibbles]` - 범위를 제한하는 선택적 접미사 (제외)

**반환:** `(key, value)` 쌍의 역순 스트림을 반환하는 상태풀 계산

`keyPrefix`로 시작하는 쌍을 반환하며, 접미사가 제공된 경우 `keyPrefix + keySuffix`까지 (제외) 반환.

**예제:**
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

### 헬퍼 함수

#### getNode
```scala
def getNode[F[_]: Monad](state: MerkleTrieState)(using NodeStore[F]): EitherT[F, String, Option[MerkleTrieNode]]
```

상태에서 루트 노드를 검색. 먼저 diff를 확인한 다음 노드 저장소를 확인.

#### getNodeAndStateRoot
```scala
def getNodeAndStateRoot[F[_]: Monad](state: MerkleTrieState)(using NodeStore[F]): EitherT[F, String, Option[(MerkleTrieNode, MerkleHash)]]
```

상태에서 루트 노드와 그 해시를 검색.

#### getCommonPrefixNibbleAndRemainders
```scala
def getCommonPrefixNibbleAndRemainders(
  nibbles0: Nibbles,
  nibbles1: Nibbles
): (Nibbles, Nibbles, Nibbles)
```

두 Nibbles의 공통 접두사와 나머지를 계산.

**매개변수:**
- `nibbles0: Nibbles` - 첫 번째 Nibbles
- `nibbles1: Nibbles` - 두 번째 Nibbles

**반환:** `(공통 접두사, remainder0, remainder1)`

**예제:**
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
