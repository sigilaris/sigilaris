# ADR-0008: UInt256 도메인 타입 통일(shared) 및 BigInteger 빠른 경로

## Status
Accepted

## Context
- 프로젝트는 도메인 SoT(Source of Truth)를 고정 길이 바이트(32/64B, big-endian)로 두고, 수학 연산은 `java.math.BigInteger` 및 곡선 타입을 사용한다(ADR-0001, ADR-0004).
- 기존에는 JVM crypto 모듈에 `UInt256BigInt`(Refined[BigInt])가 존재하여 경계에서 256비트·비음수 검증을 수행하고, 내부 연산은 `BigInteger`로 진행했다. 한편 shared 모듈에는 opaque `datatype.UInt256`(32바이트 SoT)이 존재한다.
- 이중 표현은 규약/코덱/오류 모델 중복과 드리프트 위험을 초래한다. 또한 핫패스에서는 `BigInteger` 브리지를 최소화하고, 퍼블릭/직렬화 경계에서는 32바이트 규약을 강제하는 단일 모델이 필요하다.

## Decision
- 퍼블릭/공용 도메인 타입을 `modules/core/shared/.../datatype/UInt256` 하나로 **통일**한다.
- `datatype.UInt256`는 32바이트 big-endian SoT를 유지한다(값 비교/해시/직렬화 기준).
- JVM 상호운용을 위해 `datatype.UInt256`에 **BigInteger 빠른 경로**를 추가한다.
  - `fromBigIntegerUnsigned(value: java.math.BigInteger): Either[UInt256Failure, UInt256]`
  - `toJavaBigIntegerUnsigned(u: UInt256): java.math.BigInteger`
- crypto 모듈의 `UInt256BigInt` 및 관련 Refined 경로는 **폐지/대체**한다. 경계에서 한 번 `datatype.UInt256`로 검증·표현을 고정하고, 내부 수학은 전부 `BigInteger`로 유지한다.

## Consequences
### 장점
- 단일 SoT로 규약 일관성 강화(32바이트 고정, big-endian, JSON/바이트 코덱 통일).
- 오류 모델(ADT)와 코덱이 중복되지 않아 유지보수 용이.
- 핫패스에서 BigInteger 브리지 최소화: 빠른 경로로 변환 비용을 낮춤.

### 단점/리스크
- JVM 전용 최적화 포인트(예: `Refined[BigInteger]`) 제거로 국소 코드 변경 필요.
- 기존 `UInt256BigInt` 의존 호출부 리팩터 필요.

## Implementation Outline
- shared(`datatype.UInt256`)에 BigInteger 빠른 경로 2개 추가.
- crypto 모듈에서 `KeyPair`/`PublicKey`/`Signature`/`CryptoOps` 등 `UInt256BigInt` 사용부를 `datatype.UInt256`로 치환.
- 내부 연산은 `BigInteger`로 일관 유지. 경계에서만 `UInt256`↔`BigInteger` 변환 1회를 원칙으로 한다.
- 코덱:
  - 바이트 코덱: 32바이트 고정(기존 유지)
  - JSON 코덱: 소문자 64-hex(0x 미포함) 고정(기존 유지)

## API/Contract
- 불변식: 0 ≤ n < 2^256, 32바이트 big-endian 고정. 퍼블릭 API는 항상 SoT(바이트) 기준으로 비교/해시/직렬화한다.
- 빠른 경로는 검증을 포함한다(`signum >= 0`, `bitLength ≤ 256`).

## Testing Strategy
- 기존 테스트 전량 통과(직렬화/파싱/경계 검증/서명·복구 일치).
- 신규/보강 테스트:
  - BigInteger 빠른 경로: 경계값(0, 1, 2^256−1, 2^256)에 대한 성공/실패 검증.
  - 변환 왕복성: `u == fromBigIntegerUnsigned(toJavaBigIntegerUnsigned(u))`.
  - 성능: JMH로 `recover`/`sign`/`fromPrivate`에서 회귀 없는지 확인(ops/s, bytes/op).

## Performance Expectations
- 변환 비용은 256비트 상수 비용이지만, 경계 1회 원칙과 빠른 경로로 누적 오버헤드를 억제.
- 기존 벤치(ADR-0001)에서 `recover`는 BigInteger 경로 통일로 큰 이득(+30~40%)을 보였고, 본 통일로 중복 래핑 제거/단순화가 유지 또는 소폭 개선을 기대.

## Migration Plan
1) shared에 빠른 경로 추가.
2) crypto에서 `UInt256BigInt` 사용부를 `datatype.UInt256`로 치환.
3) 내부 연산 BigInteger 경로 유지·정리.
4) 테스트/벤치/문서 업데이트.

## Rollback Strategy
- 빠른 경로를 비활성화하더라도 기존 `datatype.UInt256` API는 유지되므로 기능 회귀 없이 되돌림 가능.
- 문제 발생 시 crypto 내부에서 한시적으로 기존 경로(중간 변환)를 사용하도록 가드 가능.

## References
- ADR-0001: CryptoOps BigInteger 단일화 및 바이트 SoT
- ADR-0004: 데이터 모델 레이어링(SoT=바이트, 연산=BigInteger/곡선)
- 관련 파일: `modules/core/shared/src/main/scala/org/sigilaris/core/datatype/UInt256.scala`, `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/*.scala`

