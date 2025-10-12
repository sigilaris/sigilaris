# ADR-0004: CryptoOps 데이터 모델/레이어링 (Phase 4)

## Status
Accepted

## Context
- 이전 단계(ADR-0001 BigInteger·Bytes SoT, ADR-0002 타입체크/래핑 최소화, ADR-0003 캐싱/할당 최적화)로 핵심 연산 경로의 불필요한 변환과 객체 생성을 줄였으나, 여전히 데이터 표현과 뷰 생성 간 레이어링이 명확하지 않아 반복적인 인코딩/디코딩과 임시 객체가 남아 있습니다.
- 본 프로젝트의 규약은 "소스 오브 트루스(SoT)는 고정 길이 big-endian 바이트(32/64바이트)"이며, 수학 연산은 `java.math.BigInteger`로 통일합니다. 이 원칙을 유지하면서도, JVM 실행 환경에서는 원 라이브러리(BouncyCastle 등) 타입의 뷰를 지연 생성하여 성능·할당을 개선할 여지가 있습니다.
- PLAN.md Phase 4 목표: 바이트를 SoT로 유지하되, JVM 전용 뷰/캐시를 제공하고, 경계에서만 `ByteVector`를 사용하며 내부는 `Array[Byte]`를 우선 사용하여 핫패스 할당을 줄입니다.

## Decision
- 바이트 SoT 일관 유지
  - 개인키: 32바이트 unsigned big-endian 고정 길이
  - 공개키: 64바이트(`x||y`) unsigned big-endian 고정 길이
  - 인코딩 정책은 변경하지 않으며, 퍼블릭 API의 의미/타입을 보존합니다.
- JVM 전용 뷰/캐시 도입(지연 생성)
  - `PublicKey`에 다음 JVM 전용 뷰를 지연 생성·캐시: `ECPoint`(uncompressed), 필요 시 65바이트(uncompressed) 표현.
  - `KeyPair`에 다음 JVM 전용 뷰를 지연 생성·캐시: `ECPrivateKeyParameters`(도메인 파라미터 포함), 필요 시 곡선 포인트(`ECPoint`) 파생 뷰.
  - 캐시는 멱등적(late-init, multi-writers 허용)이며 동시성에서 동일 인스턴스의 중복 생성은 허용(경쟁 시 마지막 저장 승자)합니다. 가시성은 `@volatile` 또는 안전한 게터 경로로 보장합니다.
- 내부 표현 최적화
  - 경계(API, 코덱)에서는 `ByteVector`를 사용하되, 내부 핫패스는 `Array[Byte]` 중심으로 통일합니다.
  - `PublicKey`는 64바이트 결합 배열(`x||y`)을 내부 캐시로 유지하여 반복 분할/병합 비용을 제거합니다.
- 레이어 경계 명확화
  - SoT(바이트) ↔ 수학(`BigInteger`) ↔ JVM 뷰(`ECPoint`/`ECPrivateKeyParameters`)의 단방향 지연 변환 구조를 명확히 하고, 역변환은 SoT 보관 값에서 재구성하도록 합니다.
- 보안/일관성
  - Low‑S 정규화, 상수시간 비교, 엔디언/길이 규약은 유지합니다.
  - 비밀 데이터(개인키) 관련 임시 버퍼는 사용 후 제로화하며, JVM 뷰 캐시는 불변 파라미터 객체만 저장합니다.
- 토글·폴백
  - 문제 발생 시 JVM 전용 뷰/캐시를 비활성화하고 per-call 생성 경로로 폴백할 수 있는 정책 플래그(환경변수/시스템 프로퍼티 기반)를 제공합니다.

