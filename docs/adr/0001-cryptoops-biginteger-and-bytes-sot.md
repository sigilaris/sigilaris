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

## References
 - site: `site/src/ko/performance/crypto-ops.md` (EN: `site/src/en/performance/crypto-ops.md`)
 - Acceptance Criteria: `docs/perf/criteria.md`
 - Bench Guide: `benchmarks/README.md`
 - BouncyCastle secp256k1 구현 세부


