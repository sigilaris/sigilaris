# Group 모듈 (ADR-0011)

[← 개요](README.md) | [English →](../../en/application/group.md)

---

## 개요

Group 모듈은 블록체인 애플리케이션을 위한 계정 그룹 관리를 구현합니다. ADR-0011을 기반으로, 멤버십 추적과 함께 코디네이터 기반 그룹 관리를 제공하며 코디네이터 검증을 위해 Accounts 모듈을 요구합니다.

**주요 기능:**
- **코디네이터 모델**: 단일 코디네이터가 그룹 멤버십 관리
- **멤버십 추적**: memberCount 무결성을 가진 온체인 멤버십
- **계정 의존성**: Accounts 모듈에서 코디네이터 존재 검증
- **해산 제약**: 그룹은 해산 전에 비어있어야 함

## 그룹 모델

### 그룹 구조

각 그룹은 다음으로 구성됩니다:
- **GroupId**: UTF-8 문자열 식별자 (운영 유연성을 위한 형식 제한 없음)
- **Name**: 생성 시 설정되는 불변 그룹 이름
- **Coordinator**: 관리 권한을 가진 단일 Account
- **Members**: 그룹에 속한 계정 집합
- **Nonce**: 재생 공격 방지를 위한 순차 카운터
- **MemberCount**: 해산 검증을 위한 현재 멤버십 크기 추적

**핵심 설계**: 코디네이터는 자동으로 멤버가 되지 않음 - 필요시 명시적으로 추가해야 합니다.

## 상태 스키마

### 그룹 메타데이터
```scala
Entry["groupData", GroupId, GroupData]
```

**GroupData** 필드:
- `name: Utf8` - 그룹 이름 (생성 후 불변)
- `coordinator: Account` - 코디네이터 계정
- `nonce: BigNat` - 트랜잭션 재생 방지 카운터
- `memberCount: BigNat` - 현재 멤버 수 (해산 검증용)
- `createdAt: Instant` - 그룹 생성 타임스탬프

### 그룹 멤버십
```scala
Entry["groupMember", (GroupId, Account), Unit]
```

존재 여부만으로 멤버십을 나타냅니다 (키 존재 = 멤버).

**역인덱스 없음**: Account → Groups는 유지하지 않습니다. 운영 쿼리는 그룹 접두어로 스캔합니다.

## 의존성

### 필요한 테이블 (Accounts 모듈로부터)

```scala
type GroupNeeds = Entry["accountInfo", Utf8, AccountInfo] *: EmptyTuple
```

Group 모듈은 그룹 생성 시 코디네이터 존재를 검증하기 위해 Accounts에 의존합니다.

## 트랜잭션

모든 그룹 트랜잭션은 다음을 포함하는 **Envelope**를 포함합니다:
- `networkId: BigNat` - 체인/네트워크 식별자
- `createdAt: Instant` - 트랜잭션 생성 타임스탬프
- `memo: Option[Utf8]` - 선택적 운영 메모

모든 그룹 관리 트랜잭션(CreateGroup 제외)은 다음을 요구합니다:
- `groupNonce: BigNat` - 재생 방지를 위해 현재 그룹 nonce와 일치해야 함

### CreateGroup

지정된 코디네이터로 새 그룹을 생성합니다.

**파라미터:**
- `groupId: GroupId` - UTF-8 문자열 식별자
- `name: Utf8` - 불변 그룹 이름
- `coordinator: Account` - 초기 코디네이터

**서명:** 코디네이터 계정

**사전조건:**
- `groupId`를 가진 그룹이 존재하지 않아야 함
- 코디네이터가 accountInfo에 존재해야 함 (의존성을 통해 검증)

**사후조건:**
- `group(groupId) = GroupData(name, coordinator, nonce=0, memberCount=0, ...)` 생성
- `GroupCreated(groupId, coordinator, name)` 이벤트 방출

### DisbandGroup

기존 그룹을 해산합니다.

**파라미터:**
- `groupId: GroupId`
- `groupNonce: BigNat`

**서명:** 코디네이터

**사전조건:**
- 그룹 존재
- `groupNonce`가 `group(groupId).nonce`와 일치
- **그룹이 비어있어야 함**: `memberCount == 0`
  - 모든 멤버를 먼저 `RemoveAccounts`로 제거해야 함

**사후조건:**
- `group(groupId)` 상태 제거
- 모든 `groupAccount(groupId, *)` 엔트리는 이미 제거된 상태 (memberCount 제약으로 보장)
- 동일 `groupId`를 새 그룹에 재사용 가능 (새 그룹의 nonce는 0부터 시작)
- `GroupDisbanded(groupId)` 이벤트 방출

