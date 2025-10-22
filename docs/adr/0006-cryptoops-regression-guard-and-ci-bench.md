# ADR-0006: CryptoOps 회귀 방지 및 문서화/CI 벤치 통합 (Phase 6)

## Status
Accepted

## Context
- Phase 0–5를 통해 기능/성능/보안 관점의 기반(수학 타입 통일, 타입체크 최소화, 캐싱/레이어링, Low‑S/상수시간 비교/제로화)이 마련되었습니다.
- 장기적으로 성능/할당 특성이 유지되도록, 자동 계량과 회귀 가드 체계를 CI에 통합하고, 결과를 아티팩트로 보존하며, 문서/사이트에 반영하는 제도화가 필요합니다.
- 벤치마크의 비결정성(환경 노이즈)을 고려해 재현 가능 구성과 적절한 임계치를 설정해야 합니다.

## Decision
- 단기적으로 CI 통합은 보류하고, 로컬에서 JMH 벤치마크를 실행하여 저장된 기준선 JSON과 비교하는 절차를 운영합니다.
  - 핵심 대상: `CryptoOps.sign`/`recover`/`fromPrivate`/`keccak256` (Throughput, ops/s)
  - 선택 대상: GC 프로파일(alloc.rate.norm: bytes/op, gc.time 합계)
  - 기준선 소스: `benchmarks/reports/` 폴더의 최신 안정 결과(JSON). 필요 시 특정 파일을 기준선으로 고정 사용.
- 성능 임계치(로컬 평가 가이드):
  - 처리량(ops/s): 기준선 대비 −2% 초과 하락 시 주의(중앙값 또는 score 평균 비교)
  - 지연(ns/op): 필요 시 time 모드로 별도 측정하여 +2% 초과 증가 시 주의
  - bytes/op, GC time: 기준선 대비 +5% 초과 증가 시 주의
- 문서 업데이트:
  - 로컬 실행/비교 방법, 파일 네이밍 규약, 임계치 해석 가이드를 본 ADR과 PLAN/사이트에 명시합니다.

## Scope
- 대상:
  - benchmarks 모듈(JMH 실행, JSON 출력, 선택적 GC 프로파일 수집)
  - 로컬 비교 절차와 문서/사이트(performance 페이지 요약)
- 비대상:
  - CI 워크플로/아티팩트 업로드/자동 가드(추후 재도입)
  - 암호 알고리즘/퍼블릭 API의 의미적 변경

## Implementation Outline (Local)
1) 실행 파라미터 고정
   - JVM: `-Xms2g -Xmx2g`
   - 모드: Throughput(ops/s), Warmup 5, Measurement 10, Fork 1, Threads 1
2) 로컬 실행 명령 예시
   - 정상: `sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"`
   - GC: `sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"`
   - 실행 후 결과를 `benchmarks/reports/<UTC_ISO>_feature-crypto-operations_<tag>_jmh.json` 으로 보관
3) 기준선 관리
   - 기준선 파일을 `benchmarks/reports/`에 명시적으로 지정(예: `baseline_main_YYYYMMDD.json`).
   - 새 변경 전/후 결과를 기준선과 비교하여 임계치 준수 여부를 확인.
4) 비교 방법(수동)
   - 핵심 벤치별 `score`(ops/s)를 비교(중앙값/평균 중 일관되게 사용).
   - GC 결과가 있는 경우 `alloc.rate.norm`(bytes/op), `gc.time`(합)을 함께 비교.
   - ±2%(ops/s)/+5%(bytes/op, gc.time) 가이드를 넘으면 리팩터링/원인 분석.
5) 문서 반영
   - 본 ADR, `PLAN.md`, 사이트(performance)에 로컬 절차를 명시하고 최신 기준선 링크를 갱신.

## Acceptance Criteria
- 기능/운영
  - 로컬에서 명시된 명령으로 벤치를 실행하고 결과 JSON을 `benchmarks/reports/`에 보관한다.
  - 기준선 JSON과 결과를 수동 비교해 임계치 가이드 준수 여부를 판단한다.
- 성능
  - 핵심 벤치 4종의 처리량/지연이 기준선 대비 임계치 내에서 유지된다.
  - bytes/op 및 GC time은 증가하지 않거나 임계치 내에서만 변동한다.
- 문서화
  - 로컬 실행/비교 절차와 기준선 파일 경로가 문서/사이트에 반영되어 있다.

## Testing/Verification Strategy
- 동일 JVM 옵션/측정 파라미터로 2~3회 반복 실행하여 편차를 파악하고 중앙값 기준으로 판단.
- 결과 JSON을 히스토리로 보존하여 회귀 추세를 체감적으로 확인.
- 기준선 갱신은 안정적 수치가 확인된 시점에 수동으로 수행(파일 교체/명시).

## Risks
- 환경 노이즈로 인한 플래키 실패 → 중앙값/반복 측정, 완충 임계치(+/−2%/5%), 일시적 재시도 정책.
- 머신 스펙/부하 변화로 기준선 이동 → 기준선 아티팩트에 머신/런타임 메타데이터를 저장하고, 변경 시 임시적으로 가드를 완화 후 재수립.
- 벤치 선택/구성이 실제 워크로드와 괴리 → 주기적으로 벤치 커버리지 검토 및 보완.

## Rollback Strategy
- 환경 변수/입력 플래그로 가드 비활성화 가능(예: `BENCH_GUARD=off`).
- 비교 단계만 임시 스킵하거나 임계치를 일시 완화.
- 문제 발생 시 이전 워크플로로 즉시 복귀하고, 원인 분석 후 재도입.

## References
- `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`
- `docs/adr/0002-cryptoops-typecheck-wrapping-minimization.md`
- `docs/adr/0003-cryptoops-caching-and-allocation-optimization.md`
- `docs/adr/0004-cryptoops-data-model-layering.md`
- `docs/adr/0005-cryptoops-security-and-consistency.md`
- `docs/perf/criteria.md`
