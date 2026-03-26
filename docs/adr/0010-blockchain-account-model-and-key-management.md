# ADR-0010: Blockchain Account Model and Key Management

## Status
Draft

## 개요

프라이빗 블록체인을 위한 계정 모델과 키 관리 시스템을 정의한다. Named Account는 온체인에 공개되는 식별자임을 전제로 하며(블록 익스플로러에 전면 공개), 가디언 기반 키 복구 메커니즘을 지원한다.

## Public Key Identifier (KeyId20)

이더리움 어드레스 규칙을 사용한다:
- 공개키(64바이트)의 Keccak256 해시값 중 마지막 20바이트를 사용
- Unnamed Account의 식별자로 사용(KeyId20, 20바이트); 사용자 노출 주소로는 직접 사용하지 않음
- Ethereum에서 검증된 방식으로 충돌 위험 수용 가능

### Account 타입
- `Account`는 계정 식별 타입으로, 다음 두 경우의 수를 갖는다.
  - `Named(name: Name)` — 온체인 공개 이름 기반 계정
  - `Unnamed(keyId: KeyId20)` — KeyId20 기반 계정

## 계정 모델

### Named Account
- UTF-8 문자열 이름으로 식별
- 하나의 이름에 여러 공개키 등록 가능
- 가디언을 통한 키 복구 메커니즘 제공
- 비밀키 분실 시 가디언이 새 키 등록 가능
 - 이름은 온체인에 완전 공개되므로 개인정보·민감정보를 포함하지 않는다(운영 정책으로 제한). 완전 휴먼리더블 이름은 일부 예외에만 사용.

### Unnamed Account
- KeyId20로만 식별
- 온체인 상태로 관리하지 않음
- 잔고만 UTXO 상태에 기록
- 비밀키 분실 시 복구 불가능

### 가디언 (Guardian)
- 서비스 운영자가 담당
- Named Account의 키 복구 지원
- 오프체인 KYC를 통해 계정 소유자 확인 후 새 키 등록
- 프라이빗 블록체인의 중앙화된 신뢰 모델

## 상태 구조

### 계정 정보
```
name: Name(Utf8) -> AccountInfo
```

`AccountInfo` 필드:
- `guardian: Option[Account]` - 단일 가디언(없을 수도 있음)
- `nonce: BigNat` - 계정 상태 변경 트랜잭션용 순차 번호(정확히 +1 증가)

### 키 등록 정보 (Named Account 전용)
```
nameKey: (Name, KeyId20) -> KeyInfo
```

`KeyInfo` 필드:
- `addedAt: Instant` - 키 추가 시각(java.time.Instant)
- `expiresAt: Option[Instant]` - 선택적 만료 시각
- `description: Utf8` - 키에 대한 설명

### 잔고 관리
UTXO 모델로 별도 관리:
```
(AccountId, TokenDefId, TokenId, UTXOHash) -> ()
```

`AccountId`는 Named Account의 경우 `Name`, Unnamed Account의 경우 `KeyId20`

## 트랜잭션

### 공통 필드 (Envelope)
- `networkId: BigNat` — 체인/네트워크 식별자
- `createdAt: Instant` — 트랜잭션 생성 시각(java.time.Instant)
- `memo: Option[Utf8]` — 선택적 메모(감사/운영 용도)

모든 트랜잭션은 위 Envelope를 포함하며, 서명/검증은 Envelope를 포함한 페이로드 해시 위에서 수행한다.

### CreateNamedAccount
새로운 Named Account 생성

**파라미터:**
- `name: Name` - 계정 이름 (UTF-8 문자열)
- `initialKeyId: KeyId20` - 최초 등록 공개키의 식별자(KeyId20)
- `guardian: Option[Account]` - 단일 가디언(선택)

**서명:** `initialKeyId`에 대응하는 공개키의 비밀키

**사전조건:**
- `name`이 계정 상태에 존재하지 않음
- `name`이 유효한 UTF-8 문자열

**사후조건:**
- `name -> AccountInfo(guardians, nonce=0)` 생성
- `(name, initialPublicKey) -> KeyInfo(현재시각, None, "")` 생성

### UpdateAccount
계정 정보 업데이트 (가디언 목록 변경)

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `newGuardian: Option[Account]`

**서명:** 해당 계정에 등록된 키 중 하나 또는 가디언

**사전조건:**
- 계정 존재
- nonce가 현재 계정의 nonce와 일치

**사후조건:**
- `AccountInfo.guardian` 업데이트(설정/변경/해제)
- `AccountInfo.nonce` 증가

