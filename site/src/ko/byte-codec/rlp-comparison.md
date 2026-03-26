# Ethereum RLP와의 비교

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---

## 개요

이 문서는 Sigilaris 바이트 코덱과 Ethereum의 Recursive Length Prefix (RLP) 인코딩을 비교하며, 설계 유사점, 차이점, 그리고 설계 선택의 근거를 설명합니다.

## RLP란?

RLP (Recursive Length Prefix)는 Ethereum의 주요 인코딩 방법으로 데이터 구조를 직렬화합니다. 트랜잭션, 블록, 상태 트리, 네트워크 메시지에 사용됩니다.

**주요 특성:**
- Deterministic: 동일한 입력은 항상 동일한 출력 생성
- 공간 효율성을 위한 가변 길이 인코딩
- 바이트 배열과 리스트 지원 (재귀적 구조)
- 명시적 타입 정보 없음 (타입은 컨텍스트로 결정)

## 유사점

Sigilaris 코덱과 RLP는 근본적인 설계 목표를 공유합니다:

### 1. Deterministic 인코딩
두 방식 모두 동일한 데이터가 항상 동일한 바이트 시퀀스를 생성하도록 보장하며, 이는 블록체인 합의에 필수적입니다.

### 2. 가변 길이 인코딩
작은 값은 더 적은 바이트를 사용합니다:
- **Sigilaris**: `0` → `0x00`, `128` → `0x80`, `129` → `0x81 81`
- **RLP**: `0` → `0x00`, `127` → `0x7f`, `128` → `0x81 80`

### 3. 길이 접두사
두 방식 모두 접두사 바이트를 사용하여 더 큰 값의 데이터 길이를 나타냅니다.

### 4. 공간 효율성
두 방식 모두 일반적인 경우(작은 정수, 짧은 문자열)를 최적화합니다.

## 주요 차이점

### 1. 단일 바이트 범위

| 코덱 | 단일 바이트 범위 | 값 |
|-------|-------------------|-----------|
| **Sigilaris** | `0x00` - `0x80` | 0-128 (포함) |
| **RLP** | `0x00` - `0x7f` | 0-127 (포함) |

**근거:** Sigilaris는 2의 거듭제곱 인코딩을 최적화하기 위해 단일 바이트 범위에 128을 포함합니다.

**예제:**
```
값 128:
  Sigilaris: 0x80          (1 바이트)
  RLP:       0x81 0x80     (2 바이트)

값 127:
  Sigilaris: 0x7f          (1 바이트)
  RLP:       0x7f          (1 바이트)
```

### 2. 접두사 바이트 계산

#### 짧은 데이터 (1-119 바이트)

| 코덱 | 접두사 범위 | 공식 |
|-------|--------------|------------|
| **Sigilaris** | `0x81` - `0xf7` | `0x80 + data_length` |
| **RLP (문자열)** | `0x80` - `0xb7` | `0x80 + data_length` |

**Sigilaris 범위:** `0x81` - `0xf7` = 119개 가능 값 (1-119 바이트 데이터)
**RLP 범위:** `0x80` - `0xb7` = 56개 가능 값 (1-55 바이트 데이터)

**예제 (10-바이트 데이터):**
```
Sigilaris: [0x8a][10 bytes data]    (prefix = 0x80 + 10)
RLP:       [0x8a][10 bytes data]    (prefix = 0x80 + 10)
```

#### 긴 데이터 (120+ 바이트)

| 코덱 | 접두사 범위 | 공식 |
|-------|--------------|------------|
| **Sigilaris** | `0xf8` - `0xff` | `0xf8 + (length_bytes - 1)` |
| **RLP (문자열)** | `0xb8` - `0xbf` | `0xb7 + length_bytes` |

**예제 (200-바이트 데이터):**
```
Sigilaris:
  200은 인코딩에 1 바이트 필요 (0xc8)
  Prefix: 0xf8 (0xf8 + 0)
  Format: [0xf8][0xc8][200 bytes data]

RLP:
  200은 인코딩에 1 바이트 필요 (0xc8)
  Prefix: 0xb8 (0xb7 + 1)
  Format: [0xb8][0xc8][200 bytes data]
```

### 3. 타입 시스템

| 특성 | Sigilaris | RLP |
|---------|-----------|-----|
| **타입 안정성** | Scala 3 타입 시스템 | 타입 없음 (컨텍스트 의존) |
| **인코딩** | `Mirror.ProductOf`를 통한 자동 derivation | 수동 직렬화 |
| **검증** | 컴파일 타임 + 런타임 (`emap` 사용) | 런타임만 |
| **에러 처리** | `Either[DecodeFailure, A]` | 바이트 배열 또는 예외 |