**설계 근거:**
- MerkleTrie API는 범위 삭제/반복 기능 부족 (ADR-0009 Phase 8에서 계획됨)
- 비어있지 않은 그룹 해산 시 고아 `groupAccount` 엔트리 남김
- `groupId` 재사용 시 이전 멤버 복원 (보안 문제)
- **해결책**: 해산 전 `memberCount == 0` 요구

### AddAccounts

그룹 멤버십에 계정을 추가합니다.

**파라미터:**
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

**서명:** 코디네이터

**사전조건:**
- 그룹 존재
- `groupNonce`가 현재 nonce와 일치
- `accounts`가 비어있지 않아야 함 (검증 시 거부)

**사후조건:**
- 각 계정에 대해 `(groupId, account)` 엔트리 생성
- 이미 멤버인 계정: 멱등적 no-op (상태 변화 없음)
- `group.nonce` 증가
- 실제로 추가된 계정 수만큼 `memberCount` 증가
- `GroupMembersAdded(groupId, added=actuallyAdded)` 이벤트 방출

### RemoveAccounts

그룹 멤버십에서 계정을 제거합니다.

**파라미터:**
- `groupId: GroupId`
- `accounts: Set[Account]`
- `groupNonce: BigNat`

**서명:** 코디네이터

**사전조건:**
- 그룹 존재
- `groupNonce`가 현재 nonce와 일치
- `accounts`가 비어있지 않아야 함

**사후조건:**
- `(groupId, account)` 엔트리 제거
- 비멤버 계정: 멱등적 no-op
- `group.nonce` 증가
- 실제로 제거된 계정 수만큼 `memberCount` 감소
- `GroupMembersRemoved(groupId, removed=actuallyRemoved)` 이벤트 방출

### ReplaceCoordinator

그룹 코디네이터를 변경합니다.

**파라미터:**
- `groupId: GroupId`
- `newCoordinator: Account`
- `groupNonce: BigNat`

**서명:** 현재 코디네이터

**사전조건:**
- 그룹 존재
- `groupNonce`가 현재 nonce와 일치

**사후조건:**
- `group(groupId).coordinator = newCoordinator` 업데이트
- `oldCoordinator == newCoordinator`인 경우: 멱등적 no-op (하지만 nonce는 여전히 증가)
- `group.nonce` 증가
- `GroupCoordinatorReplaced(groupId, old, new)` 이벤트 방출

## Blueprint 구조

```scala mdoc:compile-only
import cats.Monad
import cats.effect.IO
import java.time.Instant
import scala.Tuple.++
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.{Entry, StoreF, Tables}
import org.sigilaris.core.application.support.compiletime.Requires
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.codec.byte.ByteCodec
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

// 도메인 타입 (ADR-0010/0011)
type GroupId = Utf8
type Account = Utf8

case class AccountInfo(guardian: Option[Account], nonce: BigInt :| Positive0)
case class GroupData(
  name: Utf8,
  coordinator: Account,
  nonce: BigInt :| Positive0,
  memberCount: BigInt :| Positive0,
  createdAt: Instant
)

// 참고: 프로덕션에서는 도메인 타입에 대한 ByteCodec 인스턴스를 제공해야 합니다
// given ByteCodec[AccountInfo] = ...
// given ByteCodec[GroupData] = ...

// Group이 소유하는 테이블
type GroupOwns = (
  Entry["groupData", GroupId, GroupData],        // groupId → GroupData
  Entry["groupMember", (GroupId, Account), Unit], // (groupId, account) → 멤버십
)

// Group이 필요로 하는 Accounts 테이블
type GroupNeeds = Entry["accountInfo", Utf8, AccountInfo] *: EmptyTuple

// 트랜잭션
trait CreateGroup extends Tx:
  type Reads = GroupNeeds   // 코디네이터 존재 검증
  type Writes = GroupOwns    // 그룹 생성
  def groupId: GroupId
  def nameValue: Utf8
  def coordinator: Account

trait AddAccounts extends Tx:
  type Reads = GroupOwns ++ GroupNeeds  // 그룹 읽기 + 계정 검증
  type Writes = GroupOwns                // 멤버 업데이트
  def groupId: GroupId
  def accounts: Set[Account]
  def groupNonce: BigInt :| Positive0

// 의존성 주입을 가진 리듀서
class GroupReducer[F[_]: Monad] 
  extends StateReducer0[F, GroupOwns, GroupNeeds]:
  
  def apply[T <: Tx](signedTx: Signed[T])(
    using
      Requires[signedTx.value.Reads, GroupOwns ++ GroupNeeds],
      Requires[signedTx.value.Writes, GroupOwns ++ GroupNeeds],
      Tables[F, GroupOwns],
      TablesProvider[F, GroupNeeds],  // 주입된 accountInfo
  ): StoreF[F][(signedTx.value.Result, List[signedTx.value.Event])] = ???

// 블루프린트 (AccountsBP provider 필요)
// val groupBP = new ModuleBlueprint[
//   IO, "group", GroupOwns, GroupNeeds, GroupTxs
// ](
//   owns = groupSchema,
//   reducer0 = new GroupReducer[IO],
//   txs = groupTxRegistry,
//   provider = accountsProvider  // 마운트된 AccountsBP로부터 연결되어야 함
// )
```

