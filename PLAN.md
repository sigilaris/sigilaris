# 코덱 문서화 계획

## 0. 빠른 참조

### 작업 대상 파일

**Scaladoc 추가:**
- `modules/core/shared/src/main/scala/org/sigilaris/core/codec/byte/ByteEncoder.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/codec/byte/ByteDecoder.scala`
- `modules/core/shared/src/main/scala/org/sigilaris/core/codec/byte/ByteCodec.scala`

**마크다운 문서 생성:**
- `site/src/ko/codec/README.md`
- `site/src/ko/codec/api.md`
- `site/src/ko/codec/types.md`
- `site/src/ko/codec/examples.md`
- `site/src/ko/codec/rlp-comparison.md`
- `site/src/en/codec/README.md`
- `site/src/en/codec/api.md`
- `site/src/en/codec/types.md`
- `site/src/en/codec/examples.md`
- `site/src/en/codec/rlp-comparison.md`

**루트 문서 수정:**
- `site/src/README.md`

**기존 문서:**
- 참고: `site/src/ko/codec.md` (마이그레이션 후 삭제 예정)

## 1. 개요 및 배경

### 1.1 목적
Application-specific private blockchain 구축을 위한 바이트 코덱 라이브러리 문서화.

### 1.2 배경
블록체인에서 트랜잭션/블록에 서명하거나 해시를 구하려면, 먼저 데이터를 deterministic한 바이트열로 변환해야 함.
같은 데이터는 항상 같은 바이트열로 인코딩되어야 해시와 서명의 정확성이 보장됨.

### 1.3 Sigilaris 코덱의 특징
- RLP(Recursive Length Prefix)와 유사하지만 차별화된 가변 길이 인코딩
- 컬렉션(Set/Map)의 deterministic 정렬 보장
- Scala 3 + cats 생태계 기반 타입 안전 API
- 자동 derivation 지원 (case class, sealed trait via Mirror.ProductOf)

## 2. 산출물 목록

향후 머클 트라이, 컨센서스, 통신 프로토콜 등 모듈이 추가될 것을 고려하여 `codec/` 서브폴더 구조 사용.

### 2.1 Scaladoc (API 문서)
- `ByteEncoder.scala`: trait, companion object, given 인스턴스
- `ByteDecoder.scala`: trait, companion object, DecodeResult, given 인스턴스
- `ByteCodec.scala`: trait, companion object, given 인스턴스

### 2.2 한국어 마크다운 문서 (`site/src/ko/codec/`)
- `README.md` - 개요 (왜 필요한가, 빠른 시작, ~200줄)
- `api.md` - ByteEncoder/ByteDecoder/ByteCodec API 레퍼런스 (~400줄)
- `types.md` - 타입별 인코딩/디코딩 규칙 상세 (~600줄)
- `examples.md` - 실전 예제 (~400줄)
- `rlp-comparison.md` - RLP와 비교 (~300줄)

### 2.3 영어 마크다운 문서 (`site/src/en/codec/`)
- `README.md` - Overview (why needed, quick start, ~200 lines)
- `api.md` - ByteEncoder/ByteDecoder/ByteCodec API reference (~400 lines)
- `types.md` - Type-specific encoding/decoding rules (~600 lines)
- `examples.md` - Practical examples (~400 lines)
- `rlp-comparison.md` - Comparison with RLP (~300 lines)

### 2.4 루트 문서 수정
- `site/src/README.md`: Features 섹션 확장, Documentation 섹션 추가

## 3. 문서별 상세 명세

### 3.1 README.md (개요)
**목적**: 코덱 모듈의 전체적인 소개와 빠른 시작 가이드

**포함 섹션**:
- 왜 필요한가: 블록체인 서명/해싱을 위한 deterministic 인코딩
- 30초 시작: 간단한 case class 인코딩 예제 (컴파일 가능)
- 문서 목차: 다른 문서로의 링크
- 네비게이션: 메인 페이지, 영어/한국어 전환

**예상 분량**: ~200줄

### 3.2 api.md (API 레퍼런스)
**목적**: ByteEncoder, ByteDecoder, ByteCodec의 API 상세 설명

