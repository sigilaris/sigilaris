## PLAN: CryptoOps 성능 최적화

### 배경
- `CryptoOps`는 블록체인 성능의 핵심 경로로, 불필요한 타입 래핑/검증, `BigInt`↔`BigInteger` 변환, 임시 객체 생성이 잠재적 병목입니다.
- 목표: 동일한 기능/보안 수준을 유지하면서 지연(ns/op)과 할당(bytes/op)을 최소화하고 처리량(ops/s)을 최대화합니다.

### 원칙(Principles)
- P1. 외부 라이브러리 결과에 대한 타입체크/래핑 최소화
  - IO 경계에서 1회 검증, 핫패스에서는 검증된 타입만 사용
  - 리플렉션 기반 캐스팅(shapeless `cast`) 지양, 단순 분기로 대체
  - 핫패스에서 범용 타입클래스(cats 등) 호출 배제
- P2. Scala `BigInt` 지양, Java `BigInteger` 의존
  - 수학 연산은 전부 `BigInteger`로 통일, 변환 제거
  - `UInt256`에도 `BigInteger` 빠른 경로(생성/추출) 제공
- P3. 원 라이브러리 자료형으로 키페어 저장(실무 절충)
  - 소스오브트루스는 바이트 고정(32바이트 D, 64바이트 x||y)
  - JVM에서는 원 라이브러리 뷰(포인트/파라미터) 지연 생성+캐시
- P4. 할당/객체 생성 최소화
  - 상수/객체 캐싱: `HalfCurveOrder(BigInteger)`, `X9IntegerConverter`, `FixedPointCombMultiplier`, `ECDomainParameters`
  - 핫패스는 `Array[Byte]` 우선, `ByteVector`는 경계에서만
- P5. 보안 일관성
  - Low‑S 정규화 유지, 비밀값 비교는 상수시간 비교 사용
  - 엔디언/길이 규약 고정(big‑endian, 고정 32/64바이트)
- P6. 레이어링/플랫폼 분리
  - `shared`: 불변 값/코덱/검증, `jvm`: 고성능 구현/캐시
  - 핫/콜드 경로 분리: 핫패스에서 예외/`Either`/타입클래스 호출 제거
- P7. 의존 축소
  - 핫패스에서 cats/shapeless 제거하여 인라이닝/탈박싱 유도
- P8. 계량과 회귀 방지
  - JMH로 계량, 프로파일링(JFR/async‑profiler), CI에서 회귀 감시

### 평가 방법(Evaluation)
- 1차 KPI: 처리량(ops/s), 지연 p50/p99(ns/op), 할당(bytes/op), GC 시간(%), CPU 사용량
- 벤치 대상: `CryptoOps.sign`, `CryptoOps.recover`, `CryptoOps.fromPrivate`, `CryptoOps.keccak256`
- 도구: JMH(`sbt-jmh`), JFR/async‑profiler
- 정합성: 프로퍼티 테스트(서명 복구키 동일, Low‑S, 고정 길이/엔디언), 케이스 테스트

### 접근 전략(Core Tactics)
- `BigInteger`로 수학 경로 전면 통일, `BigInt` 경유 제거
- 핫패스의 cats/shapeless 제거, 표준 연산으로 대체
- 상수/객체 캐싱, 바이트 경로 통일
- 바이트를 소스오브트루스로, JVM 전용 뷰/캐시 제공

---

## 단계별 계획

### Phase 0 — 베이스라인 측정
- 목표: 현재 성능/할당 수치 확정(비교 기준 수립)
- 적용 포인트: 없음(측정 인프라만 추가)
- 절차: 평가방법 확보 → 벤치/프로파일 실행 → 결과 보존
- 평가 방법:
  - JMH 벤치 구현 및 실행, 결과 저장(JSON/CSV)
  - JFR/async‑profiler로 핫스팟/할당 트리 파악
- 성공 기준: 벤치와 리포트 아티팩트 생성, 재현 가능

### Phase 1 — BigInteger 통일
- 목표: `BigInt`↔`BigInteger` 변환 제거
- 적용 포인트:
  - `CryptoOps.HalfCurveOrder`, `sign`, `recoverFromSignature`, `fromPrivate` 연산을 `BigInteger`로 통일
  - `PublicKey.fromByteArray`에서 `BigInteger(1, bytes)` 경로 사용
  - `UInt256`: `from(BigInteger)`, `toBigInteger` 빠른 경로 제공
- 절차: 벤치 측정 → 구현 적용 → 벤치 재측정 → 비교
- 평가 방법: `sign/recover/fromPrivate` ns/op, bytes/op, ops/s 비교
- 성공 기준: 각 벤치 p50 ns/op 10%+ 개선 또는 bytes/op 10%+ 감소

### Phase 2 — 타입체크/래핑 최소화
- 목표: 핫패스에서 리플렉션/타입클래스 호출 제거
- 적용 포인트:
  - 키 생성부 `cast` 제거, 단순 타입 분기로 대체
  - `cats.Eq`/`===` 제거, 표준 비교로 대체
  - `Either` 생성/매핑을 경계로 이동, 핫패스는 happy‑path 직행
- 절차: 벤치 측정 → 구현 적용 → 벤치 재측정 → 비교
- 평가 방법: 동일 항목 + CPU 사이클/분기 예측률 관찰
- 성공 기준: ns/op 5–10% 개선, 할당 감소

### Phase 3 — 캐싱/할당 최적화
- 목표: 상수/객체 재사용으로 할당 축소
- 적용 포인트:
  - `X9IntegerConverter`, `FixedPointCombMultiplier`, `Keccak.Digest256`(가능 시 스레드로컬), `ECDomainParameters`, `HalfCurveOrder(BigInteger)` 캐시
  - `new BigInteger(1, message)` 등 반복 생성 최소화(공용 버퍼/슬라이스 재사용)
