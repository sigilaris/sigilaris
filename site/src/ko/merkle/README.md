# Merkle Trie

[← 메인](../../README.md) | [English →](../../en/merkle/README.md)

---

[API](api.md)

---

## 개요

Sigilaris merkle 패키지는 암호학적으로 검증된 키-값 저장소를 위한 고성능, 타입 안전한 Merkle Trie 구현을 제공합니다. Merkle Trie는 트라이(접두사 트리)의 장점과 머클 트리의 장점을 결합하여, 효율적인 키-값 연산과 함께 전체 트리 상태에 대한 암호학적 검증을 가능하게 합니다.

**왜 Merkle Trie가 필요한가?** 블록체인과 분산 시스템에서는 데이터 무결성 검증이 매우 중요합니다. Merkle Trie를 사용하면:
- 공통 접두사를 가진 키-값 쌍을 효율적으로 저장
- 간결한 증명으로 키의 존재 여부 증명 가능
- 단일 루트 해시로 전체 트리의 무결성 검증
- 증분 변경 사항을 효율적으로 추적

**주요 특징:**
- **타입 안전**: Scala 3 타입 시스템과 함수형 이펙트 활용
- **암호학적 검증**: 각 노드는 내용에서 계산된 해시를 가짐
- **효율적인 저장**: 경로 압축으로 트리 깊이 감소
- **스트리밍 연산**: 사전순으로 키-값 쌍 스트리밍
- **변경 추적**: Diff 메커니즘으로 수정 사항을 효율적으로 추적
- **Cross-platform**: JVM과 JavaScript 모두에서 동작

## 빠른 시작 (30초)

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.data.{EitherT, Kleisli}
import org.sigilaris.core.merkle.*
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

// 간단한 인메모리 노드 저장소 생성
val store = scala.collection.mutable.Map.empty[MerkleTrieNode.MerkleHash, MerkleTrieNode]

given MerkleTrie.NodeStore[IO] =
  Kleisli { hash =>
    EitherT.rightT[IO, String](store.get(hash))
  }

// 노드를 저장소에 저장하는 헬퍼 함수
def saveNodes(state: MerkleTrieState): Unit =
  state.diff.toMap.foreach { case (hash, (node, _)) =>
    store.put(hash, node)
  }

// 빈 트라이 상태로 시작
val initialState = MerkleTrieState.empty

// 키-값 쌍 삽입
val key1 = ByteVector(0x12, 0x34).toNibbles
val value1 = ByteVector.fromValidHex("abcd")

val key2 = ByteVector(0x12, 0x56).toNibbles
val value2 = ByteVector.fromValidHex("ef01")

val program = for
  _ <- MerkleTrie.put[IO](key1, value1)
  _ <- MerkleTrie.put[IO](key2, value2)
  result <- MerkleTrie.get[IO](key1)
yield result

// 프로그램 실행 후 최종 상태 획득
val (finalState, retrievedValue) = program.run(initialState).value.unsafeRunSync() match
  case Right(r) => r
  case Left(e) => throw new Exception(e)

// 향후 쿼리를 위해 노드를 저장소에 저장
saveNodes(finalState)

// 조회한 값이 삽입한 값과 일치
assert(retrievedValue.contains(value1))

// 최종 상태는 전체 트리를 나타내는 루트 해시를 가짐
assert(finalState.root.isDefined)
```

이게 전부입니다! Merkle Trie가 자동으로:
- 공통 접두사를 가진 경로 압축
- 각 노드에 대한 암호학적 해시 계산
- 전체 트리에 대한 루트 해시 유지
- Diff를 통한 효율적인 변경 추적

## 문서

### 핵심 개념
- **[API 레퍼런스](api.md)**: 모든 타입과 연산에 대한 상세 문서

### 주요 타입

#### MerkleTrieNode
트라이의 노드를 나타내며 세 가지 변형을 가짐:
- **Leaf**: 키-값 쌍을 저장하는 말단 노드
- **Branch**: 16개의 자식 슬롯을 가진 분기 노드 (각 니블 0-F에 대응)
- **BranchWithData**: 값도 함께 저장하는 분기 노드

각 노드는:
- `prefix: Nibbles` - 이 노드의 키 경로 세그먼트
- 선택적 `children: Children` - 16개의 선택적 자식 노드 해시 배열
- 선택적 `value: ByteVector` - 이 노드에 저장된 데이터

#### Nibbles
4비트 경계에 정렬된 비트 벡터로, 16진수 숫자 (0-F)를 나타냅니다. 트라이의 키 경로에 사용됩니다.

```scala mdoc:reset:silent
import org.sigilaris.core.merkle.Nibbles
import org.sigilaris.core.merkle.Nibbles.{given, *}
import scodec.bits.ByteVector

