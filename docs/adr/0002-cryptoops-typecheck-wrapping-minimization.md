# ADR-0002: CryptoOps 타입체크/래핑 최소화 (Phase 2)

## Status
Accepted

## Context
- Phase 1(BigInteger 단일화) 이후 `recover` 경로는 크게 개선되었으나 `sign`/`fromPrivate`의 이득은 제한적이었습니다.
- 핫패스에서 리플렉션 기반 캐스팅, 범용 타입클래스 호출(cats Eq 등), `Either`/옵션 등의 래핑 객체 생성은 분기 예측 실패, 인라이닝 방해, 불필요한 할당으로 이어집니다.
- 바이트를 소스 오브 트루스로 유지하고(`Array[Byte]`), 수학 연산은 `BigInteger`로 일관되게 처리하되, 검증은 경계에서 1회만 수행하는 구조가 성능/단순성 모두에 유리합니다.

## Decision
- 타입체크/캐스팅 최소화
  - 리플렉션/범용 `cast` 제거, 단순 분기 기반의 명시적 타입 확인으로 대체합니다.
  - 내부 전용(사전 검증 완료) 경로에서는 추가 타입 재검증을 수행하지 않습니다(전제조건 명시).
- 범용 타입클래스 호출 제거
  - 핫패스에서 `cats.Eq`/`===` 등 타입클래스 기반 비교/연산 호출을 제거합니다.
  - 표준 연산자 또는 전용 인라인 비교 루틴으로 대체합니다.
- 오류 래핑 최소화 및 경계 집중
  - `Either`/예외/옵션 생성은 I/O/도메인 경계 레이어에서 집중 처리합니다.
  - 내부 API는 happy‑path 직행 형태(검증 전제)로 단순 값을 반환하고, 퍼블릭 API에서만 래핑합니다.
- 자료형 래핑 제거
  - 내부 수학/바이트 경로에서 불필요한 `BigInt`/`ByteVector` 생성을 금지하고,
    `BigInteger`/`Array[Byte]`를 1급 경로로 유지합니다.
- 보안 속성 유지
  - Low‑S 정규화 유지, 비밀값 비교는 상수시간 비교 루틴 유지.
  - 엔디언/길이 규약 고정(big‑endian, 32/64바이트).