**포함 섹션**:
- `ByteEncoder`: trait 설명, `encode` 메서드, `contramap` 변환
- `ByteDecoder`: trait 설명, `decode` 메서드, `map`/`emap`/`flatMap` 변환
- `ByteCodec`: Encoder + Decoder 조합, 자동 derivation
- Given 인스턴스 목록 (타입별 상세는 types.md 참조)

**예상 분량**: ~400줄

### 3.3 types.md (타입별 규칙)
**목적**: 각 타입의 인코딩/디코딩 규칙 상세 설명 (기술 명세 역할)

**포함 섹션**:
- 기본 타입: Unit, Byte, Long, Instant (인코딩 + 디코딩 + 예제)
- BigNat: 가변 길이 인코딩 규칙 상세 (0x00~0x80, 0x81~0xf7, 0xf8~0xff) + 예제
- BigInt: 부호 처리 (2n, -2n+1) + 예제 (-1, 0, 1, 큰 수)
- Tuple: Product derivation으로 자동 지원
- 컬렉션: List(순서), Set(정렬), Map(deterministic), Option

**예상 분량**: ~600줄

### 3.4 examples.md (실전 예제)
**목적**: 실제 사용 시나리오의 완전한 예제

**포함 섹션**:
- 기본 사용: 간단한 데이터 인코딩/디코딩
- 블록체인 데이터 구조: Transaction, Block case class
- 커스텀 타입: opaque type, contramap 활용
- 에러 처리: 디코딩 실패 시나리오
- 라운드트립 테스트: property-based 테스트 작성법

**주의**: 해시/서명 계산은 별도 모듈이므로 여기서는 바이트 인코딩까지만

**예상 분량**: ~400줄

### 3.5 rlp-comparison.md (RLP 비교)
**목적**: Ethereum RLP와의 비교를 통한 설계 이해

**포함 섹션**:
- RLP 간단 소개
- 공통점과 차이점 비교 테이블
- 설계 선택 근거
- 같은 데이터의 인코딩 예제 비교

**예상 분량**: ~300줄

## 4. 기술 명세 (types.md 작성 기준)

### 4.1 타입별 인코딩 규칙

#### 4.1.1 기본 타입
- **Unit**: 빈 바이트열 (`ByteVector.empty`)
- **Byte**: 단일 바이트
- **Long**: BigInt로 변환 후 인코딩
- **Instant**: epoch milliseconds를 Long으로 인코딩

#### 4.1.2 BigNat (자연수) 가변 길이 인코딩

**인코딩 규칙:**
```
0x00 ~ 0x80: 값 n (0 ≤ n ≤ 128)을 단일 바이트로 직접 표현
0x81 ~ 0xf7: [0x80 + 데이터_길이][데이터] (1~119 바이트)
0xf8 ~ 0xff: [0xf8 + (길이_바이트_수 - 1)][길이값][데이터] (120+ 바이트)
```

**예제:**
- `0` → `0x00`
- `128` → `0x80`
- `129` → `0x81 81` (길이 1, 데이터 0x81)
- `255` → `0x81 ff` (길이 1, 데이터 0xff)
- `256` → `0x82 01 00` (길이 2, 데이터 0x0100)

**디코딩 규칙:**
1. 첫 바이트 읽기
2. `0x00~0x80`: 값 그대로 반환
3. `0x81~0xf7`: 길이 = 첫 바이트 - 0x80, 데이터 읽기
4. `0xf8~0xff`: 길이_바이트_수 = 첫 바이트 - 0xf7, 길이 읽고 데이터 읽기

#### 4.1.3 BigInt (정수) 부호 인코딩

**인코딩 변환:**
```scala
n ≥ 0: n * 2를 BigNat로 인코딩
n < 0: n * (-2) + 1을 BigNat로 인코딩
```

**예제:**
- `-2` → `(-2) * (-2) + 1 = 5` → BigNat(5) → `0x05`
- `-1` → `(-1) * (-2) + 1 = 3` → BigNat(3) → `0x03`
- `0` → `0 * 2 = 0` → BigNat(0) → `0x00`
- `1` → `1 * 2 = 2` → BigNat(2) → `0x02`
- `2` → `2 * 2 = 4` → BigNat(4) → `0x04`