- 절차: 벤치 측정 → 구현 적용 → 벤치 재측정 → 비교
- 평가 방법: JMH bytes/op, GC 시간, 프로파일링 할당 트리
- 성공 기준: bytes/op 20%+ 감소, GC 시간 감소 확인

### Phase 4 — 데이터 모델/레이어링
- 목표: 바이트를 소스오브트루스로, JVM 뷰/캐시 제공
- 적용 포인트:
  - `KeyPair`/`PublicKey`에 JVM‑전용 캐시 필드(지연 생성) 도입
  - `ByteVector`는 경계에서만 사용, 내부는 `Array[Byte]`
- 절차: 벤치 측정 → 구현 적용 → 벤치 재측정 → 비교
- 평가 방법: 벤치 및 힙 프로파일에서 캐시 적중으로 인한 할당/지연 감소 확인
- 성공 기준: ns/op 5–10% 추가 개선 또는 bytes/op 추가 감소

### Phase 5 — 보안/일관성 강화
- 목표: 성능 최적화가 보안 속성 훼손 없음 보장
- 적용 포인트:
  - Low‑S 정규화 경로 테스트 강화
  - 상수시간 비교 도입(비밀 비교 경로)
  - 엔디언/길이 규약 테스트 고정
- 절차: 테스트 강화 → 벤치 재측정(회귀 확인)
- 평가 방법: 프로퍼티/케이스 테스트 통과, 벤치 수치 유지
- 성공 기준: 모든 테스트 통과, 성능 회귀 없음(±2% 이내)

### Phase 6 — 회귀 방지/문서화(로컬 기준선 운용)
- 목표: CI 없이도 로컬에서 기준선 대비 성능을 지속 확인
- 적용 포인트:
  - 로컬 JMH 실행 및 결과 JSON 보관(`benchmarks/reports/`)
  - 기준선 JSON과의 수동 비교(ops/s, 선택: bytes/op/gc.time)
  - `PLAN.md`/사이트에 실행 방법과 임계치 가이드(±2%/±5%) 명시
- 절차: 로컬 실행 → 결과 저장/비교 → 필요 시 리팩터링 또는 기준선 갱신
- 평가 방법: 반복 실행의 중앙값 기준으로 기준선 내 유지 확인
- 성공 기준: 핵심 벤치가 기준선 대비 임계치 내 유지, 문서 최신화

---

## JMH/프로파일링 설정 가이드

### SBT 플러그인 추가
`project/plugins.sbt`에 아래를 추가합니다.

```scala
addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.8")
```

### 별도 benchmarks 모듈 구성
벤치마크는 `core`와 분리된 전용 모듈로 구성합니다.

```scala
// build.sbt 예시 (발췌)

lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .dependsOn(core.jvm)
  .settings(
    publish / skip := true,
    Test / fork := true,
  )

lazy val root = (project in file("."))
  .aggregate(
    core.jvm,
    core.js,
    benchmarks,
  )
  .dependsOn(core.jvm)
```

### 예시 벤치마크 스켈레톤
경로: `benchmarks/src/jmh/scala/org/sigilaris/core/crypto/CryptoOpsBenchmark.scala`

```scala
package org.sigilaris.core.crypto

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit
import java.security.SecureRandom

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2, jvmArgs = Array("-Xms2g","-Xmx2g"))
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class CryptoOpsBenchmark:
  private val rnd  = new SecureRandom()
  private val kp   = CryptoOps.generate()
  private val msg  =
    val arr = new Array[Byte](32)
    rnd.nextBytes(arr)
    arr
  private val hash = CryptoOps.keccak256(msg)

  @Benchmark
  def sign(): AnyRef = CryptoOps.sign(kp, hash)

  @Benchmark
  def recover(): AnyRef =
    val sig = CryptoOps.sign(kp, hash).toOption.get
    CryptoOps.recover(sig, hash)

  @Benchmark
  def fromPrivate(): AnyRef =
    CryptoOps.fromPrivate(kp.privateKey.toBigInt)

  @Benchmark
  def keccak256(): Array[Byte] =
    CryptoOps.keccak256(msg)
```

### 프로파일링(로컬)
```bash
# benchmarks 모듈에서 JMH 실행
sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*"
# JFR 예시
java -XX:StartFlightRecording=filename=recording.jfr -jar target/jmh/jmh.jar .*CryptoOpsBenchmark.*
# async-profiler 예: perf
profiler.sh -d 30 -f flame.svg <jmh_forked_jvm_pid>
```

---

## 위험/롤백 전략
- 수학 타입 전환(Phase 1)은 변경 폭이 큼: 각 단계 후 단위 커밋으로 롤백 용이하게 운영
- 캐싱은 스레드 안전성 고려(불변/지연 초기화). 문제가 있으면 캐시 비활성화 플래그로 롤백

## 산출물
- 벤치 결과 아티팩트(JMH JSON/CSV, JFR/플레임그래프)
- 문서: 본 `PLAN.md`, 엔디언/길이 규약, 보안 속성
- CI 파이프라인: JMH 실행/보관, 회귀 알림



---

## Baseline Artifacts
- Latest JSON: `benchmarks/reports/2025-10-12T06-13-14Z_feature-crypto-operations_4d0c557_jmh.json`
- Bench guide: `benchmarks/README.md`
- Guidelines: site `site/src/ko/performance/crypto-ops.md` (EN: `site/src/en/performance/crypto-ops.md`)
