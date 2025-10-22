# ADR-0007: PublicKey를 sealed trait로 분리하고 ECPoint 뷰를 추가

## Status
Implemented

## Context
- 현재 `PublicKey`는 `x: UInt256`, `y: UInt256`(64바이트 x||y, big-endian) SoT를 보관하며, 필요 시 JVM에서 `ECPoint` 뷰를 재구성한다.
- ECDSA 서명 검증(recover) 경로가 전체 병목 중 가장 큰 비중을 차지하며, 여기서 포인트를 `ECPoint`로 계산한 뒤 다시 65바이트 인코딩→64바이트 분해→정수 파싱→UInt256 포장으로 왕복하는 비용이 반복된다.
- 프로젝트 원칙(ADR-0001): 도메인 SoT는 고정 길이 바이트(32/64B), 수학 연산은 `java.math.BigInteger`/곡선 타입을 사용. 성능을 해치지 않고 이 원칙을 지키려면, SoT와 실행 뷰를 분리하되 동치성/직렬화 기준은 SoT로 고정해야 한다.

## Decision
- `PublicKey`를 sealed trait로 정의하고, 두 가지 구현을 제공한다.
  - `PublicKey.XY(x: UInt256, y: UInt256)`: SoT(바이트) 중심 구현. 필요 시 `ECPoint`를 지연 생성/캐시.
  - `PublicKey.Point(p: ECPoint)`: JVM 전용 실행 뷰. 필요 시 64바이트 x||y와 `UInt256` 좌표를 지연 생성/캐시.
- 컴패니언 오브젝트에 두 생성자를 제공한다.
  - `def fromXY(x: UInt256, y: UInt256): PublicKey`
  - `def fromECPoint(p: ECPoint): PublicKey`
- 퍼블릭 API의 동치성/해시/직렬화 규약은 항상 “64바이트 x||y(big-endian)”를 기준으로 한다.
- recover 경로에서는 `ECPoint` 결과를 바로 `PublicKey.Point`로 감싸 반환하여 재인코딩/정수 파싱/패딩을 피한다.

## Consequences
### 장점
- recover 경로에서 불필요한 65→64바이트 재인코딩 및 정수 파싱 제거로 할당/지연 감소.
- EC 연산과의 상호운용 단순화: 계산된 `ECPoint`를 바로 도메인 값으로 승격.
- SoT(바이트) 규약은 유지되어 직렬화/동치성/해시의 일관성 확보.

### 단점/리스크
- 타입 분화로 인지 부하 증가(두 구현 관리). 내부 추상화로 완화 필요.
- 캐시 일관성 주의: 파생값(lazy) 캐시는 불변 전제에서만 안전. 외부에 배열을 반환할 때는 복사본을 제공해야 한다.
- JVM 종속 요소(`ECPoint`)는 shared에 노출하지 않고 jvm 모듈로 한정해야 함.

## Implementation Outline
- 파일: `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/PublicKey.scala`
- sealed trait `PublicKey` 공통 API(예):
  - `def toBytes: ByteVector` // 64B 사본 반환
  - `def x: UInt256` / `def y: UInt256`
  - `private[crypto] def asECPoint(): ECPoint`
  - equals/hashCode는 64B 값 기준으로 구현
- 구현체:
  - `final case class XY(x: UInt256, y: UInt256) extends PublicKey`
    - `lazy val xy64: Array[Byte]` 생성 및 캐시
    - `lazy val point: ECPoint` 생성 및 캐시(0x04 || x||y 디코드)
    - `asECPoint()`는 항상 `normalize()`된 포인트를 반환하며 정규화 포인트를 별도 캐시
  - `final case class Point(p: ECPoint) extends PublicKey`
    - `lazy val xy64: Array[Byte]` 생성 및 캐시(곡선 좌표에서 바로 64B 구성: BigInteger → 32B 고정 채움)
    - `lazy val x: UInt256`, `lazy val y: UInt256` 생성 및 캐시(항상 정규화된 포인트의 affine 좌표 사용)
    - 내부 보관 포인트는 필요 시 `normalize()`하여 별도 캐시에 저장 후 재사용