**디코딩 변환:**
```scala
x % 2 == 0: x / 2
x % 2 == 1: (x - 1) / (-2)
```

**검증 (roundtrip):**
- `-2` → `5` → `(5-1)/(-2) = -2` ✓
- `-1` → `3` → `(3-1)/(-2) = -1` ✓
- `0` → `0` → `0/2 = 0` ✓
- `1` → `2` → `2/2 = 1` ✓

#### 4.1.4 Tuple
- Product derivation으로 자동 지원
- 각 필드를 순서대로 인코딩하여 연결

#### 4.1.5 컬렉션

**List**:
```scala
[크기:BigNat][요소1][요소2]...[요소n]
```
- 순서 유지
- 크기를 먼저 인코딩, 그 다음 각 요소

**Option**:
```scala
Option[A]를 List[A]로 변환하여 인코딩
None → List() → [0x00]
Some(x) → List(x) → [0x01][x 인코딩]
```
- 타입 컨텍스트로 구분되므로 BigNat(0)과 충돌하지 않음

**Set**:
```scala
[크기:BigNat][정렬된_요소1][정렬된_요소2]...
```
- 각 요소를 먼저 인코딩
- 인코딩된 `ByteVector`를 lexicographic order로 정렬 (`.sorted`)
- deterministic 보장

**예제**:
```scala
Set(2, 1, 3)
→ [0x02, 0x01, 0x03] 인코딩
→ [0x01, 0x02, 0x03] 정렬
→ [0x03][0x01][0x02][0x03] 최종 (크기 3 + 정렬된 요소들)
```

**Map**:
```scala
Map[K, V]를 Set[(K, V)]로 변환하여 인코딩
```
- Tuple2는 Product derivation으로 자동 처리
- Set 인코딩 규칙 적용

### 4.2 RLP 비교 (rlp-comparison.md 작성 기준)

#### RLP와의 공통점
- 작은 값은 단일 바이트로 표현 (공간 효율)
- 큰 값은 길이 prefix 사용
- 재귀적 인코딩 가능

#### RLP와의 차이점

| 범위 | Sigilaris Codec | Ethereum RLP | 비고 |
|------|-----------------|--------------|------|
| 작은 값 | 0x00~0x80 (0~128) | 0x00~0x7f (0~127) | 128 포함 여부 |
| 짧은 데이터 | 0x81~0xf7: `[0x80+len][data]` | 0x80~0xb7: `[0x80+len][data]` | prefix 범위 차이 |
| 긴 데이터 | 0xf8~0xff: `[0xf8+(ll-1)][len][data]` | 0xb8~0xbf: `[0xb7+ll][len][data]` | prefix 계산 차이 |

#### 설계 선택 근거
- 단일 바이트 범위 확장 (0~128): 작은 정수 최적화
- prefix 범위 조정: 더 많은 짧은 데이터 지원 가능

#### 호환성
- RLP와 **호환되지 않음**
- Application-specific blockchain을 위한 독립 설계

## 5. 스타일 가이드

### 5.1 마크다운 문서 구조
각 문서는 다음 섹션을 포함:

```markdown
# 제목

[네비게이션 링크]

---

## 개요
간단한 소개 (2-3문장)

## 주요 개념
핵심 개념 설명

## 사용법
실제 코드 예제 (복사/붙여넣기 가능)

## 상세 설명
세부 동작 원리

## 참고사항
제약사항, 성능 특성, 주의사항
```

### 5.2 코드 예제 스타일
- 모든 예제는 컴파일 가능한 완전한 코드
- import 문 명시
- 블록체인 도메인 용어 사용 (Transaction, Block, Address 등)
- 주석은 최소화 (타입이 설명을 대신)
- 해시/서명 계산은 포함하지 않음 (별도 모듈)

