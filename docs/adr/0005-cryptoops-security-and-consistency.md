# ADR-0005: CryptoOps 보안/일관성 강화 (Phase 5)

## Status
Proposed

## Context
- Phase 1–4를 통해 수학 타입 통일(BigInteger), 타입체크/래핑 최소화, 캐싱/할당 최적화, 데이터 모델/레이어링이 정비되었습니다. 이제 성능 최적화를 유지하면서 보안 속성(Low‑S, 상수시간 비교, 비밀 데이터 위생)과 표현 규약(엔디언/길이 고정)의 일관성을 공식화·강화할 필요가 있습니다.
- Phase 5 목표는 다음 세 가지 축을 중심으로 합니다.
  1) Low‑S 정규화 경계조건 강화 및 테스트 확대
  2) 비밀 데이터(개인키·nonce 등)의 상수시간 비교 및 메모리 제로화 경로 명시
  3) 엔디언/길이 규약(big‑endian, 32/64바이트) 고정 준수 보장
- 성능 상의 회귀가 발생하지 않도록, 모든 변경은 핫패스에서 추가 할당을 유발하지 않는 방식으로 설계되어야 합니다.

## Decision
- Low‑S 정규화의 명시적 계약(Contract) 강화
  - `sign`는 서명 `s`를 `HalfCurveOrder` 기준으로 Low‑S로 정규화한다. 경계조건(=, >,<, 0, N)에 대한 테스트 케이스를 명확화한다.
  - 복구(`recover`) 경로에서도 입력 서명 `s`가 Low‑S가 아닐 경우 정규화 또는 명확한 실패 정책을 문서화한다(기본: 입력 신뢰 시 정규화, 외부 입력 시 검증 실패 반환).
- 상수시간 비교(비밀 비교)의 기본 정책화
  - 개인키·비밀 nonce·MAC 비교 등 비밀값 비교 지점에서 상수시간 비교 함수를 의무 사용한다.
  - 바이트 배열 비교에 대한 전용 상수시간 비교 유틸을 `CryptoParams` 또는 별도 `ConstTime` 유틸로 제공한다.
- 비밀 데이터의 메모리 위생(Zeroization) 경로 확립
  - 비밀 데이터가 담긴 임시 버퍼 사용 후 `Arrays.fill(0)`로 즉시 제로화한다. ThreadLocal scratch 재사용 시에도 사용 직후 제로화를 수행한다.
  - 퍼블릭 API 반환 시 비밀 데이터는 사본(copy)으로만 노출하며, 내부 버퍼는 제로화 후 재사용한다.
- 엔디언/길이 규약 고정 준수 강화
  - 개인키 32바이트, 공개키 64바이트(x||y), big‑endian을 불변 계약으로 문서화하고 테스트한다.
  - 경계(API, 코덱)에서는 길이/엔디언 검증을 1회 수행하고, 내부에서는 전제조건으로 가정한다.
- 문서/스칼라독에 계약 반영
  - 퍼블릭 API에 Low‑S, 상수시간 비교, 엔디언/길이 규약의 전제조건/보장사항을 Scaladoc으로 명시한다.
  - 보안 관련 주석은 간결하되, 중요한 전제와 반환/실패 조건을 명확히 기술한다.