### AddKeyIds
Named Account에 새 공개키 등록

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Map[KeyId20, Utf8]` — 키 식별자와 설명(description)
- `expiresAt: Option[Instant]` — 모든 신규 키에 적용할 선택적 만료 시각

**서명:** 해당 계정에 등록된 키 중 하나 또는 가디언

**사전조건:**
- 계정 존재
- nonce가 현재 계정의 nonce와 일치
- 각 `keyId`가 해당 계정에 미등록 상태

**사후조건:**
- 각 `(name, keyId) -> KeyInfo(addedAt=now, expiresAt, description)` 생성
- `AccountInfo.nonce` 증가

### RemoveKeyIds
Named Account에서 공개키 제거

**파라미터:**
- `name: Name`
- `nonce: BigNat`
- `keyIds: Set[KeyId20]`

**서명:** 해당 계정에 등록된 키 중 하나 또는 가디언

**사전조건:**
- 계정 존재
- nonce가 현재 계정의 nonce와 일치
- 각 `keyId`가 해당 계정에 등록되어 있음

**사후조건:**
- 해당 `(name, keyId)` 키 정보 제거
- `AccountInfo.nonce` 증가
- 마지막 키까지 제거 가능 (잔고는 UTXO로 별도 관리)

### RemoveAccount
Named Account 삭제

**파라미터:**
- `name: Name`
- `nonce: BigNat`

**서명:** 해당 계정에 등록된 키 중 하나 또는 가디언

**사전조건:**
- 계정 존재
- nonce가 현재 계정의 nonce와 일치
 - 잔고 확인은 모듈 경계상 수행하지 않음(Token/UTXO 모듈에 의존하지 않음). 운영 정책으로 사전 보장.

**사후조건:**
- 계정 상태에서 제거
- 모든 `(name, *)` 키 정보 제거
- `AccountInfo.nonce` 증가 (삭제 전)
- UTXO 잔고는 영향받지 않음 (접근 불가능해짐)

## Context

프라이빗 블록체인 환경에서 사용자 친화적인 계정 시스템이 필요하다:
- 사용자가 긴 해시값 대신 이름으로 계정을 식별할 수 있어야 함
- 비밀키 분실 시 복구 메커니즘 제공
- 서비스 운영자의 투명성과 감사 가능성(auditability) 향상이 목표
- 탈중앙화보다는 프라이빗 체인의 특성을 활용한 실용적 접근

## Decision

### 계정 타입
- **Named Account**: 사용자 친화적 이름 기반, 키 복구 가능
- **Unnamed Account**: 공개키 요약만 사용, 온체인 상태 미관리

### 키 관리
- 하나의 Named Account에 여러 공개키 등록 가능
- 키별 메타데이터(추가일, 만료일, 설명) 관리
- Nonce 기반 replay attack 방지

### 가디언 시스템
- 서비스 운영자가 가디언 역할 수행
- 오프체인 KYC를 통한 계정 소유자 확인
- 계정 소유자와 가디언 모두 키 추가/제거 권한 보유

### Public Key Identifier (KeyId20)
- Ethereum 방식 채택 (Keccak256 해시의 마지막 20바이트)
- 검증된 방식으로 충돌 위험 수용 가능

## Scope

이 ADR은 다음을 다룬다:
- Named Account 생성 및 관리
- 공개키 등록/제거 메커니즘
- 가디언 기반 키 복구
- Replay attack 방지 (nonce)
- 계정 삭제

## Non-Goals

이 ADR에서 다루지 않는 사항:
- **UTXO 상세 구조**: 잔고 계산 및 트랜잭션 로직은 별도 ADR 필요
- **이름 검증 규칙**: UTF-8 유효성 외 추가 제약은 오프체인에서 관리
- **Multi-signature 지원**: 복잡도 대비 이점 부족, 향후 별도 검토
- **스마트 컨트랙트**: 프로그래머블 계정은 범위 밖
- **수수료 모델**: 프라이빗 체인으로 gas fee 없음
- **권한 위임**: Account delegation 메커니즘 미지원
- **계정 간 전송**: UTXO 트랜잭션 ADR에서 다룰 예정

## Alternatives Considered

### 1. Ethereum 계정 모델 그대로 사용
**장점:**
- 검증된 설계
- 도구 및 라이브러리 풍부

**단점:**
- 사용자 친화적이지 않음 (긴 해시 주소)
- 키 복구 메커니즘 없음
- 프라이빗 체인에 불필요한 복잡도

**결정:** Named Account로 사용성 개선

### 2. Account Model vs UTXO Model
**Account Model:**
- 잔고를 계정 상태에 직접 저장
- 구현 단순
- 병렬 처리 어려움

**UTXO Model (선택):**
- 잔고를 별도 UTXO 집합으로 관리
- 병렬 트랜잭션 처리 용이
- 프라이버시 향상 가능

**결정:** UTXO 모델 채택 (별도 ADR에서 상세 정의)

### 3. Multi-signature 지원 여부
**지원 시:**
- 보안 강화 (다중 승인)
- 공동 계정 관리 가능

**미지원 (선택):**
- Pending 트랜잭션 관리 복잡도 증가
- 노드 간 부분 서명 트랜잭션 전파 필요
- 프라이빗 체인 환경에서 필요성 낮음

**결정:** 현재 버전에서 미지원, 향후 필요 시 재검토

### 4. 가디언 모델
**탈중앙화 가디언:**
- 다수의 독립적 가디언
- Threshold 기반 복구 (예: 3-of-5)

**중앙화 가디언 (선택):**
- 서비스 운영자가 담당
- KYC 기반 신원 확인
- 프라이빗 체인 특성에 부합

**결정:** 중앙화 모델 채택 (프라이빗 체인의 목적에 부합)

## Consequences

### Positive
- **사용성 향상**: 이름 기반 계정으로 사용자 경험 개선
- **키 복구 가능**: 비밀키 분실 시 가디언을 통한 복구
- **유연한 키 관리**: 다중 키 등록으로 디바이스별 키 사용 가능
- **검증된 암호학**: Ethereum 방식 차용으로 안정성 확보
- **Replay attack 방지**: Nonce 기반 보안

### Negative/Trade-offs
- **중앙화된 신뢰**: 가디언에 대한 의존성
  - 완화: 프라이빗 체인의 목적상 수용 가능
- **KeyId20 충돌(Public Key Identifier)**: 이론적 가능성 존재
  - 완화: Ethereum 검증 방식, Named Account는 이름이 primary key
- **키 없는 계정**: 모든 키 제거 시 계정 접근 불가
  - 완화: 잔고는 유지되나 접근 불가능 (명시적 설계)
- **온체인 상태 증가**: 키 메타데이터 저장
  - 완화: 프라이빗 체인으로 스토리지 비용 문제 없음

## Security Considerations

### Replay Attack 방지
- 계정 상태 변경 트랜잭션에 nonce(BigNat) 필수, 정확히 +1 증가 규칙
- UTXO 소비/생성 트랜잭션은 입력 참조로 재생 방지됨(별도 ADR에서 정의)
- Envelope에 포함된 `networkId`로 체인 간 리플레이 방지, `createdAt`/`memo`는 감사 메타데이터로 서명 대상에 포함

### 키 관리
- 만료 날짜 설정 가능 (선택적)
- 가디언과 계정 소유자 모두 키 제거 가능
- 마지막 키 제거 허용 (명시적 설계 결정)
 - 검증 규칙: `expiresAt.exists(now > _)`인 키는 서명 권한 없음(자동 삭제는 수행하지 않음)

### 가디언 보안
- 오프체인 KYC 필수(사용자가 블록체인에 직접 접근하지 않는 운영 모델)
- 단일 가디언만 허용한다
- 중앙 오케스트레이션 환경을 가정하므로 타임락/철회 메커니즘은 도입하지 않음
- 가디언 교체/키 변경은 감사 가능하도록 Envelope와 이벤트 로그로 추적

### KeyId20 충돌 (Public Key Identifier)
- 20바이트 해시 사용
- Birthday attack 이론적 가능 (2^80 연산)
- Ethereum 검증 방식으로 위험 수용
- Named Account는 이름이 primary key로 영향 최소화

### 계정 삭제
- 삭제 후 UTXO 잔고 접근 불가
- 계정 모듈은 잔고를 확인하지 않음(경계 분리). 의도하지 않은 삭제는 운영/UI 정책으로 방지 필요

### 서명 대상 정의 (Canonical Signing Payload)
- 별도 도메인 태그 없이, 정합적(표준화된) 트랜잭션 인코딩 전체에 서명한다.
- 구성: `encode(modulePathOrId, txDiscriminator, networkId: BigNat, createdAt: Instant, memo: Option[Utf8], payload)`를 정확히 이 순서로 길이접두 방식으로 인코딩한다.
- 목적:
  - `networkId` 포함: 체인 간 리플레이 차단
  - `txDiscriminator`/`modulePathOrId` 포함: 모듈/트랜잭션 간 서명 재사용 방지
  - `createdAt`/`memo` 포함: 감사/추적을 위한 메타데이터를 서명 대상에 포함
- 주의: 인코딩 스펙은 고정(constant)되어야 한다. 스키마 변경 시 `txDiscriminator`에 버전을 부여하거나 상위 수준 버전 필드를 추가한다.

## Open Questions

1. Signature domain 태그 버전 관리 정책(모듈/버전별 네이밍 규칙)
2. Memo 최대 길이/인코딩 제약(온체인 스토리지 영향 범위)
3. 가디언 집합 크기 상한(운영상 한도 설정 필요 여부)
4. Unnamed → Named 전환을 위한 오프체인 마이그레이션 표준 절차 문서화

## References

- Ethereum Yellow Paper: Public key to address derivation
- Bitcoin UTXO model
- EIP-1271: Standard Signature Validation Method for Contracts