### 5.3 용어 일관성
| 한국어 | 영어 | 설명 |
|--------|------|------|
| 인코딩 | encoding | 데이터 → 바이트열 |
| 디코딩 | decoding | 바이트열 → 데이터 |
| 결정적 | deterministic | 같은 입력 → 같은 출력 |
| 가변 길이 인코딩 | variable-length encoding | 값 크기에 따라 바이트 수 변화 |
| 자연수 | natural number | 0 이상의 정수 (BigNat) |
| 부호 | sign | 정수의 +/- |
| 나머지 | remainder | 디코딩 후 남은 바이트 (DecodeResult) |
| 라운드트립 | roundtrip | encode 후 decode 시 원본 복원 |
| 렉시코그래픽 순서 | lexicographic order | 바이트 기준 사전순 정렬 |

### 5.4 네비게이션 스타일
**화살표 사용** (이모지 대신):

```markdown
[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)
```

**언어 전환 및 메인 링크**:
```markdown
[← 메인](../../README.md) | [English →](../../en/codec/README.md)
```

### 5.5 Scaladoc 작성 스타일

**기본 구조**:
```scala
/** 한 줄 요약 (명령형, 마침표 없음)
  *
  * 상세 설명 (여러 줄 가능)
  *
  * @param name 파라미터 설명
  * @return 리턴값 설명
  *
  * @example
  * {{{
  * val bytes = ByteEncoder[Long].encode(42L)
  * }}}
  */
```

**상세 수준**:
- **trait/object**: 목적, 주요 개념, types.md 링크
- **메서드**: 파라미터, 리턴값, 간단한 예제
- **given 인스턴스**: 간략한 설명, types.md 링크
  ```scala
  /** Encodes Long as BigInt. See types.md for encoding rules. */
  given longEncoder: ByteEncoder[Long] = ...
  ```

**types.md와의 관계**:
- Scaladoc: API 사용법 중심 (어떻게 사용하는가)
- types.md: 인코딩 규칙 상세 (어떻게 인코딩되는가)
- Scaladoc에서 types.md로 링크 권장

### 5.6 mdoc 코드 블록 작성법

**기본 사용** (컴파일 + 출력 표시):
````markdown
```scala mdoc
val x = 42
x + 1
```
````

**silent** (컴파일만, 출력 숨김):
````markdown
```scala mdoc:silent
import org.sigilaris.core.codec.byte.*
```
````

**invisible** (컴파일만, 코드도 숨김):
````markdown
```scala mdoc:invisible
// 테스트용 헬퍼 함수
def helper = ???
```
````

**fail** (컴파일 에러 예상):
````markdown
```scala mdoc:fail
// 컴파일 에러 예제
val x: String = 42
```
````

**reset** (이전 정의 리셋):
````markdown
```scala mdoc:reset
// 새로운 스코프 시작
```
````

**예제 작성 팁**:
- import는 `mdoc:silent`로
- 예제 실행은 기본 `mdoc`로
- 긴 출력은 주석으로 일부만 표시

## 6. 작업 절차

### 6.1 사전 준비
1. 디렉토리 구조 생성
   - `site/src/ko/codec/`
   - `site/src/en/codec/`
2. 기존 파일 마이그레이션
   - `site/src/ko/codec.md` → `site/src/ko/codec/README.md` (확장)

### 6.2 작업 진행 (문서별 반복)

각 문서에 대해 다음 순서로 작성:

#### 문서 1: README.md
1. Scaladoc 작성 (해당 없음)
2. 영어 문서 작성: `en/codec/README.md`
3. 한국어 문서 작성: `ko/codec/README.md` (영어 번역 + 기존 내용 통합)
4. 상호 검증 (용어, 예제 일관성)

#### 문서 2: api.md
1. Scaladoc 작성: `ByteEncoder.scala`, `ByteDecoder.scala`, `ByteCodec.scala`
2. 영어 문서 작성: `en/codec/api.md`
3. 한국어 문서 작성: `ko/codec/api.md`
4. 상호 검증 (Scaladoc과 md 문서 일치)

#### 문서 3: types.md
1. Scaladoc 확장: given 인스턴스 문서화
2. 영어 문서 작성: `en/codec/types.md`
3. 한국어 문서 작성: `ko/codec/types.md`
4. 상호 검증 (인코딩 규칙 일관성)

