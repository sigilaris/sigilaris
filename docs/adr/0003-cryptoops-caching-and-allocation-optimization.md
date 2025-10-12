# ADR-0003: CryptoOps 캐싱/할당 최적화 (Phase 3)

## Status
Proposed

## Context
- Phase 1(BigInteger 단일화) 및 Phase 2(타입체크/래핑 최소화) 적용 후 `recover` 경로는 Throughput과 bytes/op가 크게 개선되었으나, `sign`/`fromPrivate`는 여전히 개선 폭이 제한적입니다.
- 핫패스에서 반복 생성되는 암호 객체(`X9IntegerConverter`, `FixedPointCombMultiplier`, `Keccak.Digest256` 등)와 중간 바이트/수학 자료형은 불필요한 할당과 GC 압력을 유발합니다.
- 프로젝트 원칙(ADR-0001): 수학 연산은 `java.math.BigInteger`, 소스오브트루스는 고정 길이 big-endian 바이트(32/64바이트)입니다. 이 원칙을 유지한 채, 캐싱과 버퍼 재사용으로 bytes/op와 GC 시간을 줄이는 것이 목표입니다.

## Decision
- 객체/상수 캐싱 도입(불변/무상태 또는 스레드로컬 적합 객체에 한함)
  - `HalfCurveOrder(BigInteger)`: 상수 단일 인스턴스 유지.
  - `X9IntegerConverter`: 단일 인스턴스 캐시(무상태로 간주).
  - `FixedPointCombMultiplier`: 재사용 캐시(무상태/스레드 세이프 사용 전제). 필요 시 스레드로컬로 보완.
  - 곡선/도메인 파라미터(`ECDomainParameters`/`SecP256K1Curve`/`ECPoint` 관련 상수): 단일 상수화 유지 및 공유.
- Keccak 해시 경로 최적화
  - `Keccak.Digest256`는 per-call 생성 대신 `ThreadLocal` 캐시를 우선 적용한다. 재사용 전 초기화(reset)를 명시적으로 수행한다.
  - 라이브러리 제약으로 thread-safety가 보장되지 않는 경우에만 엄격히 per-thread 재사용으로 한정하고, 필요 시 fallback(매 호출 신규 생성)을 제공한다.
- 바이트 경로에서 임시 배열 재사용
  - 32/64/65바이트 등 고정 길이 scratch 버퍼를 `ThreadLocal`로 보유하여 정상 경로에서의 임시 배열 생성을 최소화한다.
  - 퍼블릭 API 반환 시에는 사본(copy)로 안전하게 노출하고, 내부 계산에서는 scratch 버퍼를 재사용한다.
  - 비밀값이 포함되는 버퍼는 사용 후 덮어쓰기(제로화)하여 누수를 방지한다.
- 자료형 경량화 유지
  - 핫패스 내부에서 `Array[Byte]`/`BigInteger` 일관 유지, `ByteVector`/`BigInt` 생성 금지(경계에서만 사용).
  - `UInt256` 변환은 빠른 경로(`fromBigIntegerUnsigned`, `toBigInteger`)만 사용.
- 보안/일관성 유지
  - Low‑S 정규화, 상수시간 비교, 엔디언/길이 규약(big‑endian, 32/64바이트) 유지.

## Scope
- 대상 파일/경로(핫패스 중심):
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/CryptoOps.scala` (`sign`/`recover`/`fromPrivate`)
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/Hash.scala` (Keccak-256 경로)
  - 보조 상수/헬퍼: `CryptoParams`(신규), `KeccakPool`(신규), `Scratch`(신규)
- 비대상(Phase 3 외):
  - 알고리즘/도메인 의미 변경, 퍼블릭 API 타입 변경, 외부 해시 구현 교체

## Implementation Outline
- Crypto 상수/객체 캐시 모듈화
  - `object CryptoParams`:
    - `secp256k1` 도메인 파라미터, 곡선 상수, `HalfCurveOrder`, `X9IntegerConverter`, `FixedPointCombMultiplier` 보관.
    - 불변(singleton) 캐시를 통해 재할당 제거.
  - 인라이닝 친화 헬퍼(`inline` 메서드)로 Low‑S 비교/정규화, 상수시간 비교 제공.
