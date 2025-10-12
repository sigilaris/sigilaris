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


