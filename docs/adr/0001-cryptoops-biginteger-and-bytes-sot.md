# ADR-0001: CryptoOps BigInteger 단일화 및 바이트 SoT

## Status
Accepted

## Context
- BigInt↔BigInteger 변환 비용과 임시 객체 생성이 핫패스 할당/지연을 유발.
- 수학 연산은 BouncyCastle/EC 연산과의 상호운용을 위해 `java.math.BigInteger`가 자연스러움.
- 키/서명/좌표는 바이트가 궁극 소스오브트루스(엔디언/길이 규약을 강제하기 쉬움).

## Decision
- 수학 경로는 `BigInteger`로 통일한다.
- 도메인 모델은 고정 길이 바이트 규약을 1급으로 채택한다(32/64바이트, big‑endian).
- JVM 전용 뷰(곡선 파라미터/포인트)는 지연 생성/캐시한다.

## Consequences
- 변환/할당 감소로 p50/p99 지연 및 bytes/op 개선 기대.
- 테스트/문서에서 엔디언·길이·Low‑S·상수시간 비교 규약을 명시/검증 필요.

## Phase 1 진행 상황 (BigInteger 통일)
### 구현 요약
- `CryptoOps.HalfCurveOrder`: `BigInt` → `BigInteger`(N.shiftRight(1))
- `CryptoOps.sign`: `s` 정규화 및 비교를 `BigInteger` 기반으로 변경, `UInt256.fromBigIntegerUnsigned` 사용
- `CryptoOps.generate`: 개인키 `D`를 `BigInteger` 경로로 변환
- `PublicKey.fromByteArray`: 좌표(x,y)를 `new BigInteger(1, bytes)` → `UInt256.fromBigIntegerUnsigned` 경로로 생성
- `UInt256`: `fromBigIntegerUnsigned(BigInteger)` 추가(빠른 경로)

### 벤치 구성
- JMH 모드: Throughput(ops/s), Warmup 5 × 10s, Measure 10 × 10s, Fork 1, Threads 1
- JVM: Temurin 23.0.1, `-Xms2g -Xmx2g`
- 추가 실행: `-prof gc`로 bytes/op 수집
- 아티팩트:
  - 현행(ops/s): `benchmarks/reports/2025-10-12T09-25-07Z_feature-crypto-operations_fe1e7e0_jmh.json`
  - 현행(GC): `benchmarks/reports/2025-10-12T09-25-07Z_feature-crypto-operations_fe1e7e0_jmh-gc.json`
  - 기준(GC, 4d0c557): `benchmarks/reports/2025-10-12T09-25-07Z_baseline_4d0c557_jmh-gc.json`

### 결과 요약
- Throughput 비교(ops/s)
  - fromPrivate: +3.0% (기준 16068.30 → 현행 16554.79, GC 실행 기준)
  - sign: +2.2% (기준 5385.50 → 현행 5501.60, GC 미적용 실행 기준)
  - recover: +41.1% (기준 2378.49 → 현행 3355.42, GC 실행 기준)
  - keccak256: ~0% (변화 미미)

- bytes/op 비교(GC 프로파일, 낮을수록 좋음)
  - fromPrivate: 95,584 → 95,736 B/op (~+0.2%, 미미한 악화)
  - sign: 334,456 → 333,976 B/op (~−0.1%, 미미한 개선)
  - recover: 805,049 → 563,392 B/op (−30.0%, 크게 개선)
  - keccak256: 544 → 544 B/op (동일)

### 평가 (PLAN.md 기준)
- 성공 기준: 각 벤치 p50 ns/op 10%+ 개선 또는 bytes/op 10%+ 감소
  - recover: 기준 충족(Throughput +41%, bytes/op −30%)
  - fromPrivate: 기준 미충족(Throughput +3%, bytes/op 변화 미미)
  - sign: 기준 미충족(Throughput +2% 내외, bytes/op 변화 미미)
  - keccak256: 기준 해당 없음(암호 연산 외 경로), 변화 미미

### 해석
- recover 경로는 `BigInteger` 연산 통일 효과가 크게 나타났음(역복구 수학 경로에서 BigInt↔BigInteger 변환 제거 영향이 큼).
- sign/fromPrivate는 변환 제거 이득이 작게 나타남. 추가 최적화(Phase 2/3 항목: 타입체크/래핑 제거, 캐시 도입) 대상.
- keccak256은 외부 해시 구현 비용이 지배적이라 본 변경의 영향이 제한적.

### 다음 단계 제안
- Phase 2: 핫패스에서 리플렉션 `cast` 제거, Eq/타입클래스 호출 제거, happy-path 직행화
- Phase 3: 캐싱(`FixedPointCombMultiplier`, `X9IntegerConverter`, `ECDomainParameters`, `Keccak.Digest256`(가능 시))
- CI에 JMH 아티팩트 보존 및 회귀 가드 추가(ops/s, bytes/op 임계치)

## References
 - site: `site/src/ko/performance/crypto-ops.md` (EN: `site/src/en/performance/crypto-ops.md`)
 - Acceptance Criteria: `docs/perf/criteria.md`
 - Bench Guide: `benchmarks/README.md`
 - BouncyCastle secp256k1 구현 세부


