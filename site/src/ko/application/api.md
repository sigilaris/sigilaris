# Application Module API 참조

[← 개요](README.md) | [English →](../../en/application/api.md)

---

## 개요

이 페이지는 애플리케이션 모듈 시스템의 상세 API 참조를 제공합니다. 완전한 예제와 사용 패턴은 [개요](README.md)를 참조하세요.

## 핵심 추상화

### Blueprint (경로 독립)

**ModuleBlueprint**

배포 경로 없이 정의된 단일 모듈 명세입니다.

```scala
class ModuleBlueprint[
  F[_],
  MName <: String,      // 모듈 이름 (타입 레벨)
  Owns <: Tuple,        // 이 모듈이 소유하는 테이블
  Needs <: Tuple,       // 이 모듈이 요구하는 테이블
  Txs <: Tuple          // 트랜잭션 타입
](
  owns: Owns,                           // 런타임 Entry 튜플
  reducer0: StateReducer0[F, Owns, Needs],
  txs: TxRegistry[Txs],
  provider: TablesProvider[F, Needs]    // 주입된 의존성
)
```

**예시: AccountsBP (ADR-0010)**

Accounts 모듈은 Named Account(이름 기반 계정) 관리를 제공합니다. 외부 의존성이 없는 독립적인 모듈입니다.

```scala
// 스키마 정의
type AccountsOwns = (
  Entry["accountInfo", Utf8, AccountInfo],        // 이름 → 계정 메타데이터
  Entry["nameKey", (Utf8, KeyId20), KeyInfo],     // (이름, keyId) → 키 정보
)

type AccountsNeeds = EmptyTuple  // 외부 의존성 없음

// 트랜잭션 예시
trait CreateNamedAccount extends Tx:
  type Reads = EmptyTuple
  type Writes = AccountsOwns
  def nameValue: Utf8
  def initialKeyId: KeyId20
  def guardian: Option[Account]
```

**핵심 특성:**
- 경로 독립: 어디에든 마운트 가능
- 의존성 없음: `Needs = EmptyTuple`
- 타입 안전: 트랜잭션이 테이블 요구사항을 정적으로 선언

**ComposedBlueprint**

라우팅과 함께 결합된 다중 모듈입니다.

**예시: 의존적 모듈 (GroupBP, ADR-0011)**

Group 모듈은 코디네이터 검증을 위해 Accounts 모듈에 의존합니다.

```scala
// Group이 소유하는 테이블
type GroupOwns = (
  Entry["groupData", GroupId, GroupData],         // groupId → 메타데이터
  Entry["groupMember", (GroupId, Account), Unit], // (groupId, account) → 멤버십
)

// Group이 필요로 하는 Accounts 테이블
type GroupNeeds = (
  Entry["accountInfo", Utf8, AccountInfo],  // AccountsBP로부터
)

// 트랜잭션
trait CreateGroup extends Tx:
  type Reads = GroupNeeds      // 코디네이터 존재 확인
  type Writes = GroupOwns       // 그룹 생성
  def groupId: GroupId
  def coordinator: Account      // accountInfo에 존재해야 함
```

**핵심 통찰**: GroupBP는 타입 레벨에서 `Needs = GroupNeeds`를 선언하여, 호출자가 TablesProvider를 통해 AccountsBP의 테이블을 주입하도록 요구합니다.

### StateModule (경로 바인딩)

인스턴스화된 테이블을 가진 런타임 모듈:
- 계산된 접두어를 가진 Tables\[F, Owns\] 포함
- 특정 배포 경로에 바인딩됨
- 의존성을 위한 TablesProvider 추출 가능

### 상태 관리

**StoreF\[F\]**: 효과적인 상태 모나드
- 오류를 위한 EitherT와 상태를 위한 StateT 결합
- `StoreF[F][(Result, List[Event])]` 반환

**StoreState**: MerkleTrie 상태와 AccessLog 결합
- 충돌 감지를 위한 읽기/쓰기 추적
- 병렬 트랜잭션 실행 분석 가능

### 트랜잭션 모델

**Tx**: 기본 트랜잭션 트레이트
- Reads/Writes 스키마 요구사항 선언
- Result 및 Event 타입 정의
- 컴파일 타임에 타입 안전

**ModuleRoutedTx**: 라우팅을 가진 트랜잭션
- 모듈 상대 ModuleId 포함
- 조합된 블루프린트에 필요
- 적절한 서브 리듀서로 라우팅

### 의존성 시스템

**TablesProvider**: 모듈 간 테이블 접근을 위한 의존성 주입

```scala
trait TablesProvider[F[_], Schema <: Tuple]:
  def tables: Tables[F, Schema]
  def narrow[Subset <: Tuple](
    using TablesProjection[F, Subset, Schema]
  ): TablesProvider[F, Subset]

object TablesProvider:
  def empty[F[_]]: TablesProvider[F, EmptyTuple]
  
  // 마운트된 모듈로부터 생성
  def fromModule[F[_], Path <: Tuple, Owns <: Tuple, ...](
  )(module: StateModule[F, Path, Owns, ...]
  ): TablesProvider[F, Owns]
```

**배포 패턴: 의존성 연결**

```scala
// 1단계: AccountsBP 마운트 (의존성 없음)
val accountsModule = accountsBP.mount[("app", "accounts")]

// 2단계: accounts로부터 provider 생성
val accountsProvider: TablesProvider[IO, AccountsOwns] = 
  TablesProvider.fromModule(accountsModule)

// 3단계: GroupBP에 연결
val groupBPWired = new ModuleBlueprint[
  IO, "group", GroupOwns, GroupNeeds, GroupTxs
](
  owns = groupSchema,
  reducer0 = new GroupReducer[IO],
  txs = groupTxRegistry,
  provider = accountsProvider  // accounts 테이블 주입
)

// 4단계: GroupBP 마운트
val groupModule = groupBPWired.mount[("app", "group")]
```

**핵심 특성:**
- 타입 안전: 스키마 불일치는 컴파일 타임에 탐지
- 재사용 가능: 하나의 마운트된 모듈이 여러 의존자에게 제공 가능
- 조합 가능: 각 모듈이 필요로 하는 것만 정확하게 축소

**Requires**: 컴파일 타임 증거
- 트랜잭션 요구사항 ⊆ 스키마 검증
- 누락된 테이블 접근 방지
- 런타임 오버헤드 제로

---

[← 개요](README.md) | [English →](../../en/application/api.md)