- 컴패니언:
  - `fromXY(x, y)`는 `XY`
  - `fromECPoint(p)`는 `Point`
  - `fromByteArray(arr: Array[Byte])`는 기존대로 64B 검증 후 `XY` 생성
- 코덱:
  - 바이트 인코더: `toBytes`
  - 바이트 디코더: 64B 고정 → `fromByteArray`
  - JSON 코덱: 64자(=32B*2) 소문자 hex 기준 유지(기존 규약 준수)
- 사용처 변경:
  - `CryptoOps.recoverFromSignature` 종료부를 `Some(PublicKey.fromECPoint(q))`로 교체

## API/Contract
- 불변식: 퍼블릭 키는 곡선 상 유효한 점이며, x,y는 0 ≤ v < 2^256. 외부 API로는 64바이트 big-endian x||y 표현 보장.
- equals/hash: 64바이트 값 기준으로 동일성/해시 결정(표현 형태에 무관하게 동일 처리).
- 직렬화: 바이트/JSON 모두 기존 규약 유지. 내부 표현은 관찰 불가능해야 함.

## Testing Strategy
- 기존 서명/복구/직렬화 테스트 전량 통과.
- 신규 테스트:
  - 변형 간 동치성: `fromXY(x,y)`와 `fromECPoint(p)`가 같은 점이면 equals/hash 동일.
  - 64B 규약: `toBytes`가 항상 64바이트, 불변 사본 반환.
  - 경계값: x,y가 {0, 1, N/2, N/2±1, N−1} 근방에서도 일관.
  - recover 경로 성능: JMH로 기존 대비 ops/s, bytes/op 변화를 확인(±2% 이내 유지 또는 개선 기대).

## Performance Expectations
- 제거되는 비용: recover에서의 65B 인코딩→64B 분해→정수 파싱 경로.
- 예상 효과: 기존 벤치에서 `recover`가 BigInteger 통일만으로도 +30~40% 개선이 관측됨. 본 변경으로 추가적인 미세 개선(할당/파싱 감소)을 기대.
- 회귀 가드: CI 벤치(ops/s, bytes/op)로 ±2% 이내 유지 확인.

## Risks and Mitigations
- 표현 드리프트: equals/hash/코덱을 64B 값으로 고정하고 테스트로 보장.
- 캐시 누락/과도한 할당: `lazy val`과 조건부 캐시(정책 플래그)로 제어.
- JVM 종속 노출: ECPoint 기반 구현은 jvm 모듈에만 위치시키고, shared에 노출 금지.
 - 비정규화 포인트 사용: `ECPoint#getAffineX/YCoord`는 정규화되지 않은 포인트에서 예외를 유발할 수 있음 → `asECPoint()`에서 항상 `normalize()`된 포인트를 반환하도록 강제하고 캐시.

## Rollback Strategy
- 기능 토글(빌드 플래그/정책)로 `Point` 변형 생성을 비활성화하고, `XY`만 사용하도록 되돌릴 수 있음.
- recover 경로에서 `fromECPoint` 대신 기존 64B 경로로 즉시 되돌릴 수 있음.

## Migration Plan
1) `PublicKey`를 sealed trait로 리팩터, 두 구현 추가.
2) 컴패니언 생성자/코덱 정리(기존 시그니처 유지).
3) `CryptoOps.recoverFromSignature`에서 `fromECPoint` 사용.
4) 테스트/벤치 갱신 및 CI 가드 설정.

## References
- ADR-0001: BigInteger 단일화 및 바이트 SoT
- ADR-0004: 데이터 모델 레이어링(SoT=바이트, 연산=BigInteger/곡선)
- 현행 구현: `modules/core/jvm/src/main/scala/org/sigilaris/core/crypto/PublicKey.scala`, `CryptoOps.scala`