## Scope
- 대상(Phase 2)
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/CryptoOps.scala`의 `sign`/`recover`/`fromPrivate` 주요 경로
  - `PublicKey` 생성 경로의 타입 분기/검증 흐름 단순화
  - `UInt256` 경로에서의 불필요한 래핑/변환 제거(`fromBigIntegerUnsigned` 등의 빠른 경로 활용)
- 비대상(Phase 3로 이관)
  - 객체/상수 캐싱(`FixedPointCombMultiplier`, `X9IntegerConverter`, `ECDomainParameters`, `Keccak.Digest256` 등)
  - 해시 구현(`Keccak`)의 스레드로컬/캐시 도입

## Non-Goals
- 암호 알고리즘이나 도메인 의미 변경 없음.
- 퍼블릭 API의 타입 시그니처 대폭 변경 없음(호환 유지).
- 바이트/엔디언/길이 규약 변경 없음.

## Implementation Outline
- 타입체크/캐스팅
  - 리플렉션/범용 `cast` 제거, `match`/`isInstanceOf` 기반 단순 분기 적용.
  - 내부 경로(검증 가정)에서는 추가 검증 생략, 퍼블릭 진입 시 단 1회 검증.
- 타입클래스 호출 제거
  - `cats.syntax` 기반 비교 제거 후 표준 연산자/전용 인라인 비교로 교체.
  - 공용 비교/정규화 헬퍼는 `inline` 메서드로 도입해 인라이닝을 유도.
- 오류 경계화
  - 프라이빗 내부 메서드는 성공 경로 중심의 값 반환; 실패는 퍼블릭 경계에서 래핑 처리.
  - 퍼블릭 API는 기존 `Either`/도메인 오류 ADT를 유지해 호환성 보장.
- 자료형 경량화
  - 중간 `BigInt`/`ByteVector` 생성 제거, `BigInteger`/`Array[Byte]` 경로로 일관 유지.
  - `UInt256` 변환은 빠른 경로 전용 메서드 사용.

## Testing Strategy
- 기존 프로퍼티/케이스 테스트 전량 통과 필수:
  - 서명/복구 키 일치, Low‑S 유지, 고정 길이/엔디언 불변 검증.
- 신규/보강 테스트:
  - 퍼블릭 API 경계에서의 오류 래핑 일관성(`Either` 내용/메시지 동일).
  - 내부 경로에서의 happy‑path 회귀 없음(성능은 벤치로 검증).
- 우선순위: Happy path → Error cases → 경계조건 → Properties.

## Benchmark & Acceptance Criteria
- 벤치 설정: 기존 JMH 설정과 동일한 모드/파라미터 사용(참고: `benchmarks/README.md`).
- KPI: Throughput(ops/s), ns/op(p50/p99), bytes/op, GC(%), CPU.
- 성공 기준(문서화된 성능 기준을 참고):
  - `sign`/`fromPrivate`/`recover` 중 2개 이상에서 ns/op 5–10%+ 개선 또는 bytes/op ≥5% 감소.
  - 회귀 금지: 다른 경로 악화는 ±2% 이내 유지.
- 아티팩트: JMH JSON/GC 리포트 보존(`benchmarks/reports/`).

## Phase 2 결과 요약 (JMH, GC 프로파일 기준)

- 환경: Temurin 23.0.1, `-Xms2g -Xmx2g`, Warmup 5×10s, Measure 10×10s, Fork 1, Threads 1
- 비교 대상: 베이스라인(`..._baseline_4d0c557_jmh-gc.json`) ↔ 현재(`..._feature-crypto-operations_b453da1_jmh-gc.json`)
- Throughput(ops/s) 및 할당(bytes/op, gc.alloc.rate.norm) 변화:
  - fromPrivate: 16068.30 → 16424.02  (+2.2%), 95584.12 → 95816.12 B/op (+0.2%)
  - sign: 5511.24 → 5450.17  (−1.1%), 334456.19 → 330568.19 B/op (−1.2%)
  - recover: 2378.49 → 3339.96  (+40.4%), 805049.16 → 568000.41 B/op (−29.5%)
  - keccak256: 4427303.29 → 4384596.56 (−1.0%), 544.00 → 544.00 B/op (동일)

아티팩트 경로:
- GC 포함: `benchmarks/reports/2025-10-12T10-15-40Z_feature-crypto-operations_b453da1_jmh-gc.json`
- 일반: `benchmarks/reports/2025-10-12T10-15-40Z_feature-crypto-operations_b453da1_jmh.json`
- 베이스라인(GC): `benchmarks/reports/2025-10-12T09-25-07Z_baseline_4d0c557_jmh-gc.json`

## 평가 (Acceptance Criteria 대비)

- 기준: `sign`/`fromPrivate`/`recover` 중 2개 이상에서 ns/op ≥5–10% 개선 또는 B/op ≥5% 감소.
- 결과:
  - recover: 기준 충족(ops/s +40.4%, B/op −29.5%).
  - sign: 기준 미충족(ops/s −1.1%, B/op −1.2%).
  - fromPrivate: 기준 미충족(ops/s +2.2%, B/op +0.2%).
- 결론: Phase 2는 recover 경로에서 유의미한 개선을 유지/확대했으나, `sign`/`fromPrivate`는 기준에 미달. 전체 기준은 부분 충족 상태.

## 해석

- cats/shapeless 제거 및 비교/분기 단순화는 역복구 수학 경로(recover)에 추가 이득을 제공하며, 불필요한 래핑 감소로 B/op가 크게 개선됨.
- sign은 Throughput이 통계적 변동 범위 내에서 소폭 하락했으나, B/op가 소폭 개선됨. 리플렉션/타입클래스 제거만으로는 서명 경로의 객체 생성/할당 지배 비용을 충분히 낮추지 못한 것으로 보임.
- fromPrivate는 수치가 미세하게 개선/악화 혼재(ops/s +2.2%, B/op +0.2%). 캐싱 부재와 임시 객체 생성이 여전히 지배 가능성이 큼.
- keccak256은 외부 해시 구현 비용이 지배적이어서 변화가 미미(의도된 비대상).

## 다음 단계 제안 (Phase 3로 이관 추천)

- 캐싱/할당 최적화:
  - `X9IntegerConverter`, `FixedPointCombMultiplier`, `ECDomainParameters`(이미 상수화), `Keccak.Digest256`(가능 시 스레드로컬) 캐시 도입.
  - 바이트 경로에서 재사용 가능한 버퍼/슬라이스 고려로 임시 배열 생성 최소화.
- sign 경로 집중 개선:
  - recId 탐색 중 중간 객체 생성을 최소화하고, 불필요한 자료형 래핑 제거가 추가 가능한지 검토.
  - 내부 happy‑path 분기를 더 단순화(검증 경계화 유지)하고 인라이닝을 유도.
- 측정/가드:
  - 동일 벤치 파라미터로 JMH 재측정, GC 프로파일 포함 저장.
  - CI에 JMH 아티팩트 보존 및 임계치 가드 추가(ops/s, B/op)로 회귀 방지.

## 참고 (실행 메타데이터)

- 커밋: `b453da1`
- 타임스탬프(UTC): `2025-10-12T10-15-40Z`

## Security & Consistency
- Low‑S 정규화/검증 경로 유지하되 중복 검증 제거.
- 비밀 비교는 상수시간 비교 유지.
- 엔디언/길이 규약 고정(big‑endian, 32/64바이트) 재확인.

## Risks
- 타입클래스 호출 제거로 코드 가독성 저하 가능 → 작은 전용 헬퍼/인라인 메서드로 보완.
- 검증 경계화 실수 시 미검증 값 사용 위험 → 퍼블릭 API 입구에서 단일 진입 검증 강제, Scaladoc에 전제조건 명시.

## Rollback Strategy
- 변경을 기능 단위로 커밋 분리(타입체크 → 타입클래스 제거 → 오류 경계화).
- 문제 발생 시 직전 단위 커밋으로 즉시 롤백.
- 위험 구간은 파일/메서드 단위 리버트가 용이하도록 유지.

## References
- `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`
- 사이트 문서: `site/src/ko/performance/crypto-ops.md`, `site/src/en/performance/crypto-ops.md`
- 벤치 가이드: `benchmarks/README.md`
- 성능 기준: `docs/perf/criteria.md`