val bytes = ByteVector(0x12, 0x34, 0x56)
val nibbles = bytes.toNibbles

nibbles.hex // "123456"
nibbles.nibbleSize // 6

// head와 tail로 분해
val headTail = nibbles.unCons
// headTail = Some((1, "23456"을 나타내는 nibbles))
```

#### MerkleTrieState
트라이의 현재 상태를 추적:
- `root: Option[MerkleRoot]` - 현재 루트 해시
- `base: Option[MerkleRoot]` - 변경 추적을 위한 기준 루트
- `diff: MerkleTrieStateDiff` - 기준에서 누적된 차이

효율적인 상태 관리를 가능하게 함:
```scala mdoc:compile-only
import org.sigilaris.core.merkle.*
import org.sigilaris.core.crypto.Hash

// 빈 상태로 시작
val state1 = MerkleTrieState.empty

// 또는 기존 루트 해시로부터
val rootHash: MerkleTrieNode.MerkleRoot = ??? // 어딘가로부터
val state2 = MerkleTrieState.fromRoot(rootHash)
```

#### MerkleTrie 연산
모든 연산은 이펙트풀하며 `StateT[EitherT[F, String, *], MerkleTrieState, A]`를 통해 상태를 유지:

- **get**: 키로 값 조회
- **put**: 키-값 쌍 삽입 또는 업데이트
- **remove**: 키-값 쌍 삭제
- **streamFrom**: 주어진 키부터 모든 키-값 쌍 스트리밍
- **reverseStreamFrom**: 역순으로 스트리밍

## 활용 사례

### 1. 기본 키-값 저장소
```scala
import cats.effect.IO
import org.sigilaris.core.merkle.*
import scodec.bits.ByteVector

// 노드 저장소 구현 정의
given MerkleTrie.NodeStore[IO] = ???

val key = ByteVector(0x01, 0x02).bits.assumeNibbles
val value = ByteVector.fromValidHex("deadbeef")

val program = for
  _ <- MerkleTrie.put[IO](key, value)
  retrieved <- MerkleTrie.get[IO](key)
yield retrieved

val (state, result) = program.run(MerkleTrieState.empty).value.unsafeRunSync().toOption.get
```

### 2. 키-값 쌍 스트리밍
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

// pairs는 사전순으로 모든 키-값 쌍을 포함
```

### 3. Merkle 증명 검증
```scala
import org.sigilaris.core.merkle.*

// 트라이를 구축한 후, 루트 해시로 무결성 검증 가능
val state: MerkleTrieState = ???
val rootHash = state.root // 이 해시는 전체 트리 상태를 고유하게 식별

// 이 루트 해시를 가진 누구나 트리의 내용에 대한 증명을 검증 가능
```

### 4. 변경 추적을 통한 증분 업데이트
```scala
import cats.effect.IO
import org.sigilaris.core.merkle.*

given MerkleTrie.NodeStore[IO] = ???

val baseState = MerkleTrieState.fromRoot(???) // 기존 루트

val updates = for
  _ <- MerkleTrie.put[IO](key1, value1)
  _ <- MerkleTrie.put[IO](key2, value2)
  _ <- MerkleTrie.remove[IO](key3)
yield ()

val (newState, _) = updates.run(baseState).value.unsafeRunSync().toOption.get

// newState.diff는 변경된 노드만 포함
// 이를 통해 다른 노드와의 효율적인 동기화 가능
```