**예제:**
```scala
// Sigilaris: 타입 안전
case class Transaction(from: Long, to: Long, amount: Long)
val tx = Transaction(1L, 2L, 100L)
val bytes = ByteEncoder[Transaction].encode(tx)
val decoded = ByteDecoder[Transaction].decode(bytes).map(_.value)

// RLP: 수동
val rlpTx = RLP.encodeList(
  RLP.encodeLong(1),
  RLP.encodeLong(2),
  RLP.encodeLong(100)
)
// 디코딩 시 각 필드를 수동으로 추출하고 검증해야 함
```

### 4. 컬렉션 처리

#### 리스트

| 코덱 | 인코딩 |
|-------|----------|
| **Sigilaris** | `[size:BigNat][elem1][elem2]...` |
| **RLP** | `[length_prefix][elem1][elem2]...` (length는 요소 수가 아닌 전체 바이트 크기) |

**주요 차이점:** RLP는 모든 요소의 *전체 바이트 길이*를 인코딩하고, Sigilaris는 *요소 수*를 인코딩합니다.

**예제:**
```
List(1L, 2L, 3L):

Sigilaris:
  [0x03]                          // 3개 요소
  [8 bytes for 1L]
  [8 bytes for 2L]
  [8 bytes for 3L]

RLP:
  [prefix indicating 24 bytes follow]  // 전체 크기
  [encoded 1]
  [encoded 2]
  [encoded 3]
```

#### Set과 Map

| 코덱 | 인코딩 | 순서 |
|-------|----------|-------|
| **Sigilaris Set** | 인코딩된 바이트로 정렬 | Deterministic lexicographic |
| **RLP** | N/A (네이티브 지원 없음) | - |
| **Sigilaris Map** | `Set[(K, V)]` | Deterministic lexicographic |
| **RLP** | N/A (네이티브 지원 없음) | - |

**Sigilaris determinism:**
```scala
Set(3, 1, 2) always encodes as:
  [0x03][0x02][0x04][0x06]  // size=3, then sorted: 1→0x02, 2→0x04, 3→0x06
```

RLP는 Set이나 Map의 내장 개념이 없으며—애플리케이션이 순서를 수동으로 처리해야 합니다.

### 5. 부호 있는 정수

| 코덱 | 부호 있는 정수 |
|-------|-----------------|
| **Sigilaris** | 내장: sign-magnitude 인코딩이 있는 `BigInt` |
| **RLP** | 네이티브 지원 없음 (2의 보수 또는 커스텀 인코딩 사용) |

**Sigilaris 부호 인코딩:**
```
n >= 0  →  encode(n * 2)           // 짝수 = 양수
n < 0   →  encode(n * (-2) + 1)    // 홀수 = 음수

예제:
  -2 → 5 → 0x05
  -1 → 3 → 0x03
   0 → 0 → 0x00
   1 → 2 → 0x02
   2 → 4 → 0x04
```

RLP는 애플리케이션이 자체 부호 인코딩을 구현해야 합니다.

## 설계 근거

### RLP를 직접 사용하지 않는 이유는?

1. **타입 안정성**: Sigilaris는 Scala의 타입 시스템을 통해 컴파일 타임 보장 제공
2. **함수형 프로그래밍**: cats 생태계 활용 (contravariant/covariant functor)
3. **Deterministic 컬렉션**: Set과 Map에 대한 내장 정렬
4. **부호 있는 정수**: 음수에 대한 네이티브 지원
5. **자동 Derivation**: case class가 자동으로 인코딩/디코딩
6. **더 나은 에러 처리**: 예외 대신 `Either` 사용

### 트레이드오프

| 측면 | Sigilaris | RLP |
|--------|-----------|-----|
| **타입 안정성** | ✓ 강함 | ✗ 약함 |
| **인체공학** | ✓ 높음 (자동 derivation) | ✗ 수동 |
| **호환성** | ✗ RLP 호환 불가 | ✓ Ethereum 표준 |
| **컬렉션 determinism** | ✓ 내장 | ✗ 수동 |
| **오버헤드** | ~ 비슷함 | ~ 비슷함 |

## 인코딩 비교 예제

### 예제 1: 작은 정수

