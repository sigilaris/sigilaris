# ADR 0011: 블록체인 계정 그룹 관리 (Blockchain Account Group Management)

## Status
제안됨 (Proposed)

## Context

블록체인 애플리케이션에서 특정 권한(예: 토큰 발행)을 가진 계정들을 논리적으로 묶어 관리할 필요가 있다. 개별 계정이 아닌 그룹 단위로 권한을 부여하고 관리하면 다음과 같은 이점이 있다:

- 권한 관리의 단순화: 여러 계정에 동일한 권한을 부여할 때 그룹을 통해 일괄 관리
- 유연한 멤버십 관리: 그룹 멤버 추가/제거를 통한 동적 권한 조정
- 명확한 책임 구조: 코디네이터를 통한 그룹 관리 권한의 명확화

## Decision

### 그룹 관리 모델

계정 그룹은 다음과 같은 특성을 가진다:

- **코디네이터(Coordinator)**: 각 그룹은 하나의 코디네이터 계정을 가지며, 코디네이터는 그룹 관리 권한을 가진다
  - 그룹 멤버 추가/삭제
  - 그룹 해산
  - 코디네이터 변경
- **멤버십**: 코디네이터는 자동으로 그룹 멤버가 되지 않으며, 필요시 명시적으로 추가해야 한다
- **식별자**: 그룹은 UTF-8 문자열 기반의 GroupId로 식별된다. 운영 용도로 사용되므로 별도의 문자/길이 제약이나 정규화 정책을 두지 않는다.
- **그룹 이름(name)**: 생성 시 설정되며 이후 불변이다(이름 변경 트랜잭션은 제공하지 않음).
- **재생 방지(nonce)**: 그룹 상태에 `nonce: BigNat`를 두고, 유효한 그룹 관리 트랜잭션이 적용될 때마다 정확히 +1 증가한다.

### 트랜잭션 타입

계정 그룹 관리를 위한 트랜잭션 타입들:

#### 공통 필드(Envelope)
ADR-0010과 동일한 Envelope를 포함한다:
- `networkId: BigNat` — 네트워크 식별자
- `createdAt: Instant` — 트랜잭션 생성 시각
- `memo: Option[Utf8]` — (선택) 운영/감사용 메모

서명 검증 및 해시는 Envelope를 포함한 페이로드 위에서 수행한다. 그룹 관리 트랜잭션의 서명 주체는 원칙적으로 “코디네이터”이며, 아래 각 트랜잭션에 별도 기재가 없으면 코디네이터 서명을 요구한다. 재생 방지를 위해 CreateGroup을 제외한 모든 그룹 관리 트랜잭션은 `groupNonce: BigNat`를 포함하며, 적용 시 그룹 상태의 `nonce`와 일치해야 한다.

#### CreateGroup
새 그룹을 생성한다.

파라미터
- `groupId: GroupId` — UTF-8 문자열(별도 제약 없음)
- `name: Utf8` — 그룹 이름(생성 후 불변)
- `coordinator: Account` — 초기 코디네이터 계정(ADR-0010의 Account)

서명
- `coordinator` 계정

사전조건
- 동일 `groupId`의 그룹이 존재하지 않음

사후조건
- `group(groupId) = GroupData(name, coordinator, nonce = 0, createdAt = now, ...)`
- 이벤트 `GroupCreated(groupId, coordinator, name)` 방출

#### DisbandGroup
그룹을 해산한다.

파라미터
- `groupId: GroupId`
- `groupNonce: BigNat`

서명
- 코디네이터

사전조건
- 그룹 존재, `groupNonce == group(groupId).nonce`

사후조건
- `group(groupId)` 및 모든 `groupAccount(groupId, *)` 상태 제거(완전 정리)
- 이후 동일 `groupId`로 `CreateGroup` 재생성 허용(새 그룹의 `nonce`는 0부터 시작)
- 이벤트 `GroupDisbanded(groupId)` 방출

#### AddAccounts
그룹에 계정들을 추가한다.

파라미터
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

서명
- 코디네이터

사전조건
- 그룹 존재, `groupNonce == group(groupId).nonce`
- `accounts`는 비어있지 않아야 함(비었으면 검증 단계에서 거부)

사후조건
- 각 `(groupId, account)`가 존재하도록 만든다. 이미 멤버인 계정은 상태 변화 없이 통과(idempotent no-op)
- `group.nonce` 증가
- 이벤트 `GroupMembersAdded(groupId, added = accountsActuallyAdded)` 방출

#### RemoveAccounts
그룹에서 계정들을 제거한다.

파라미터
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

서명
- 코디네이터