## 데이터 구조 세부사항

### Trie 구조
Merkle Trie는 16진 분기 인수(hexadecimal branching factor)를 사용하며, 각 노드는 니블 0-F에 해당하는 최대 16개의 자식을 가질 수 있습니다.

**경로 압축**: 노드는 경로를 압축하기 위해 `prefix`를 저장합니다. 예를 들어, 서브트리에 경로 "abcdef"에 하나의 리프만 있다면, 트라이는 6레벨의 노드를 생성하지 않고 접두사 "abcdef"를 가진 단일 리프를 저장합니다.

**트리 예제**:
```
        Root (prefix: "")
         |
    Branch (prefix: "12")
       /    \
  Leaf     Leaf
  (34)     (56)
  val1     val2
```

### 해싱 전략
각 노드의 해시는 다음과 같이 계산됩니다:
```scala
hash(node) = keccak256(encode(node))
```

여기서 `encode(node)`는 다음을 포함:
- 노드 타입 (Leaf, Branch, 또는 BranchWithData)
- 접두사 니블
- 자식 해시 (분기의 경우)
- 값 바이트 (리프 및 데이터를 가진 분기의 경우)

루트 해시는 전체 트리 상태를 고유하게 식별합니다. 어떤 노드에 대한 변경이라도 위로 전파되어 새로운 루트 해시를 생성합니다.

## 성능 특성

### 시간 복잡도
- **get**: O(k), k는 키 길이
- **put**: O(k), k는 키 길이
- **remove**: O(k), k는 키 길이
- **streamFrom**: O(n), n은 결과 개수

### 공간 복잡도
- 경로 압축으로 노드 수를 크게 감소
- 각 노드는 다음을 위한 저장 공간 필요:
  - 접두사 (가변 길이)
  - 최대 16개의 자식 해시 (분기의 경우 각 32바이트)
  - 값 (리프 및 데이터를 가진 분기의 경우 가변 길이)

### 메모리 사용
- **변경 추적**: `MerkleTrieStateDiff`는 변경된 노드만 저장하여 효율적인 증분 업데이트 가능
- **스트리밍**: 대용량 트라이에 대한 메모리 효율적인 반복을 위해 fs2 스트림 사용

## 타입 규약

### Nibbles 표현
- 각 니블은 4비트 (값 0-F)
- 키는 니블 시퀀스로 표현
- 바이트 0x1A는 니블 \[1, A\]가 됨

### 해시 값
- 노드 해시는 32바이트 Keccak-256 값
- 타입 안전성을 위해 `Hash.Value[MerkleTrieNode]`로 타입 지정
- 루트 해시 타입은 `MerkleRoot`로 별칭 지정

### Children 배열
- 16개의 선택적 자식 해시의 고정 크기 배열
- 타입: `Vector[Option[MerkleHash]] :| Length[StrictEqual[16]]`
- iron 세련화 타입을 사용하여 컴파일 타임에 강제

## 다음 단계

- [API 레퍼런스](api.md)에서 상세한 API 문서 읽기
- `modules/core/shared/src/main/scala/org/sigilaris/core/merkle/`에서 구현 탐색
- `modules/core/shared/src/test/scala/org/sigilaris/core/merkle/`에서 포괄적인 테스트 스위트 확인

## 제한사항

- **인메모리 diff 추적**: 대용량 diff는 상당한 메모리를 소비할 수 있음
- **내장 영속성 없음**: 자체 `NodeStore` 구현을 제공해야 함
- **단일 스레드 업데이트**: 상태 전환은 순차적 (읽기는 병렬화 가능)

## 참고자료

- [Merkle Patricia Trie 명세](https://ethereum.org/en/developers/docs/data-structures-and-encoding/patricia-merkle-trie/)
- [Tries (접두사 트리) - Wikipedia](https://en.wikipedia.org/wiki/Trie)
- [Merkle Tree - Wikipedia](https://en.wikipedia.org/wiki/Merkle_tree)

---

[← 메인](../../README.md) | [English →](../../en/merkle/README.md)