#### 문서 4: examples.md
1. Scaladoc 작성 (해당 없음)
2. 영어 문서 작성: `en/codec/examples.md`
3. 한국어 문서 작성: `ko/codec/examples.md`
4. 예제 컴파일 검증

#### 문서 5: rlp-comparison.md
1. Scaladoc 작성 (해당 없음)
2. 영어 문서 작성: `en/codec/rlp-comparison.md`
3. 한국어 문서 작성: `ko/codec/rlp-comparison.md`
4. 상호 검증

### 6.3 루트 문서 업데이트
1. `site/src/README.md` 수정
   - Features 섹션 확장
   - Documentation 섹션 추가

### 6.4 최종 검증 및 정리
1. unidoc 생성 테스트
2. 모든 예제 코드 컴파일 검증
3. 문서 간 링크 검증
4. 용어 일관성 최종 검토
5. 기존 파일 삭제
   - `site/src/ko/codec.md`
   - `site/src/PLAN.md`

## 7. 품질 기준 및 검증 방법

### 7.1 필수 품질 기준
- 모든 public API에 Scaladoc 존재
- 예제 코드는 실제 컴파일 가능
- 한/영 문서 내용 일치
- 인코딩 규칙 명확하고 검증 가능 (RLP와 비교 포함)
- 에러 케이스 문서화
- 성능 특성 언급 (deterministic, space-efficient)
- 블록체인 컨텍스트 명확 (바이트 인코딩까지만, 해시/서명은 별도)

### 7.2 검증 방법
- **Scaladoc 완성도**: `sbt unidoc` 실행 후 누락 확인
- **예제 컴파일**: mdoc을 사용하여 문서 내 예제 자동 검증
  ```bash
  sbt mdoc
  ```
  (mdocIn이 `site/src`로 설정됨)
- **용어 일관성**: `grep` 검색 후 수동 검토
  ```bash
  grep -r "인코딩\|encoding" site/src/ko/codec/
  grep -r "디코딩\|decoding" site/src/ko/codec/
  ```
- **링크 검증**: 모든 상대 링크 수동 클릭 확인
- **한/영 대응**: 각 섹션 제목과 예제 코드 일치 확인

## 8. 미래 확장 계획

### 8.1 성능 벤치마크 (선택사항)
- 인코딩/디코딩 속도
- 바이트 크기 효율성
- RLP 대비 성능 비교
- 별도 문서 `codec/benchmarks.md`로 작성

### 8.2 마이그레이션 가이드 (필요시)
- 다른 인코딩 방식에서 전환 시나리오
- 기존 블록체인 데이터 변환 전략
- 별도 문서 `codec/migration.md`로 작성

## 9. 루트 문서 업데이트 명세

### 9.1 site/src/README.md 변경 사항

#### 추가: Features 섹션 확장
기존 4개 항목에 추가:
```markdown
- **Deterministic Byte Codec**: Blockchain-ready encoding with guaranteed consistency for hashing and signatures
```

#### 추가: Documentation 섹션 (Getting Started 다음에 삽입)
```markdown
## Documentation

### Core Modules

#### Byte Codec
Deterministic byte encoding/decoding for blockchain applications.
- [한국어 문서](ko/codec/) | [English Documentation](en/codec/)
- Use cases: Transaction signing, block hashing, merkle tree construction

### API Documentation
- [Latest Release API](https://javadoc.io/doc/org.sigilaris/sigilaris-core_3/latest/index.html)
- [Development API](https://sigilaris.github.io/sigilaris/api/index.html)

### Coming Soon
- Merkle Tree
- Consensus Algorithms
- Network Protocols
```

## 10. 네비게이션 명세

### 10.1 코덱 문서 내 네비게이션 (모든 문서 상단)

```markdown
[← 코덱 개요](README.md) | [API](api.md) | [타입 규칙](types.md) | [예제](examples.md) | [RLP 비교](rlp-comparison.md)

---
```

### 10.2 코덱 README.md 구조