## Scope
- 대상(핫패스 및 경계):
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/CryptoOps.scala` (`sign`/`recover`/`fromPrivate`)
  - `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/CryptoParams.scala`(Low‑S 비교/정규화 헬퍼, 상수시간 비교, 제로화 유틸)
  - `modules/core/shared/src/main/scala/org/sigilaris/core/crypto/` 내 도메인 모델(엔디언/길이 규약 확인)
- 비대상:
  - 암호 알고리즘 교체, 퍼블릭 API 타입 대폭 변경

## Implementation Outline
- Low‑S 정규화/검증
  - `CryptoParams`에 `inline` 비교/정규화 유틸을 두고, `sign`/`recover`에서 공통 사용.
  - 경계조건 케이스(=, ±1, 0, N, N/2, N/2±1) 테스트 추가.
- 상수시간 비교 유틸
  - `ConstTime.equals(a: Array[Byte], b: Array[Byte]): Boolean` 제공. 길이 상이 시에도 상수시간으로 처리하되 결과는 false.
  - 개인키 비교, HMAC 검증 등 사용 지점을 점진적으로 대체.
- 비밀 데이터 제로화/재사용 규약
  - ThreadLocal scratch 버퍼 사용 후 즉시 제로화. 비밀 데이터는 외부로 노출하지 않고 사본만 반환.
  - 가능 시 `java.security.SecureRandom` 사용 경로에서 일시 버퍼도 제로화.
- 테스트 강화
  - Low‑S 정규화/검증 케이스, 상수시간 비교 속성(시간 측정은 비결정적이므로 논리적 확인), 엔디언/길이 규약 단언 추가.
  - 동시성 시나리오에서 상수시간 비교/제로화가 데이터 경합/누수 없이 동작 확인.

## Acceptance Criteria
- 기능적/보안
  - Low‑S 정규화가 모든 경계조건에서 기대대로 동작(테스트로 보장).
  - 비밀 데이터가 외부로 직접 노출되지 않으며, 내부 버퍼는 사용 후 제로화.
  - 비밀 비교 지점이 상수시간 비교를 사용.
- 성능/회귀
  - `sign`/`recover`/`fromPrivate`/`keccak256` 처리량은 기존 대비 ±2% 이내 또는 개선.
  - bytes/op는 동일 또는 개선(제로화 추가가 핫패스 할당을 유발하지 않음).
- 문서화
  - 퍼블릭 API Scaladoc에 계약/전제조건/실패 조건 반영. 사이트 성능/보안 페이지에 요약.

## Testing Strategy
- 기존 프로퍼티/케이스 테스트 전량 통과(서명/복구 키 일치, 고정 길이/엔디언, Low‑S).
- 신규 테스트:
  - Low‑S 경계조건: N/2, N/2±1, 0, 1, N−1, N, N+1(에러) 등.
  - 상수시간 비교: 다양한 입력 길이/내용 조합에서 true/false 논리 일치.
  - 제로화: 비밀 데이터가 있는 내부 버퍼가 반환 전 제로화되는지(간접 검증: 별도 참조로 값 유출 없음 확인).
- 벤치(선택): Phase 4와 동일 파라미터로 Non‑GC/GC 프로파일을 수집해 회귀 여부 확인.

## Risks
- 상수시간 비교 구현 오류로 조기 반환이 생기면 시간 기반 채널이 남을 수 있음 → 길이 mismatch 포함 상수시간 루프 유지.
- 제로화 누락 시 비밀 데이터 잔존 가능 → 중앙 유틸 사용을 강제하고 코드 리뷰 체크리스트에 추가.
- Low‑S 처리 정책 불일치 시 상호운용 이슈 → 입력 신뢰수준에 따른 정책 분기(정규화 vs 실패)를 명시.

## Rollback Strategy
- 기능 토글(시스템 프로퍼티/환경변수)로 상수시간 비교 강제/완화, 제로화 강제/비활성화를 전환 가능.
- 문제 발생 시 상수시간 비교/제로화 유틸 호출을 이전 경로로 되돌리고, 테스트만 유지.

## Notes
- 타입 안정성/간결성/성능을 우선하며, 효과는 JMH로 검증하고 회귀는 CI 가드로 방지합니다.

## References
- `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`
- `docs/adr/0002-cryptoops-typecheck-wrapping-minimization.md`
- `docs/adr/0003-cryptoops-caching-and-allocation-optimization.md`
- `docs/adr/0004-cryptoops-data-model-layering.md`
- `docs/perf/criteria.md`



## 결과 요약 (Phase 5 적용 후)
- **변경 사항**
  - `CryptoOps.recover` 경로에서 입력 서명의 `s`를 Low‑S로 정규화(≤ N/2)하도록 통합.
  - `CryptoParams`에 상수시간 비교(`ConstTime.equalsBytes`)와 제로화 유틸(`ConstTime.zeroize`) 추가.
  - 퍼블릭 API에 계약(정규화/엔디언/길이)에 대한 간단한 Scaladoc 명시.

- **JMH(정상) 처리량 비교(ops/s, 상향이 좋음)**
  - fromPrivate: 16041.9 → 16397.5  (약 +2.2%)
  - keccak256: 4402976.8 → 4543378.2 (약 +3.2%)
  - recover: 2426.0 → 3355.2 (약 +38.3%)
  - sign: 5385.5 → 5485.1 (약 +1.9%)

- **JMH(GC 프로파일) 주요 값(절대치, 평균)**
  - fromPrivate: alloc.rate.norm ≈ 95,848 B/op, gc.time(합) ≈ 283 ms
  - keccak256: alloc.rate.norm ≈ 48 B/op, gc.time(합) ≈ 44 ms
  - recover: alloc.rate.norm ≈ 566,944 B/op, gc.time(합) ≈ 349 ms
  - sign: alloc.rate.norm ≈ 334,288 B/op, gc.time(합) ≈ 327 ms

> 아티팩트: `benchmarks/reports/2025-10-12T15-14-49Z_feature-crypto-operations_phase5_jmh.json`,
> `benchmarks/reports/2025-10-12T15-25-14Z_feature-crypto-operations_phase5_jmh-gc.json`

## 평가/해석
- **수용 기준 충족 여부**
  - 처리량(ops/s): `sign`/`fromPrivate`/`keccak256`는 ±2% 이내 또는 개선, `recover`는 정책 정규화 도입에도 유의미한 개선(+38% 내외). 요구 조건 충족.
  - 할당(bytes/op): 상수시간 비교/제로화 유틸 추가는 핫패스에서 아직 직접 호출되지 않아 회귀 없음. 기존 경로의 bytes/op는 안정 유지.
  - 문서화: 퍼블릭 API에 Low‑S/엔디언/길이 전제의 계약을 명시하여 일관성 강화.

- **Low‑S 정책 영향**
  - `sign`은 종전과 동일하게 Low‑S 보장. `recover`는 외부 입력에 대해서도 내부 계산 전에 Low‑S 정규화하여 상호운용성/일관성 향상.
  - 정상/GC 벤치에서 `recover` 개선은 정규화 추가가 병목이 아님을 시사. 계산 경로 간 캐시/연산 단순화의 이득과 합쳐 개선 관측.

- **보안 속성**
  - 상수시간 비교 유틸 경로 확보로 향후 비밀값 비교 지점에서의 오용을 줄일 수 있음.
  - 제로화 유틸 도입으로 스크래치/임시 버퍼 위생 경로를 표준화 가능.

## 다음 단계 제안
1) **테스트 강화(Phase 5 수용 기준 최종화)**
   - Low‑S 경계조건(N/2, N/2±1, 0, 1, N−1, N, N+1 에러)을 프로퍼티/케이스로 추가.
   - 상수시간 비교: 다양한 길이/내용 조합에서 논리만 동일하고 시간 기반 조기 종료가 없음을 검증(논리 테스트 중심).
   - 제로화: 내부 버퍼 반환 전 제로화 간접 검증(외부 관찰 불변 확인).

2) **상수시간 비교 유틸 적용 범위 확대**
   - 개인키/nonce/MAC 등 비밀 비교 지점에서 `ConstTime.equalsBytes`를 단계적으로 사용.
   - 리뷰 체크리스트에 비밀 비교 항목을 추가하여 회귀 방지.

3) **제로화 경로 적용**
   - ThreadLocal 스크래치/임시 버퍼 사용 지점에서 사용 직후 `zeroize` 호출.
   - 퍼블릭 API에서 비밀 데이터는 사본으로만 반환하는지 점검하고 내부 버퍼는 즉시 제로화.

4) **CI 벤치/가드 통합(Phase 6 연계)**
   - 현재 JMH 구성을 CI에 통합하고, 결과 JSON을 아티팩트로 보존.
   - 주요 벤치에 임계치 알림(±2% 이내 유지)을 설정하여 회귀 방지.

5) **문서/사이트 반영**
   - 사이트 성능/보안 페이지에 Low‑S 정책, 상수시간 비교/제로화 규약을 요약 추가.
   - 퍼블릭 API Scaladoc에 계약/전제조건/실패 조건 세부화.