```
값: 42

Sigilaris BigInt:
  42 * 2 = 84
  84 → 0x54
  결과: [0x54] (1 바이트)

RLP (big-endian 바이트로):
  42 → 0x2a
  결과: [0x2a] (1 바이트)
```

### 예제 2: 문자열 "hello"

```
Sigilaris:
  UTF-8: 0x68 0x65 0x6c 0x6c 0x6f (5 바이트)
  길이: 5를 BigNat로 인코딩 → 0x05
  결과: [0x05][0x68 0x65 0x6c 0x6c 0x6f] (6 바이트)

RLP:
  UTF-8: 0x68 0x65 0x6c 0x6c 0x6f (5 바이트)
  Prefix: 0x80 + 5 = 0x85
  결과: [0x85][0x68 0x65 0x6c 0x6c 0x6f] (6 바이트)
```

### 예제 3: 정수 리스트

```
List(1, 2, 3) 각 요소가 BigInt로 인코딩

Sigilaris:
  요소 수: 3 → 0x03
  1 → 0x02, 2 → 0x04, 3 → 0x06
  결과: [0x03][0x02][0x04][0x06] (4 바이트)

RLP:
  전체 payload: 3 바이트
  Prefix: 0x80 + 3 = 0xc3
  1 → 0x01, 2 → 0x02, 3 → 0x03
  결과: [0xc3][0x01][0x02][0x03] (4 바이트)
```

### 예제 4: Case Class

```scala
case class User(id: Long, balance: Long)
val user = User(1L, 100L)

Sigilaris:
  id: 1L → 8 bytes (0x00...0x01)
  balance: 100L → 8 bytes (0x00...0x64)
  결과: [16 bytes total]

RLP:
  각 필드를 수동으로 인코딩:
  RLP.encodeList(
    RLP.encodeLong(1),
    RLP.encodeLong(100)
  )
  결과: [prefix][encoded fields]
```

## 언제 각각을 사용해야 하는가

### Sigilaris 코덱을 사용할 때:
- 애플리케이션 특화 블록체인 구축 시
- 타입 안정성과 함수형 프로그래밍이 우선순위일 때
- Deterministic 컬렉션 인코딩이 필요할 때
- 자동 derivation이 개발을 간소화할 때
- Scala 3 + cats 생태계가 스택일 때

### RLP를 사용할 때:
- Ethereum과의 상호 운용 시
- EVM 표준을 따를 때
- 기존 Ethereum 도구와의 호환성이 필요할 때
- 동적 타입 언어에서 작업할 때

## 상호 운용성

**중요:** Sigilaris 코덱과 RLP는 **호환되지 않습니다**. 하나로 인코딩된 데이터는 다른 것으로 디코딩할 수 없습니다.

Ethereum 노드나 스마트 컨트랙트와 통신해야 하는 경우 RLP를 사용해야 합니다. Sigilaris 코덱은 인코딩과 디코딩을 모두 제어하는 독립적인 블록체인 애플리케이션을 위해 설계되었습니다.

## 성능 비교

두 코덱 모두 비슷한 성능 특성을 가집니다:

| 작업 | 복잡도 | 참고 |
|-----------|------------|-------|
| 기본 타입 인코딩 | O(1) | 비슷함 |
| 리스트 인코딩 | O(n) | 비슷함 |
| Set 인코딩 | O(n log n) | Sigilaris 정렬 오버헤드 |
| 디코딩 | O(n) | 비슷함 |
| 공간 효율성 | ~ 동일 | 두 방식 모두 작은 값 최적화 |

Sigilaris는 정렬로 인해 Set/Map에 약간의 오버헤드가 있을 수 있지만, 이는 determinism 보장에 비해 무시할 수 있는 수준입니다.

## 요약

| 특성 | Sigilaris | RLP |
|---------|-----------|-----|
| Determinism | ✓ | ✓ |
| 가변 길이 | ✓ | ✓ |
| 타입 안정성 | ✓ 강함 | ✗ 약함 |
| 컬렉션 | Set/Map deterministic | 수동 |
| 부호 있는 정수 | ✓ 내장 | ✗ 수동 |
| 자동 derivation | ✓ | ✗ |
| Ethereum 호환 | ✗ | ✓ |

Sigilaris 코덱은 RLP에서 영감을 받았지만 Scala에서 타입 안전하고 함수형인 블록체인 개발을 위해 설계되었으며, deterministic 컬렉션과 부호 있는 정수에 대한 내장 지원을 제공합니다.

---

[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
