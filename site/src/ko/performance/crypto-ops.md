## CryptoOps 성능/보안 규약과 벤치 가이드

### 규약(핵심)
- 엔디언/길이: big-endian, 고정 길이 32바이트(스칼라)·64바이트(좌표 x||y)
- Low‑S 정규화 유지, 비밀값 비교는 상수시간 비교 사용
- 바이트가 소스 오브 트루스, JVM에서는 라이브러리 뷰를 지연 생성/캐시

### 최적화 원칙 요약(P1–P8)
- BigInteger 경로 통일, cats/shapeless 핫패스 제거, 상수/객체 캐싱, 바이트 경로 우선
- 자세한 내용: `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`, `docs/perf/criteria.md`, `benchmarks/README.md`

### 벤치 실행 요약(JMH)
- 실행 예시: `sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*"`
- JVM 옵션: 기본 `-Xms2g -Xmx2g` (벤치 클래스 @Fork에서 설정 가능)
- 결과: `benchmarks/reports/<timestamp>_<branch>_<sha>_jmh.json`에 보관

### 베이스라인 링크
- 최신 베이스라인은 저장소의 `benchmarks/reports/` 디렉토리를 참고하세요.


### Phase 6 — 로컬 회귀 가드 절차

ADR-0006에 따라 로컬에서 기준선 대비 성능 회귀를 수동으로 점검합니다.

- 고정 파라미터: Throughput(ops/s), Warmup 5, Measurement 10, Fork 1, Threads 1, JVM `-Xms2g -Xmx2g`
- 실행(기본):
  ```bash
  sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"
  ```
- 실행(GC 포함):
  ```bash
  sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"
  ```
- 보관 규칙: `benchmarks/reports/<UTC_ISO>_<branch>_<sha>_jmh.json` (GC 시 `_jmh-gc.json` 권장)
- 임계치 가이드:
  - 처리량(ops/s): 기준선 대비 −2% 초과 하락 시 주의
  - GC 측정 시: bytes/op(`alloc.rate.norm`), `gc.time` 합계가 +5% 초과 증가 시 주의
- 비교 방법: 각 벤치의 `score`(ops/s) 및(선택) GC 메트릭을 기준선 JSON과 나란히 비교합니다. 2–3회 반복 실행 후 중앙값 기준으로 판단하세요.

자세한 절차와 근거는 `docs/adr/0006-cryptoops-regression-guard-and-ci-bench.md`와 `benchmarks/README.md`를 참조하세요.