- Keccak 경로 최적화
  - `object KeccakPool`:
    - `private val tl = ThreadLocal[Keccak.Digest256]` 보유.
    - `acquire(): Digest256`에서 재사용 인스턴스 제공, 사용 전 `reset()` 보장.
    - 예외/비정상 상태 시 안전하게 신규 인스턴스 fallback.
- Scratch 버퍼
  - `object Scratch`:
    - `ThreadLocal`로 32/64/65바이트 배열 보유.
    - 내부 계산에서만 사용하고, 퍼블릭 반환 전에는 `Array.copyOf`로 사본을 만든다.
    - 비밀값 포함 시 `java.util.Arrays.fill(0)`로 즉시 제로화.
- 핫패스 단순화 유지
  - Phase 2에서 도입한 happy‑path 직행, 타입클래스 호출 제거, `Either` 래핑 경계화 정책 유지.
  - 표준 연산과 조기 반환(guard clause)로 분기 예측을 돕고 인라이닝을 유도.

## Testing Strategy
- 기존 프로퍼티/케이스 테스트 전량 통과가 최소 조건:
  - 서명/복구 키 일치, Low‑S 유지, 고정 길이/엔디언 불변.
- 회귀/동시성 안전성 확인:
  - `parTraverse` 기반 동시 서명/복구 시나리오로 결정적 결과 및 예외 부재 확인.
  - scratch 버퍼/ThreadLocal 사용 경로에서 데이터 간섭/누수 없음 확인.
- 보안 위생:
  - 비밀 버퍼 제로화 경로의 호출 보장(unit 테스트에서 가시성은 제한적이므로, 코드 검토와 프로파일링(alloc/leak)로 보완).

## Benchmark & Acceptance Criteria
- 벤치 설정: 기존 JMH 파라미터 유지(Throughput, Warmup 5×10s, Measure 10×10s, Fork 1, Threads 1; Temurin 23, `-Xms2g -Xmx2g`).
- KPI: Throughput(ops/s), ns/op(p50/p99), bytes/op, GC(%), CPU.
- 성공 기준(PLAN.md Phase 3 기준 반영):
  - bytes/op: `sign` 또는 `fromPrivate`에서 ≥ 20% 감소(최소 한 경로), 나머지 경로에서도 ≥ 5% 감소 또는 ±2% 이내 유지.
  - GC 시간: 감소 경향 확인(동일 설정에서 GC 비중 하락).
  - Throughput: 회귀 금지(±2% 이내) 또는 개선.
- 아티팩트: JMH JSON 및 `-prof gc` 결과를 `benchmarks/reports/`에 보존(타임스탬프/브랜치/커밋 해시 포함). 권장 네이밍: `<timestamp>_<branch>_<sha>_jmh.json`, GC 포함: `<timestamp>_<branch>_<sha>_jmh-gc.json`.

## Security & Consistency
- Low‑S 정규화, 상수시간 비교, 엔디언/길이 규약을 변경하지 않는다.
- ThreadLocal 재사용 시 내부 상태 초기화(reset) 및 비밀 데이터 제로화를 보장한다.
- 퍼블릭 API는 여전히 검증/래핑을 경계에서 1회 수행한다(내부 경로는 happy‑path 가정).

## Risks
- ThreadLocal 사용 시:
  - 상태 오염/초기화 누락 위험 → `reset()` 강제, 예외 시 신규 생성으로 복구.
  - 메모리 누수 위험 → 클래스언로드 고려 환경에서 scope 제한, 장수 스레드 외 사용 자제.
- 캐시된 암호 객체의 thread-safety 오판 → 보수적으로 단일 인스턴스/ThreadLocal 선택, 필요 시 동기화 또는 per-call fallback.
- scratch 버퍼 재사용에 따른 데이터 노출 위험 → 내부 전용 사용 원칙, 반환 전 사본 생성, 사용 후 제로화.

## Rollback Strategy
- 기능 토글 제공:
  - `CryptoOps.CachePolicy` 또는 시스템 프로퍼티/환경 변수로 캐시/ThreadLocal 비활성화 가능.
  - 문제 발생 시 즉시 per-call 생성 경로로 복귀.
- 변경 단위를 파일/모듈별로 분리 커밋하여 부분 롤백 용이성 확보.

## References
- `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`
- `docs/adr/0002-cryptoops-typecheck-wrapping-minimization.md`
- `docs/perf/criteria.md`
- `benchmarks/README.md`
- `site/src/en/performance/crypto-ops.md`
- `site/src/ko/performance/crypto-ops.md`