## Scope
- 대상(핫패스 및 관련 도메인 모델)
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/PublicKey.scala`
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/KeyPair.scala`
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/CryptoParams.scala` (도메인 파라미터/헬퍼 정리/공유)
  - 필요 시 JVM 뷰 유틸(예: `JvmViewCache` 또는 기존 타입 내 private 캐시 필드)
- 비대상
  - 퍼블릭 API의 타입 시그니처 변경, 알고리즘/도메인 의미 변경, 외부 라이브러리 교체

## Implementation Outline
- PublicKey 레이어링
  - 내부 SoT: 64바이트 결합 배열(`x||y`)을 캐시(`cachedXY64: Array[Byte]`)로 보유.
  - 지연 뷰: `ECPoint`(uncompressed)와 65바이트 uncompressed 표현을 필요 시 생성·캐시.
  - 기존 `toBytes`는 반환 시 복사본을 제공하여 불변성과 캡슐화를 유지.
- KeyPair 레이어링
  - 내부 SoT: 개인키 32바이트 배열 캐시(`cachedD32: Array[Byte]`).
  - 지연 뷰: `ECPrivateKeyParameters`(도메인 파라미터 `ECDomainParameters` 포함) 캐시. 공개키 포인트 뷰가 필요한 경우 `ECPoint`를 `PublicKey` 캐시로부터 공유.
- 캐시 초기화 패턴
  - double-checked style의 로컬 스냅샷 후 set(가벼운 경쟁 허용), `@volatile` 가시성 보장.
  - 동일 값에 대한 중복 생성 허용(정확성 유지, 성능 저하 미미).
- 내부 표현 통일
  - 핫패스에서 `ByteVector` 생성 금지(경계에서만 허용). 반환 시에는 복사/래핑으로 안전 노출.
- 도메인 파라미터 공유
  - `CryptoParams`에서 X9 변환기, 곡선/도메인 파라미터, 멀티플라이어 등 상수/객체를 단일화하고, `CryptoOps`/모델에서 공유합니다.
- 토글 설계
  - `CachePolicy` 또는 시스템 프로퍼티(`sigilaris.crypto.cache=false`)로 캐시 비활성화 시 모든 뷰는 매 호출 생성.

## Acceptance Criteria
- 기능적
  - 퍼블릭 API(타입/의미) 변화 없음.
  - SoT 바이트 규약(고정 길이, big-endian) 불변.
- 성능/할당(벤치: 기존 설정 유지)
  - `fromPrivate` 또는 `sign` 경로 중 최소 1개에서 bytes/op ≥ 5% 감소 또는 ns/op p50 ≥ 5% 개선.
  - 나머지 핵심 경로(`recover`, `keccak256`)는 ±2% 이내 유지 또는 개선.
  - GC 시간 비중 감소 경향 확인(동일 파라미터에서 하락).
- 동시성/안전성
  - `parTraverse` 기반 동시 연산에서 캐시 뷰 사용 시 결정적 결과/예외 없음.
  - 비밀 데이터 임시 버퍼 제로화 경로가 존재하고, 퍼블릭 반환은 복사본으로 안전 노출.

## Testing Strategy
- 회귀 테스트: 기존 프로퍼티/케이스 테스트 전량 통과(서명/복구 키 일치, Low‑S, 길이/엔디언 규약).
- 동시성 테스트: 다중 스레드에서 `sign`/`recover`/`fromPrivate` 병행 호출 시 결과 일관성/예외 부재 검증.
- 메모리/할당: JMH `-prof gc`로 bytes/op 및 GC 비중 비교. 필요 시 JFR/async-profiler로 할당 트리 확인.

## Risks
- 캐시 뷰의 스레드 안전성 오판: 지연 생성은 불변 객체만 캐시하고, 내부 상태가 있는 객체는 캐시 대상에서 제외.
- 메모리 상주량 증가: 캐시로 인한 상주 메모리 증가를 허용 가능한 수준으로 관리(키당 수 개의 뷰에 한정).
- 구현 복잡도 증가: 레이어 경계가 명확해지나, 캐시 초기화 패턴이 복잡해질 수 있음.

## Rollback Strategy
- 기능 토글로 캐시 비활성화(시스템 프로퍼티/환경변수).
- per-call 생성 경로 유지로 즉시 복귀 가능.
- 변경 단위를 파일 단위 커밋으로 분리하여 부분 롤백 용이.

## Notes
- 본 ADR은 AGENTS 가이드라인을 준수합니다: 타입 안정성, 간결성, 성능 우선. 효과는 벤치로 검증하고 회귀는 CI 가드로 방지합니다.

## References
- `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`
- `docs/adr/0002-cryptoops-typecheck-wrapping-minimization.md`
- `docs/adr/0003-cryptoops-caching-and-allocation-optimization.md`
- `docs/perf/criteria.md`
 

## Phase 4 Benchmark Results (2025-10-12)

- 환경: JDK 23.0.1, 1 fork, warmup 3×10s, measure 5×10s, threads 1
- 리포트 아티팩트:
  - Non-GC: `benchmarks/reports/2025-10-12T13-51-34Z_feature-crypto-operations_39c116e_jmh.json`
  - GC Profiled: `benchmarks/reports/2025-10-12T14-03-08Z_feature-crypto-operations_4243f51_jmh-gc.json`

### Summary (Throughput)

| Benchmark | Non-GC (ops/s avg) | GC Profiled (ops/s avg) |
|---|---:|---:|
| fromPrivate | 15,906.826 | 16,531.292 |
| keccak256 | 4,528,519.996 | 4,516,202.512 |
| recover | 2,360.182 | 2,437.406 |
| sign | 3,365.159 | 5,481.105 |

참고: GC 프로파일링 유무에 따라 스케줄링/최적화 차이로 소폭 차이가 발생할 수 있습니다.

### GC/Allocation (from -prof gc)

| Benchmark | alloc.rate (MB/s) | alloc.rate.norm (B/op) | gc.count (sum) | gc.time (ms sum) |
|---|---:|---:|---:|---:|
| fromPrivate | 1,511.322 | 95,864.101 | 62 | 142 |
| keccak256 | 206.733 | 48.000 | 9 | 18 |
| recover | 1,829.399 | 787,021.418 | 75 | 178 |
| sign | 1,739.837 | 332,848.186 | 71 | 168 |

## Evaluation

- SoT/뷰 캐시 효과: `fromPrivate` 경로는 바이트/포인트 뷰 재생성 비용 감소의 이점을 받으며, GC 프로파일에서 op당 ~96KB 수준으로 유지되었습니다. `keccak256`은 48 B/op로 매우 낮은 할당을 유지합니다(스레드로컬 풀의 효과 확인).
- `sign`/`recover`는 ECDSA 수학 연산 특성상 여전히 큰 할당량을 보이며(약 333KB/op, 787KB/op), 캐시로 인한 반복 구성요소 생성 감소에도 불구하고 수학 경로 자체의 객체 생성이 지배적입니다.
- Acceptance Criteria 관점: bytes/op 또는 ns/op 개선 여부는 베이스라인과의 직접 비교가 필요합니다. 본 페이즈에서 리포트는 산출/보존되었고, Phase 6에서 자동 비교 가드로 평가를 확정하는 것이 적절합니다.

## Interpretation

- PublicKey/KeyPair JVM 뷰/바이트 캐시는 핫패스에서 재조립·재해석 비용을 줄여 스루풋 및 할당에 긍정적입니다. 특히 비-해시 경로(`fromPrivate`)에서 효과가 두드러집니다.
- `sign`/`recover`의 높은 bytes/op는 모듈러 연산과 포인트 연산 중간 객체가 주원인이며, 캐시 외에도 수학 경로의 추가 최적화 여지가 남아있습니다.
- Keccak은 스레드로컬 풀을 통해 매우 낮은 할당으로 유지되고 있어 목표에 부합합니다.

## Next Steps

- Phase 5 (보안/일관성)
  - Low‑S 정규화 테스트 강화 및 경계 조건(서명/복구) 케이스 추가
  - 비밀 데이터 버퍼 제로화 경로 점검 및 적용(가능 시 `Array[Byte]` 재사용 후 zero-fill)
  - 상수시간 비교 도입/검증(비밀 비교 지점)

- Phase 6 (회귀 방지/문서화/CI)
  - CI에 JMH 통합, Non-GC/GC 리포트 모두 보존; 임계치 가드(ops/s, B/op) 설정
  - 리포트 자동 비교 스크립트 추가: 최근 실행 vs 베이스라인(브랜치/리비전 기준) p50 및 bytes/op 변화량 계산
  - 사이트/문서에 성능 챕터 업데이트(측정 설정, 해석 가이드, 회귀 가드 설명)

- 추가 최적화 후보(검토)
  - ECDSA 경로에서 임시 `BigInteger`/포인트 객체 재사용 가능성 검토(안전성 전제)
  - 서명 시 `ECDSASigner`/`HMacDSAKCalculator` 객체 수명 최적화(스레드 안전성 고려)
  - `recover`에서 포인트 연산 조합 시 불필요한 배열 복사 제거 여부 재점검