```markdown
# 바이트 코덱 (Byte Codec)

[← 메인](../../README.md) | [English →](../../en/codec/README.md)

---

## 개요
블록체인에서 트랜잭션/블록 서명 및 해싱을 위한 deterministic 바이트 인코딩 라이브러리

## 빠른 시작
[30초 예제 - 컴파일 가능한 완전한 코드]

## 문서 목차

- **[API 레퍼런스](api.md)**: ByteEncoder, ByteDecoder, ByteCodec 상세 설명
- **[타입별 규칙](types.md)**: BigNat, BigInt, 컬렉션 인코딩 규칙
- **[실전 예제](examples.md)**: 블록체인 데이터 구조 예제
- **[RLP 비교](rlp-comparison.md)**: Ethereum RLP와의 차이점
```

## 11. 최종 검증 절차

작업 완료 후 다음을 순서대로 실행:

### 11.1 파일 존재 확인
```bash
# 마크다운 파일 개수 확인 (각 5개)
ls site/src/ko/codec/*.md | wc -l  # 5
ls site/src/en/codec/*.md | wc -l  # 5

# 루트 문서 업데이트 확인
grep "Deterministic Byte Codec" site/src/README.md
```

### 11.2 Scaladoc 검증
```bash
# unidoc 생성 (경고 없이 성공해야 함)
sbt unidoc

# 브라우저에서 확인
open modules/core/target/scala-3.*/unidoc/index.html
```

### 11.3 예제 컴파일 검증
```bash
# mdoc 실행 (모든 예제 컴파일)
sbt mdoc

# 결과 확인
ls site/target/mdoc/
```

### 11.4 용어 일관성 검토
```bash
# 주요 용어 검색 및 수동 검토
grep -r "인코딩" site/src/ko/codec/
grep -r "encoding" site/src/en/codec/
grep -r "디코딩" site/src/ko/codec/
grep -r "decoding" site/src/en/codec/
```

### 11.5 링크 검증
- 각 문서를 브라우저에서 열어 상대 링크 클릭 확인
- 네비게이션 링크 동작 확인
- 언어 전환 링크 확인

### 11.6 한/영 문서 대응 확인
- 각 섹션 제목 일치 여부
- 예제 코드 일치 여부
- 설명 누락 여부

### 11.7 정리
```bash
# 기존 파일 삭제
rm site/src/ko/codec.md
rm site/src/PLAN.md

# git status 확인
git status
```

## 12. 작업 시작 가이드

새 세션에서 이 계획을 기반으로 시작할 때:

### 첫 단계
```bash
# 1. 계획 문서 확인
cat site/src/PLAN.md

# 2. 디렉토리 생성
mkdir -p site/src/ko/codec
mkdir -p site/src/en/codec

# 3. 기존 문서 백업 (선택사항)
cp site/src/ko/codec.md site/src/ko/codec.md.bak
```

### 작업 시작 옵션

**Option A: README.md부터 (권장)**
- 전체 흐름 파악에 유리
- 다른 문서 작성 시 참조 가능
```bash
code site/src/en/codec/README.md
```

**Option B: Scaladoc부터**
- API 문서 작성 시 참조 가능
- 인코딩 규칙을 먼저 정리
```bash
code modules/core/shared/src/main/scala/org/sigilaris/core/codec/byte/ByteEncoder.scala
```

### 권장 작업 순서
1. 디렉토리 생성 (위 명령어)
2. README.md 작성 (영어 → 한국어)
3. Scaladoc 작성 (ByteEncoder → ByteDecoder → ByteCodec)
4. types.md 작성 (영어 → 한국어)
5. api.md 작성 (영어 → 한국어)
6. examples.md 작성 (영어 → 한국어)
7. rlp-comparison.md 작성 (영어 → 한국어)
8. 루트 README.md 업데이트
9. 최종 검증 (섹션 11)

### 작업 중 참조할 섹션
- **문서 구조**: 섹션 3 (문서별 상세 명세)
- **인코딩 규칙**: 섹션 4 (기술 명세)
- **스타일**: 섹션 5 (Scaladoc, mdoc 문법)
- **용어**: 섹션 5.3 (용어 일관성 테이블)