**핵심 속성:**
- `Needs = GroupNeeds`: Accounts 모듈에 의존
- 리듀서는 `ownsTables`와 `provider.tables` 모두에 접근
- 경로 독립: 어디에든 배포 가능

## 배포 패턴

### 공유 Accounts
```scala
// Accounts를 먼저 마운트
val accountsModule = accountsBP.mount[("app", "accounts")]

// accounts로부터 provider 생성
val accountsProvider = TablesProvider.fromModule(accountsModule)

// GroupBP에 연결
val groupBPWired = new ModuleBlueprint[...](
  ...,
  provider = accountsProvider
)

// Group 마운트
val groupModule = groupBPWired.mount[("app", "group")]
```

### 샌드박스 Accounts
```scala
// 각 모듈이 격리된 accounts를 얻음
val groupAccounts = accountsBP.mount[("app", "group", "accounts")]
val groupProvider = TablesProvider.fromModule(groupAccounts)

val groupModule = groupBP
  .withProvider(groupProvider)
  .mount[("app", "group")]
```

## MemberCount 무결성

`memberCount` 필드는 해산 안전성을 보장합니다:

**불변조건**: `memberCount == 실제 groupMember 엔트리 수`

**강제:**
- `CreateGroup`: `memberCount = 0` 설정
- `AddAccounts`: 실제로 추가된 수만큼 증가 (기존 멤버에 대해 멱등적)
- `RemoveAccounts`: 실제로 제거된 수만큼 감소 (비멤버에 대해 멱등적)
- `DisbandGroup`: `memberCount == 0`일 때만 허용

**왜?** MerkleTrie는 범위 삭제가 없습니다. memberCount 제약 없이:
1. 비어있지 않은 그룹 해산 → 고아 `groupMember` 엔트리
2. 동일 groupId로 재생성 → 이전 멤버 복원 (보안 버그)

**해결책**: 해산 전 빈 그룹 강제는 완전한 정리를 보장합니다.

## 사용 사례

### 권한 그룹
```scala
// 토큰 발행을 위한 발행자 그룹 정의
CreateGroup(groupId = "token-issuers", coordinator = serviceAccount)
AddAccounts(groupId = "token-issuers", accounts = Set(alice, bob))

// 계정이 발행 가능한지 확인
if (isMember("token-issuers", alice)) {
  // 발행 작업 허용
}
```

### 역할 기반 접근
```scala
// 관리자 그룹
CreateGroup(groupId = "admins", coordinator = rootAccount)
AddAccounts(groupId = "admins", accounts = Set(admin1, admin2))

// 운영자 그룹
CreateGroup(groupId = "operators", coordinator = admin1)
AddAccounts(groupId = "operators", accounts = Set(op1, op2, op3))
```

### 동적 멤버십
```scala
// 새 멤버 추가
AddAccounts(groupId = "reviewers", accounts = Set(newReviewer))

// 퇴사한 멤버 제거
RemoveAccounts(groupId = "reviewers", accounts = Set(formerReviewer))

// 코디네이터 역할 이전
ReplaceCoordinator(
  groupId = "reviewers",
  newCoordinator = seniorReviewer
)
```

## 설계 결정

**단일 코디네이터:**
- 더 단순한 운영 모델
- 명확한 책임 구조
- 프라이빗 블록체인 환경에 충분
- 미래: 다중 서명/투표를 위에 계층화 가능

**코디네이터 자동 멤버 아님:**
- 관리 역할과 멤버십 분리
- 유연성: 코디네이터는 관리만 할 수 있고 참여하지 않을 수 있음
- 명시적: 필요시 코디네이터를 멤버로 추가해야 함

**역인덱스 없음:**
- 운영 쿼리는 그룹 접두어로 스캔
- 쓰기 오버헤드 감소 (이중 유지관리 없음)
- 예상 쿼리 패턴에 충분

**빈 그룹 해산:**
- 범위 삭제 API 없이도 완전한 정리 보장
- 고아 엔트리로부터의 보안 버그 방지
- 트레이드오프: DisbandGroup 전에 RemoveAccounts 필요

## 참고 자료

- [ADR-0011: 블록체인 계정 그룹 관리](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0011-blockchain-account-group-management.md)
- [ADR-0010: 블록체인 계정 모델](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)
- [ADR-0009: 블록체인 애플리케이션 아키텍처](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [Accounts 모듈](accounts.md)
- [Application 개요](README.md)

---

© 2025 Sigilaris. All rights reserved.