사전조건
- 그룹 존재, `groupNonce == group(groupId).nonce`
- `accounts`는 비어있지 않아야 함

사후조건
- 각 `(groupId, account)`를 제거한다. 멤버가 아닌 계정은 상태 변화 없이 통과(idempotent no-op)
- `group.nonce` 증가
- 이벤트 `GroupMembersRemoved(groupId, removed = accountsActuallyRemoved)` 방출

#### ReplaceCoordinator
그룹의 코디네이터를 변경한다.

파라미터
- `groupId: GroupId`
- `newCoordinator: Account`
- `groupNonce: BigNat`

서명
- 기존 코디네이터

사전조건
- 그룹 존재, `groupNonce == group(groupId).nonce`

사후조건
- `group(groupId).coordinator = newCoordinator`
- `oldCoordinator == newCoordinator`인 경우 상태는 불변(idempotent no-op)이지만 트랜잭션은 유효하며 `group.nonce`는 증가
- 이벤트 `GroupCoordinatorReplaced(groupId, old, new)` 방출

### 상태 모델

그룹 상태는 다음 두 가지 매핑으로 관리된다:

```scala
// 그룹 메타데이터
group: GroupId -> GroupData

// 그룹 멤버십 (존재 여부만 표시)
groupAccount: (GroupId, Account) -> Unit
```

`GroupData`는 다음 정보를 포함한다:
- `name: Utf8` — 그룹 이름(불변)
- `coordinator: Account` — 코디네이터 계정
- `nonce: BigNat` — 그룹 관리 트랜잭션 재생 방지용 카운터
- `createdAt: Instant` 등 메타데이터

역인덱스(계정 → 그룹)는 유지하지 않는다. 운영·검증 목적의 접근은 그룹 접두로 `groupAccount`를 스캔하여 처리한다.

## Consequences

### 긍정적 영향

- **단순하고 명확한 권한 구조**: 코디네이터 기반의 중앙집중형 관리로 권한 체계가 명확함
- **효율적인 권한 부여**: 그룹 단위 권한 관리로 복잡한 권한 시스템 구축 가능
- **유연한 멤버십**: 동적으로 그룹 멤버를 추가/제거할 수 있어 권한 변경이 용이함
- **독립적인 코디네이터**: 코디네이터가 자동으로 멤버가 되지 않아 관리 역할과 실행 역할을 분리 가능

### 부정적 영향

- **중앙집중화**: 코디네이터에 권한이 집중되어 단일 실패 지점(SPOF)이 될 수 있음
  - 완화: 코디네이터 변경 기능으로 대응 가능
- **거버넌스 제한**: 다중 서명이나 투표 기반 의사결정 등 복잡한 거버넌스는 지원하지 않음
  - 현재 요구사항에서는 단순한 모델로 충분하다고 판단

### 구현 고려사항

- 그룹 생성 시 GroupId 중복 검증 필요
- 코디네이터 권한 검증을 모든 관리 트랜잭션에서 수행
- 그룹 해산 시 관련된 모든 상태(그룹 데이터 및 멤버십) 정리
- 존재하지 않는 그룹에 대한 작업 시도 시 적절한 오류 처리
- 입력 정규화 정책: GroupId/이름에 별도 제약이나 정규화 없음(입력값을 그대로 사용)
- 모든 유효한 그룹 관리 트랜잭션은 상태 변화가 없더라도 `group.nonce`를 증가(idempotent no-op은 상태 보존 + nonce 증가)
- 대량 멤버 추가/삭제는 배치 크기 제한 고려(가스/자원 한도)
- 이벤트 방출 스키마 정의 및 로그 수준/보존 정책 수립
- 모듈 의존성: 서명/계정 검증은 ADR-0010의 Accounts 모델을 사용

## Scope

- 그룹 생성/해산/멤버 추가·제거/코디네이터 교체
- 그룹 이름 불변(이름 변경은 범위 밖)
- 그룹 단위 nonce를 통한 재생 방지
- 이벤트 방출 및 감사 용도 로그 정의(간단 버전)

## Non-Goals

- 다중 서명/투표 기반 거버넌스(향후 별도 검토)
- 멤버십 역인덱스 유지(스캔으로 대체)
- 이름/GroupId에 대한 추가 포맷 제약 또는 정규화
- 스마트 컨트랙트 수준의 고급 권한 모델


## References
- [ADR 0009: 블록체인 애플리케이션 아키텍처](0009-blockchain-application-architecture.md)
- [ADR 0010: 블록체인 계정 모델 및 키 관리](0010-blockchain-account-model-and-key-management.md)
