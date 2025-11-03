# ADR-0009: Blockchain Application Architecture

## Status
Draft

## Context
- 블록체인 상태는 머클 트라이의 루트로 요약되며, 트랜잭션 적용에 따라 다음 상태로 전이된다. 전이는 결과(Result)와 이벤트(Event) 로그를 생산한다.
- 상태 전이는 효과 스택을 가진 상태 모나드(StateT)로 자연스럽게 모델링된다. 구현은 이미 `MerkleTrie.scala`의 `StateT[EitherT[F, String, *], MerkleTrieState, *]` 패턴을 사용한다.
- 키/값 저장소는 머클 트라이의 바이너리 K/V 위에 얹히며, 바이트 코덱과 순서 보존 인코딩 요건이 중요하다.
- 모듈 경로(Path, 문자열 세그먼트 튜플)와 테이블 이름으로 접두어(prefix)를 구성하며, 테이블 간 충돌을 방지하기 위한 엄격한 규칙이 필요하다.

## Decision
- 용어와 경계
  - 테이블: `StateTable` — K/V 스키마와 코덱만 정의(경로나 NS는 없음).
  - 모듈 설계도(경로 없음): `ModuleBlueprint` — 자체 테이블 집합(`Owns`), 외부에서 제공받아야 하는 테이블 집합(`Needs`), 트랜잭션 집합을 소유. 어디에 배치될지 모른다는 가정으로 설계하며 `Needs`는 타입으로만 선언된다.
  - 장착된 모듈(경로 부여됨): `StateModule` — 설계도를 특정 Path에 장착하여 접두어를 합성하고, 테이블 프로바이더를 주입받아 동작하는 실체.
  - 애플리케이션 상태: `DAppState` — 특정 Path, 특정 스키마의 머클 상태 래퍼(타입 레벨 증거로만 생성/소비).
  - 전이기:
    - `StateReducer0`: Path 비의존 상태에서 `Owns ++ Needs` 스키마로 트랜잭션을 해석(블루프린트 단계). `Needs`가 요구하는 테이블은 `TablesProvider`를 통해 공급된다.
    - `StateReducer`: mount 이후 Path와 테이블 접두어가 고정된 상태에서 트랜잭션을 실행(모듈 단계). `ModuleId` 기반 라우팅은 Decision의 `Reducer Routing` 항목을 따른다.
  - 테이블 프로바이더: `TablesProvider` — 특정 `Needs`(Entry 튜플) 집합을 만족하는 테이블 구현을 제공. Phase 5.5에서 도입된 의존성 모델이며, `Needs = EmptyTuple`일 때는 `TablesProvider.empty`가 사용된다.
  - 모듈 상대 ID: `ModuleId`는 항상 모듈 상대 경로(`MName *: SubPath`)만 담으며, mount 경로는 트랜잭션에 추가하지 않는다. 라우팅 전략은 `Reducer Routing Strategy` 절에서 정의한다.

- 접두어 규칙(필수, 바이트 단위)
  - 테이블 접두어는 바이트(byte) 단위로 prefix-free 이어야 한다.
  - 동일 금지: 두 테이블의 접두어가 같으면 안 된다.
  - 접두어-관계 금지: 한쪽 접두어가 다른 쪽의 접두어가 되는 경우도 금지한다.
  - 실제 접두어는 `encodePath(Path) ++ encodeSegment(tableName)`를 길이 접두(length‑prefix) 또는 명시적 구분자(sentinel)를 포함해 인코딩한다. 길이 접두를 권장한다.
  - 규칙 검증은 모듈 차원(의존/집합 결합 포함)에서 수행한다.

- 경로(Path) 구성
  - 테이블은 순수 스키마(이름, K, V, 코덱)만 갖는다.
  - 장착된 모듈(StateModule)은 주어진 Path와 각 테이블 이름을 조합해 트라이에 넣을 키 접두어를 생성한다. 블루프린트는 Path를 알지 못한다.

- 타입 레벨 안전성(제로 코스트)
  - 키 안전: `opaque type KeyOf[Brand, A] = A`로 인스턴스 브랜드 기반 키 안전성 제공(런타임 오버헤드 0).
  - 상태 증거: `opaque type DAppState[Path <: Tuple, Schema <: Tuple] = MerkleTrieState`와 `DAppStateTag` 증거로, 외부 임의 타입이 DApp 상태로 오인되는 것을 방지.

- 모듈 조합과 확장(경로는 소유자가 결정)
  - 모듈은 경로를 모르고 작성된다(ModuleBlueprint). 상위 조립자(소유자)가 장착 시 Path를 부여한다.
  - 의존 확장: Blueprint 간 의존을 선언하고, 조립 시 단일 Blueprint를 여러 위치에 장착하거나 공유 장착 가능(Accounts를 Group/Token에서 각각 참조 등).
  - 집합 결합: 관계없는 Blueprint들을 하나의 상위 Blueprint로 결합한 뒤 최종적으로 한 번에 장착.
  - DApp 경계: 최상위 장착 결과를 DApp으로 정의(= 최상위 StateModule 집합).

- 충돌 탐지
  - 정적: `Tx`는 `Reads`/`Writes`에 필요한 테이블 집합을 타입으로 선언하고, `StateReducer`는 해당 집합 ⊆ 모듈 스키마를 증거로 요구한다.
  - 동적: 실행 중 `AccessLog`로 테이블별 바이트‑키(접두어 합성 후)의 읽기/쓰기 집합을 기록한다. 충돌은 `W∩W` 또는 `R∩W`로 판정한다. prefix‑free 보장은 상이한 테이블 간 거짓 양성을 막는다.

## Sketch (Scala 3)
```scala
import cats.data.{EitherT, StateT}
import fs2.Stream
import scodec.bits.ByteVector
import org.sigilaris.core.codec.byte.ByteEncoder
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Positive0

type Eff[F[_]]    = EitherT[F, SigilarisFailure, *]
type StoreF[F[_]] = StateT[Eff[F], StoreState, *]  // Phase 8: wraps MerkleTrieState + AccessLog

final class Entry[Name <: String, K, V](using ByteCodec[K], ByteCodec[V])

// 제로-코스트 키 브랜드. Brand에는 보통 테이블 인스턴스 타입(self.type)을 사용.
opaque type KeyOf[Brand, A] = A
object KeyOf:
  inline def apply[Brand, A](a: A): KeyOf[Brand, A] = a

// 테이블: 네임스페이스 없음. 이름/키/값을 내부 타입으로 노출하고, 키는 인스턴스-브랜드로 보호.
trait StateTable[F[_]] { self =>
  type Name <: String
  type K; type V
  given ByteCodec[K]; given ByteCodec[V]

  def name: String                                      // 접두어 계산용 문자열(모듈에서 사용)
  type Key = KeyOf[self.type, K]                        // 인스턴스 브랜드 키
  def brand(k: K): Key = k                              // 키 브랜딩 헬퍼

  def get(k: Key): StoreF[F, Option[V]]
  def put(k: Key, v: V): StoreF[F, Unit]
  def remove(k: Key): StoreF[F, Boolean]
}

// 애플리케이션 상태: 타입 레벨로만 증거를 제공
opaque type DAppState[Path <: Tuple, Schema <: Tuple] = MerkleTrieState
sealed trait DAppStateTag[S]
given [Path <: Tuple, Schema <: Tuple]: DAppStateTag[DAppState[Path, Schema]] = new {}

// 트랜잭션: 요구 테이블 집합을 타입으로 모델
trait Tx:
  type Reads  <: Tuple
  type Writes <: Tuple
  type Result
  type Event

// Phase 8: 상태 + 접근 로그 통합
final case class StoreState(
  trieState: MerkleTrieState,
  accessLog: AccessLog
)

// 접근 로그: 테이블 접두어(ByteVector)별 키 집합 추적
// - prefix-free 보장으로 테이블 간 거짓 양성 방지
// - 읽기/쓰기는 unique keys로 계산 (operations 아님)
final case class AccessLog(
  reads:  Map[ByteVector, Set[ByteVector]],  // tablePrefix → set of full keys
  writes: Map[ByteVector, Set[ByteVector]]
):
  def recordRead(tablePrefix: ByteVector, key: ByteVector): AccessLog
  def recordWrite(tablePrefix: ByteVector, key: ByteVector): AccessLog
  def conflictsWith(other: AccessLog): Boolean  // W∩W or R∩W
  def readCount: Int   // sum of unique keys read across all tables
  def writeCount: Int  // sum of unique keys written across all tables
  def exceedsLimits(maxReads: Int, maxWrites: Int): Boolean  // helper for enforcement

trait StateReducer[F[_], Path <: Tuple, Schema <: Tuple]:
  def apply[T <: Tx](tx: T)(using Requires[T#Reads, Schema], Requires[T#Writes, Schema])
    : StoreF[F, (T#Result, List[T#Event])]

// 모듈: 테이블/리듀서/트랜잭션/의존을 소유하고 접두어 규칙을 보장
trait UniqueNames[Schema <: Tuple]
trait PrefixFreePath[Path <: Tuple, Schema <: Tuple] // encodePath(Path) ++ Name 기반 byte prefix-free 증거

final class StateModule[
  F[_],
  Path <: Tuple,
  Owns <: Tuple,
  Needs <: Tuple,
  Txs <: Tuple,
](
  val ownsTables: Tables[F, Owns],                                     // Path에 바인딩된 자체 테이블
  val tablesProvider: TablesProvider[F, Needs],                        // 외부 테이블 공급자
  val reducer: StateReducer[F, Path, Owns ++ Needs],                   // Combined 스키마에 대한 리듀서
  val txs: TxRegistry[Txs],
)(using
  UniqueNames[Owns],
  PrefixFreePath[Path, Owns],
)

type DApp[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple] =
  StateModule[F, Path, Owns, Needs, Txs]

// 접두어 생성은 모듈 내부 전용: Path 세그먼트 ++ tableName (bytes)
import scala.compiletime.constValue
inline def lenBytes(n: Int): ByteVector =
  ByteEncoder.bignatByteEncoder.encode(BigInt(n).refineUnsafe[Positive0])

inline def encodeSegment[S <: String]: ByteVector =
  val bytes = constValue[S].getBytes("UTF-8")
  lenBytes(bytes.length) ++ ByteVector.view(bytes) ++ ByteVector(0x00)

import scala.compiletime.erasedValue
inline def encodePath[Path <: Tuple](acc: ByteVector = ByteVector.empty): ByteVector =
  inline erasedValue[Path] match
    case _: EmptyTuple => acc
    case _: (h *: t)   => encodePath[t](acc ++ encodeSegment[h])

inline def tablePrefix[Path <: Tuple, Name <: String]: ByteVector =
  encodePath[Path] ++ encodeSegment[Name]
// 예) tablePrefix[("app", "accounts"), "balances"] → ("app","accounts","balances")를 접두어 바이트로 인코딩

final case class ModuleId(path: Tuple)

trait ModuleRoutedTx extends Tx:
  def moduleId: ModuleId

// 경로 없는 설계도(Blueprint)와 장착(mount) 스케치
trait TablesProvider[F[_], Provides <: Tuple]:
  def tables: Tables[F, Provides]

object TablesProvider:
  def empty[F[_]]: TablesProvider[F, EmptyTuple] = new:
    def tables: Tables[F, EmptyTuple] = EmptyTuple

trait StateReducer0[F[_], Owns <: Tuple, Needs <: Tuple]:
  type Combined = Owns ++ Needs
  def apply[T <: Tx](tx: T)(using
      Requires[tx.Reads, Combined],
      Requires[tx.Writes, Combined],
      Tables[F, Owns],                       // Path에 바인딩된 자체 테이블
      TablesProvider[F, Needs],              // 의존 모듈에서 주입된 테이블
  ): StoreF[F, (tx.Result, List[tx.Event])]

final class ModuleBlueprint[
  F[_],
  MName <: String,
  Owns <: Tuple,
  Needs <: Tuple,
  Txs <: Tuple,
](
  val owns: Owns,                             // Owns <: Tuple, 각 요소는 Entry[Name, K, V]
  val reducer0: StateReducer0[F, Owns, Needs],
  val txs: TxRegistry[Txs],
  val provider: TablesProvider[F, Needs],
)(using
  UniqueNames[Owns],
  Requires[Needs, Owns ++ Needs],
  ValueOf[MName],
)

  type OwnsType       = Owns
  type NeedsType      = Needs
  type TxsType        = Txs

extension [F[_], MName <: String, Owns <: Tuple, Needs <: Tuple, T <: Tuple]
  def mount[Path <: Tuple](bp: ModuleBlueprint[F, MName, Owns, Needs, T])(using PrefixFreePath[Path, Owns])
      : StateModule[F, Path, Owns, Needs, T] =
    val ownsTablesInst = SchemaInstantiation.instantiateTablesFromEntries[F, Path, Owns](bp.owns) // Entry → StateTable
    new StateModule[F, Path, Owns, Needs, T](
      ownsTables = ownsTablesInst,
      tablesProvider = bp.provider,
      reducer = new StateReducer[F, Path, Owns ++ Needs]:
        def apply[X <: Tx](tx: X)(using Requires[X#Reads, Owns ++ Needs], Requires[X#Writes, Owns ++ Needs]) =
          given Tables[F, Owns] = ownsTablesInst
          given TablesProvider[F, Needs] = bp.provider
          bp.reducer0.apply(tx),
      txs = bp.txs,
    )
```

```scala
// Schema(Entry 튜플) → 테이블 구현 튜플 매핑 (개념 스케치)
type TableOf[F[_], E] = E match
  case Entry[name, k, v] => StateTable[F] { type Name = name; type K = k; type V = v }

type Tables[F[_], Schema <: Tuple] = Tuple.Map[Schema, [E] =>> TableOf[F, E]]
```

## Blueprint Composition & Mounting
```scala
// 튜플 합성 별칭
type ++[A <: Tuple, B <: Tuple] = Tuple.Concat[A, B]

def tupleConcat[A <: Tuple, B <: Tuple](a: A, b: B): A ++ B =
  Tuple.fromArray(a.toArray ++ b.toArray).asInstanceOf[A ++ B]

// 두 설계도를 하나로 합성하는 `composeBlueprint`는 Phase 5.5 시점까지
// Needs = EmptyTuple인 설계도에 한해 제공되며, 외부 테이블 제공자는 고려하지 않는다.
// Needs를 보존한 상태에서의 합성은 `TablesProvider` 병합 전략(Phase 5.6)에서 다룬다.

### Generic Composition (Phase 5.7)
- `composeBlueprint`는 이제 `Blueprint` 일반형(단일 모듈/기 합성 설계도 포함)을 입력으로 받아 라우팅 리듀서를 재사용하며, 중첩 합성 없이도 모듈 계층을 평탄하게 다룰 수 있다.
- 라우팅은 입력 설계도의 `moduleValue`를 첫 세그먼트로 매칭하는 기존 전략을 유지하므로, 기 합성 설계도를 다시 합성해도 외부에서 동일한 모듈 이름으로 경합한다.
- 동일 증거 요구사항(`UniqueNames[A.OwnsType ++ B.OwnsType]`, `DisjointSchemas`, `TablesProjection`)은 유지되며, 각 합성 단계에서 증거를 제공해야 한다.
- 가변 인자 버전은 `headOption.fold(throw …)(_.tail.foldLeft(head)(composeBlueprint))` 형태로 폴딩하여 구현하며, 각 단계에서 타입 증거를 재사용할 수 있도록 givens/derivation 헬퍼를 권장한다.

```scala
// 다중 설계도 합성 예시
val composite = Blueprint.composeAll[F, "dapp"](
  AccountsBP,
  GroupBP,
  TokenBP,
)

// composeAll은 내부적으로 fold를 돌며 매 단계마다 증거를 summon:
// summonInline[UniqueNames[(Acc.OwnsType) ++ Next.OwnsType]] 등
```

// 베이스 경로 아래 하위 경로로 장착 (Base ++ Sub)
extension [F[_], MName <: String, Owns <: Tuple, Needs <: Tuple, T <: Tuple]
  def mountAt[Base <: Tuple, Sub <: Tuple](bp: ModuleBlueprint[F, MName, Owns, Needs, T])
      (using PrefixFreePath[Base ++ Sub, Owns])
      : StateModule[F, Base ++ Sub, Owns, Needs, T] =
    mount[Base ++ Sub](bp)
```

### Needs-Based Dependency Model (Phase 5.5)
- `Needs`는 블루프린트가 외부에서 제공받아야 하는 테이블(Entry 튜플) 집합으로, `StateReducer0`는 `Owns ++ Needs` 스키마에 대해 동작한다.
- `TablesProvider[F, Needs]`는 이러한 외부 테이블을 제공하는 의존성으로, 블루프린트가 컴파일 타임에 의존성을 선언한다.
- `ModuleBlueprint`는 `Owns`/`Needs`를 명시적으로 분리하고, 단일 `TablesProvider`를 통해 외부 테이블을 주입받는다.
- `StateModule`은 `tablesProvider`를 별도 필드로 보유하며, 더 이상의 의존성 튜플이 존재하지 않는다.
- `Needs = EmptyTuple`인 경우 `TablesProvider.empty`가 기본 제공되므로 기존 extend/compose 경로와 호환된다.
- `Needs`와 `Provides`는 모두 `Entry[Name, K, V]` 튜플로 구성되어야 하며, 이를 통해 `Tables[F, _]`, `Requires`, `Lookup` 증거가 타입 수준에서 정확히 연결된다.

### Aggregator Examples: Shared vs Sandboxed
```scala
// 블루프린트 정의 (Path-비의존)
val AccountsBP: ModuleBlueprint[F, "accounts", AccountsSchema, EmptyTuple, AccountsTxs] = ???
val GroupBP   : ModuleBlueprint[F, "group"   , GroupSchema   , EmptyTuple, GroupTxs   ] = ???
val TokenBP   : ModuleBlueprint[F, "token"   , TokenSchema   , EmptyTuple, TokenTxs   ] = ???

// 1) 공유 인스턴스: 동일 Path에 장착하여 테이블만 구분
val accountsShared = AccountsBP.mount[("app")]
val groupShared    = GroupBP   .mount[("app")]
val tokenShared    = TokenBP   .mount[("app")]

val dappShared =
  extend(extend(accountsShared, groupShared), tokenShared)

// 2) 샌드박스 인스턴스: 모듈별로 고유 Path를 사용
val groupStack =
  extend(
    AccountsBP.mount[("app", "group")],
    GroupBP.mount[("app", "group")],
  )

val tokenStack =
  extend(
    AccountsBP.mount[("app", "token")],
    TokenBP.mount[("app", "token")],
  )

// 3) 설계도 합성 후 단일 장착 (대규모 조립에 유용)
val CoreBP = composeBlueprint[F, "core"](AccountsBP, GroupBP)
val DAppBP = composeBlueprint[F, "dapp"](CoreBP, TokenBP)
val dappOnceMounted = DAppBP.mount[("app")]
```

### Needs-Based Dependency Example (Phase 5.5)
```scala
// 1) Accounts blueprint: owns balances/accounts, needs nothing external
type AccountsOwns   = AccountsSchema
type AccountsNeeds  = EmptyTuple

val AccountsBP = new ModuleBlueprint[F, "accounts", AccountsOwns, AccountsNeeds, AccountsTxs](
  owns = accountsEntries,           // Entry 튜플 (예: (accountsEntry, balancesEntry))
  reducer0 = accountsReducer0,      // StateReducer0[F, AccountsOwns, AccountsNeeds]
  txs = accountsTxRegistry,
  provider = TablesProvider.empty[F], // Needs = EmptyTuple → 기본 프로바이더 사용
)

// 2) Group blueprint: owns 그룹 테이블, needs 계정 잔고/계정 테이블
type GroupOwns   = GroupSchema
type GroupNeeds  =
  Entry["accounts", Address, Account] *:
  Entry["balances", Address, BigNat] *:
  EmptyTuple

val groupNeedsProvider: TablesProvider[F, GroupNeeds] = ??? // Accounts 모듈에서 유도

val GroupBP = new ModuleBlueprint[F, "group", GroupOwns, GroupNeeds, GroupTxs](
  owns = groupEntries,              // Entry 튜플 (예: (groupsEntry, membersEntry))
  reducer0 = new StateReducer0[F, GroupOwns, GroupNeeds]:
    def apply[T <: Tx](tx: T)(using
        Requires[tx.Reads, GroupOwns ++ GroupNeeds],
        Requires[tx.Writes, GroupOwns ++ GroupNeeds],
        tablesOwn: Tables[F, GroupOwns],
        provider: TablesProvider[F, GroupNeeds],
    ): StoreF[F, (tx.Result, List[tx.Event])] =
      groupReducerLogic(tx, tablesOwn, provider.tables)
  ,
  txs = groupTxRegistry,
  provider = groupNeedsProvider, // 조립 단계에서 프로바이더 주입
)

// 3) 조립: Accounts를 mount한 뒤 그 테이블로 TablesProvider를 구성하여 GroupBP에 전달
val accountsModule = AccountsBP.mount[("app", "accounts")]

val groupModule = GroupBP.mount[("app", "group")]

// Phase 5.5 TODO:
//   extend/accountsModule/groupModule를 결합하려면 Needs ≠ EmptyTuple인 모듈을 합치는
//   새로운 extend/composition 전략이 필요하다(테이블 프로바이더 병합).
```

> NOTE: Phase 5.5에서는 `Needs`에 선언된 테이블이 실제로 제공되었는지 컴파일 타임에 검증한다.
> `ModuleBlueprint`는 `Needs ⊆ Owns ++ Needs` 증거와 `TablesProvider` 의존성을 동시에 요구하며,
> `extend`는 현재 `Needs = EmptyTuple` 모듈에 한해 제공된다. 외부 테이블을 요구하는 모듈을 결합하려면 향후 `TablesProvider` 병합 전략을 도입해야 하며, 이는 Phase 5.6 (TBD)에서 다룬다.

### Reducer Routing Strategy

**Module-Relative IDs (Critical Invariant)**
- `ModuleId.path` is ALWAYS module-relative: `MName *: SubPath`
- The mount path is NEVER prepended to transaction `moduleId.path`
- Example: `AccountsTransfer` always has `moduleId = ModuleId(("accounts" *: EmptyTuple))`, regardless of where the AccountsBP is mounted

**Why Module-Relative?**
- Mounting is a deployment concern, not a logical identity concern
- Transactions remain portable across different deployment paths
- Routing logic stays simple: just match the first segment (MName)
- Full paths (mountPath ++ moduleId.path) can be reconstructed at system edges for telemetry/logging

**Routing Mechanism and Compile-Time Safety**
- `composeBlueprint` creates a `ComposedBlueprint` with `RoutedStateReducer0`
- `StateModule.mountComposed` returns `StateModule[..., RoutedStateReducer[F, Path, Schema]]`
- **Compile-time safety throughout the entire stack**:
  - Attempting to apply a non-routed transaction to a composed module's reducer will fail at compile time
  - Type bound `T <: Tx & ModuleRoutedTx` is enforced by both `RoutedStateReducer0` and `RoutedStateReducer`
  - No unsafe casts needed - type safety is preserved from blueprint to mounted module
- The reducer routes based on `moduleId.path.head` matching M1 or M2
- No prefix stripping required since paths are already module-relative

**Example**
```scala
case class AccountsTransfer(...) extends Tx with ModuleRoutedTx:
  // Always module-relative, never changes after mount
  val moduleId = ModuleId(("accounts" *: EmptyTuple))
  type Reads = Entry["balances", Address, BigInt] *: EmptyTuple
  type Writes = Entry["balances", Address, BigInt] *: EmptyTuple

// When mounted at ("app"), the transaction moduleId stays ("accounts")
// Full path for telemetry: ("app") ++ ("accounts") = ("app", "accounts")
```

### Tuple Concatenation Semantics
- `++` 별칭은 `Tuple.Concat`의 얕은(flat) 결합을 의도한다. 런타임에서도 `tupleConcat`을 사용해 동일한 평탄 구조를 유지해야 `Tables`, `Needs` 등 타입 수준 정보와 일치한다.
- 설계도/모듈 조합 시 중첩 튜플을 남겨 두면 조회기(`Lookup`), 증거(`Requires`)가 모두 깨진다. 반드시 `tupleConcat` 계열 헬퍼로 합성한다.

## Assembly Flow Guidance
- Compose-then-mount(권장, 기본)
  - 장점: 중앙에서 한 번 mount → 접두어 정책 검증 지점 단일화, 경로 안정성↑, 증명/메트릭 파티셔닝 용이.
  - 단점: 부분 배치 실험 시 매번 합성·빌드 경로를 통과해야 함.
- Mount-then-extend(선택)
  - 장점: 블루프린트를 여러 경로에 독립 장착(샌드박싱)하거나 공유 장착을 명시적으로 조합 가능. 테스트/AB 실험에 유리.
  - 단점: 접두어 검증/라우팅 지점이 늘고 합성 복잡도↑.
- Shared vs Sandboxed
  - Shared: 저장 중복 없음, 단일 관점 유지, 교차 모듈 트랜잭션 모델링 단순. 결합도↑, 장애 전파 가능성↑.
  - Sandboxed: 격리/롤백 용이, 실험에 유리. 데이터/증명 중복↑, 교차 샤드 전송은 브리징 필요.
- 실무 가이드
  - 단일 테넌트 DApp: compose-then-mount로 시작하되, 특정 서브스택만 샌드박싱이 필요하면 해당 블루프린트만 별도 mount.
  - 다테넌트/멀티 인스턴스: 각 테넌트를 Base Path로 분리하고, 공용 모듈은 Shared 또는 버전 고정 블루프린트로 제공.
  - 경로 네이밍: `("app", version, module, submodule, ...)` 형태 권장. 마이그레이션은 새 Path에 mount 후 데이터 이동 Tx로 수행.
  - 검증 체크리스트: UniqueNames(전역), PrefixFreePath(전역), Requires 충족, OrderedCodec/FixedSize 등 특성 증거 확인.

## Prefix Encoding Format
- 목표: 바이트 단위에서 엄격한 prefix‑free 보장.
- **Path-Level Framing (2025-10-31 구현)**:
  - **경로 인코딩**: `lenBytes(num_segments) ++ segment1 ++ segment2 ++ ...`
  - **세그먼트 인코딩**: `lenBytes(segment_bytes.length) ++ segment_bytes ++ 0x00`
  - **테이블 접두사**: `encodePath(path) ++ encodeSegment(tableName)`
  - 예시:
    - `encodePath[("app", "v1")]` → `[2][len]["app"][0x00][len]["v1"][0x00]`
    - `encodePath[("app")]` → `[1][len]["app"][0x00]`
    - `encodePath[EmptyTuple]` → `[0]`
  - **핵심**: 경로 수준의 길이 헤더로 짧은 경로가 긴 경로의 prefix가 될 수 없음을 보장
  - **Empty segment 지원**: 이전 설계에서는 `""` → `0x0000`이 `("")` + `("")` → `0x00000000`의 prefix가 되는 문제 존재 → path-level framing으로 해결
- 대안: 각 세그먼트/테이블 이름을 고정 길이 해시(예: Keccak256)로 치환 후 결합(사전 정의된 충돌 위험 허용 시).
- 검증 범위: 모듈 내부 + 의존/집합 결합된 모든 테이블 집합에 대해 전역 prefix‑free 검사.
- 구현: `PathEncoding.scala`의 `encodePath`, `encodeSegment`, `tablePrefixRuntimeFromList` 참조

## Streaming API
- 의도: “테이블 전체” vs “특정 범위/접두어” 스트리밍을 명확히 분리.
- 추천 시그니처 예시
```scala
// 특정 테이블 인스턴스에 귀속된 접두어 타입 (필요 시)
final case class KeyPrefix[T <: StateTable[?]](bytes: ByteVector)

trait StateTable[F[_]] { self =>
  type Name <: String
  type K; type V
  given ByteCodec[K]; given ByteCodec[V]
  type Key = KeyOf[self.type, K]

  def streamAll: StoreF[F, Stream[Eff[F], (K, V)]]
  def stream(prefix: KeyPrefix[self.type])(using OrderedCodec[K]): StoreF[F, Stream[Eff[F], (K, V)]]
  def streamFrom(start: K)(using OrderedCodec[K]): StoreF[F, Stream[Eff[F], (K, V)]]
  def reverseStreamFrom(prefix: KeyPrefix[self.type], until: Option[K])(using OrderedCodec[K]): StoreF[F, Stream[Eff[F], (K, V)]]
}
```

## OrderedCodec Law
- 법칙: `compare(k1, k2) ≡ lexCompare(encode(k1), encode(k2))`를 만족해야 범위/사전식 스트림의 정확성이 보장된다.
- 제공 계획: 표준 타입(Int, Long, BigInt, String(UTF‑8), ByteVector 등) 기본 인스턴스.
- 검증: property‑based testing으로 법칙 준수 확인.

## Effect Stack Flexibility
- 기본 정의는 `Eff[F] = EitherT[F, SigilarisFailure, *]`로 제공하고, 확장을 위해 제네릭 별칭을 추가한다.
```scala
type AnyEff[F[_], E]   = EitherT[F, E, *]
type AnyStore[F[_], E] = StateT[AnyEff[F, E], MerkleTrieState, *]
type Eff[F[_]]         = AnyEff[F, SigilarisFailure] // 짧은 별칭(컨텍스트 바운드 친화)
```

## DAppStateTag Usage
- 임의 타입이 DApp 상태로 오인되지 않도록, API는 태그 증거를 요구한다.
```scala
def submit[S](s: S)(using DAppStateTag[S]): Unit =
  () // DAppState로만 호출 가능
```

```scala
// 보다 실질적인 예시: 트랜잭션 실행
def executeTransaction[F[_], Path <: Tuple, Schema <: Tuple, T <: Tx](
  state: DAppState[Path, Schema],
  tx: T,
  reducer: StateReducer[F, Path, Schema]
)(using Requires[T#Reads, Schema], Requires[T#Writes, Schema]):
  StoreF[F, (T#Result, List[T#Event])] =
  reducer(tx) // 실행은 호출부에서 `.run(state)`로
```

## AccessLog Policy
- 저장 단위: 테이블 식별자(경로 세그먼트 + 테이블 이름의 바이트 인코딩)와 키 바이트(접두어 합성 후).
- 크기 제어 옵션:
  - Reads: Bloom/roaring bitmap(근사) 또는 고정 깊이 prefix 보관, Writes: 정확 키 보관.
  - 상한치: 트랜잭션별 최대 N개 키/프리픽스. 초과 시 degrade(근사화) + 메트릭.
  - 대안: 키 바이트의 고정 길이 해시(충돌 이론적 가능성 수용).

## PrefixFree Composition (Sketch)
```scala
trait PrefixFreeCombine[
  Path1 <: Tuple, S1 <: Tuple,
  Path2 <: Tuple, S2 <: Tuple,
]:
  type Combined <: Tuple
  def left:  PrefixFreePath[Path1, Combined]
  def right: PrefixFreePath[Path2, Combined]
```

## Module Dependencies & Cross-Module Access
```scala
// S가 Needs를 모두 포함함을 나타내는 증거 (이미 사용 중인 Requires 재사용)
trait Requires[Needs <: Tuple, S <: Tuple]

// 스키마 S 안에서 테이블 Name을 찾아 타입과 인스턴스를 제공하는 조회기
// K, V는 타입 파라미터로 명시하여 table 메서드가 정확한 타입을 반환하도록 보장
trait Lookup[S <: Tuple, Name <: String, K, V]:
  def table[F[_]](tables: Tables[F, S]): StateTable[F] { type Name = Name; type K = K; type V = V }

// 리듀서는 'S'에 대해 다형적이며, 필요한 모듈 스키마가 S의 부분집합임을 증거로 요구
trait TokenReducer[F[_], Path <: Tuple, S <: Tuple](using
  Requires[AccountsSchema, S], // 의존 모듈: Accounts가 S에 포함
  Requires[TokenSchema,    S], // 자체 스키마: Token이 S에 포함
) extends StateReducer[F, Path, S]:
  def apply(tx: Transfer)(using
    tables: Tables[F, S],               // 결합된 테이블 값 레코드
    acc: Lookup[S, "accounts", Addr, Account],  // 의존 모듈 테이블 조회 (K, V 타입 명시)
    bal: Lookup[S, "balances", Addr, BigInt],   // 자체 모듈 테이블 조회 (K, V 타입 명시)
    OrderedCodec[Addr],                 // 범위/정렬이 필요하다면
  ): StoreF[F, (Unit, List[Event])] =
    val accounts = acc.table[F](tables)  // tables를 명시적으로 전달
    val balances = bal.table[F](tables)
    for
      maybeFrom <- accounts.get(accounts.brand(tx.from))
      _ <- maybeFrom.fold(StoreF.pure[F](())): _ =>
        balances.put(balances.brand(tx.from), /* ... */ )
        balances.put(balances.brand(tx.to),   /* ... */ )
    yield ((), List(Event.Transferred(tx.from, tx.to, tx.amount)))

// 확장(extend): 동일 Path에서 두 모듈을 합쳐 상위 스키마/트랜잭션/리듀서를 만든다
// 권장 어셈블리(blueprint-first): 먼저 blueprint들을 composeBlueprint로 합성한 뒤,
// 한 번 mount하여 큰 모듈을 만든다. 샌드박싱이 필요하면 각 스택을 별도 mount.
type ++[A <: Tuple, B <: Tuple] = Tuple.Concat[A, B]

def extend[
  F[_]: cats.Monad,
  Path <: Tuple,
  Owns1 <: Tuple,
  Owns2 <: Tuple,
  T1 <: Tuple,
  T2 <: Tuple,
](
  a: StateModule[F, Path, Owns1, EmptyTuple, T1],
  b: StateModule[F, Path, Owns2, EmptyTuple, T2],
)(using
  UniqueNames[Owns1 ++ Owns2],
  PrefixFreePath[Path, Owns1 ++ Owns2],
): StateModule[F, Path, Owns1 ++ Owns2, EmptyTuple, T1 ++ T2] =
  val mergedOwns = mergeTables(a.ownsTables, b.ownsTables)
  val mergedReducer = mergeReducers(a.reducer, b.reducer)
  val mergedTxs = a.txs.combine(b.txs)

  new StateModule[F, Path, Owns1 ++ Owns2, EmptyTuple, T1 ++ T2](
    ownsTables = mergedOwns,
    tablesProvider = TablesProvider.empty[F], // Needs = EmptyTuple
    reducer = mergedReducer,
    txs = mergedTxs,
  )

// 리듀서 합성은 'S'가 두 스키마의 합집합임을 가정하고 내부에서 적절히 라우팅/합성
def mergeReducers[F[_], Path <: Tuple, S1 <: Tuple, S2 <: Tuple](
  r1: StateReducer[F, Path, S1],
  r2: StateReducer[F, Path, S2],
): StateReducer[F, Path, S1 ++ S2] = new:
  def apply[T <: Tx](tx: T)(using Requires[T#Reads, S1 ++ S2], Requires[T#Writes, S1 ++ S2]) =
    // 전략 1) Tx 라우팅 (우선순위/패턴 매칭)
    // 전략 2) Reducer Registry로 명시적 디스패치
    r1.apply(tx) // orElse r2.apply(tx) 등 정책에 맞게 설계

// ModuleFactory: 팩토리로 모듈을 Path-매개화하여 다른 Path에서 재사용
// LIMITATION: Needs = EmptyTuple로 제한 (교차 모듈 의존성 없는 모듈만 가능)
trait ModuleFactory[F[_], Owns <: Tuple, T <: Tuple]:
  def build[Path <: Tuple]: StateModule[F, Path, Owns, EmptyTuple, T]

// 집합 결합: mount → extend 패턴 사용 (production-ready)
val module1 = StateModule.mount(blueprint1)
val module2 = StateModule.mount(blueprint2)
val combined = StateModule.extend(module1, module2)

// blueprint-first를 선호한다면: 여러 blueprint를 composeBlueprint로 합친 뒤 최종적으로 한 번 mount.
```

## Consequences
- 장점
  - 경로/접두어 규칙이 모듈에서 일관되게 강제되어 테이블 충돌을 예방한다.
  - 테이블은 순수 스키마로 유지되어 재사용/조합이 용이하다.
  - 타입 레벨 증거로 존재/코덱/순서/버전/경로를 엄격히 보장한다(런타임 가드 코드 감소).
  - 정적 요구 집합 + 동적 AccessLog로 트랜잭션 충돌을 빠르게 판정할 수 있다.
  - 모듈 의존/집합 결합으로 기능 확장이 체계적이고, 최상위 모듈을 DApp으로 자연스럽게 정의할 수 있다.
- 트레이드오프
  - 타입 레벨 복잡도 증가 및 에러 메시지 가독성 저하 가능.
  - 접두어 prefix-free 증거 합성 시(모듈 집합 결합) 증명 도구가 필요.

## Compile-Time Checks (현황 및 후속 과제)
- **ByteCodec** ✅: `StateTypes.scala`의 `Entry` 생성 시 키/값 코덱 evidence를 요구하고, `StateTable.atPrefix`에서도 동일 증거를 재노출한다.
- **Requires** ✅: `Evidence.scala`의 `Requires`/`Contains` 타입클래스가 트랜잭션 `Reads`·`Writes ⊆ Schema`를 보증하며, `StateReducer`·`StateReducer0` 전역에서 `using Requires[...]`로 강제한다.
- **UniqueNames** ✅: 동일 파일의 `UniqueNames`/`NameNotInSchema`가 스키마 내 중복 테이블 이름을 컴파일 타임에 차단하고, `ModuleBlueprint`·`StateModule`·`composeBlueprint` 모두 evidence를 요구한다.
- **PrefixFreePath** ✅: `Evidence.scala`의 `PrefixFreePath`가 길이 접두 인코딩과 `UniqueNames` 조합으로 접두어 충돌 방지 증거를 제공하며, `StateModule.mount`/`extend`에서 소환한다.
- **Effect Stack** ✅: `StateTypes.scala`에서 `Eff[F] = EitherT[F, SigilarisFailure, *]`, `StoreF[F] = StateT[Eff[F], StoreState, *]` 별칭으로 고정 효과 스택을 정의하고 전역 API가 이를 사용한다.
- **OrderedCodec[K]** ⚠️: `OrderedCodec.scala`와 대응 테스트는 준비돼 있으나, `StateTable` 스트리밍 API가 아직 `using OrderedCodec` 제약을 요구하지 않는다. 향후 스트리밍/범위 조회 기능에 제약을 연결해야 한다.
- **DAppStateTag** ⛔: `DAppState`/`DAppStateTag` 타입과 소비 측 증거 요구가 미구현 상태다.
- **Dependency DAG** ⛔: 모듈 의존 그래프 순환 검증 evidence가 없으며, 현재는 `TablesProvider.DisjointSchemas` 수준에 머물러 있다.
- **FixedSize[K, N]** ⛔: 고정 길이 키 증거 타입과 호출부가 존재하지 않는다.
- **Proof Coupling** ⛔: `MerkleProof`에 테이블/키 팬텀을 부여해 교차 사용을 막는 구조가 아직 도입되지 않았다.

> **다음 단계 제안**: OrderedCodec 제약을 스트리밍 API에 연결하고, DAppStateTag·Dependency DAG 등 미구현 항목을 우선순위에 따라 도입하는 작업이 자연스러운 후속 과제다.

## Open Questions
- TablesProvider 병합 전략: Needs ≠ EmptyTuple 모듈을 extend/compose 할 때 어떤 증거를 요구하고, 충돌을 어떻게 보고할 것인가? (Phase 5.6)
- PrefixFree 증거 합성 자동화: 의존/결합 시 PrefixFreePath를 간결하게 재사용할 수 있는 파생 규칙을 도입할 것인가?
- OrderedCodec/FixedSize 등 특성 증거 자동 유도 범위는 어디까지 허용할 것인가?
- AccessLog 포맷과 크기 제어(블록 단위/샤딩) 정책을 어떻게 결정할 것인가?
- 모듈 간 교차 트랜잭션에 대한 증명(Proof)을 어느 경계에서 생성/검증할 것인가?

## Implementation Plan (Phases)

Phase 1 — Core
- Deliverables
  - `StateTable` (path-independent, instance branding for keys)
  - `KeyOf` opaque type + `brand` helper on tables
  - `Entry`, `TableOf`, `Tables` type machinery
- Tasks
  - Add core traits/types under `core` package (shared module)
  - Ensure `ByteCodec` instances exist or stub for demo types
  - Keep effect stack aliases: `Eff`, `StoreF`
- Tests
  - Compile-only sanity: derive `Tables` from a small schema tuple
  - Basic CRUD round-trip using `MerkleTrie` with in-memory `NodeStore`
- Criteria
  - Keys cannot cross tables (compile-time), no runtime overhead

Phase 2 — Blueprint
- Deliverables
  - `ModuleBlueprint` (pathless), `StateReducer0` (path-agnostic)
  - `mount` helper that yields `StateModule[F, Path, …]`
- Tasks
  - Implement mount wrapper that binds Path and exposes `tablePrefix`
  - Add `lenBytes`/`encodeSegment`/`encodePath` helpers (or reuse from util)
- Tests
  - Mount the same blueprint at two different paths; prefixes differ
  - Property: prefix equals `encodePath(Path) ++ encodeSegment(Name)`
- Criteria
  - Blueprint code never hardcodes Path; mounting decides placement

Phase 3 — Composition
- Deliverables
  - `composeBlueprint` (Owns/Tx union; Needs must be EmptyTuple until Phase 5.6)
  - Evidence: `UniqueNames` and `PrefixFreePath` (simple version)
- Tasks
  - Provide minimal `UniqueNames` (no duplicate table names in Schema)
  - Provide minimal `PrefixFreePath` by checking encoded prefixes with a runtime validator in tests
  - Add `mountAt(Base ++ Sub)` convenience
- Tests
  - Compose two blueprints; UniqueNames violation should fail to summon
  - Validate prefix-free over all tables in composed module
- Criteria
  - Composition works for common cases; collisions are detected

Phase 4 — Dependencies
- Deliverables
  - `Requires[Needs, S]` evidence (Needs ⊆ S)
  - `Lookup[S, Name]` typeclass to obtain a concrete `StateTable` instance
  - Cross-module access pattern in reducers
- Tasks
  - Implement subset evidence over tuple types (simple recursive typeclass)
  - Implement `Lookup` over `Tables` (index by literal Name)
- Tests
  - A reducer that needs Accounts + Token compiles only when S includes both
  - Runtime: read from Accounts, write to Token using branded keys
- Criteria
  - Illegal access is a compile error; legal access runs and updates trie

### Phase 5 — Assembly (PARTIAL: Core Patterns Proven, ModuleFactory Limited)

#### Feature Status Overview
| Feature            | Status           | Use When                                   |
|--------------------|------------------|--------------------------------------------|
| `extend`           | ✅ Production     | 동일 Path, `Needs = EmptyTuple` 모듈 결합      |
| `mergeReducers`    | ✅ Production     | 다중 리듀서 fallback 이 필요한 경우           |
| `composeBlueprint` | ✅ Production     | 여러 블루프린트를 하나로 묶어 단일 mount      |
| `ModuleFactory`    | ⚠️ Limited        | 외부 의존성 없는(Needs=EmptyTuple) 모듈 복제  |
| `aggregate`        | ⛔ Removed        | subset 증거 확보 전까지 사용 금지             |

#### Production-Ready Features
- **`extend`**: 동일 Path에서 두 모듈을 결합(Needs = EmptyTuple). 스키마/트랜잭션이 합쳐지고 Phase5Spec에서 4개 테이블 결합을 검증.
- **`mergeReducers`**: 에러 기반 fallback. r1 실패 시 r2를 시도하며, 빈 이벤트가 fallback을 유발하지 않도록 수정됨.
- **Shared vs Sandboxed 패턴**: Phase5Spec에서 공유/격리 장착 테스트 완료.

#### Limited Features
- **`ModuleFactory`**: `Needs = EmptyTuple`일 때만 fromBlueprint 허용. 외부 테이블이 필요한 모듈에는 직접 mount 또는 compose 권장.
- **`mergeReducers` 한계**: 라우팅 없이 모든 리듀서를 시도할 수 있음. ModuleRoutedTx 기반 라우팅(Phase 3)을 장기적으로 도입 예정.

#### Removed Features
- **`aggregate`**: subset 증거 부재로 삭제. mount → extend 패턴 사용 또는 provider 병합(Phase 5.6) 이후 사용을 고려.

#### Phase 5 Follow-up 완료 항목
1. mergeReducers 경로별 테스트 (성공·실패·빈 이벤트)
2. ModuleFactory 컴파일 시 `Needs = EmptyTuple` 강제
3. fallback 전략을 “오류 발생 시” 기준으로 조정(쿼리 트랜잭션 지원)

#### 실무 권장
- 가장 안전한 흐름: composeBlueprint → 단일 mount
- 실험/샌드박스: 독립 mount 후 extend (Needs = EmptyTuple 조건 하에서)
- 외부 테이블 필요 시: Phase 5.6 provider 병합 전략 도입 전까지 단일 블루프린트로 compose

Phase 5.5 — Needs-Based Dependency Providers (DESIGN SIGNED-OFF, IMPLEMENTATION PENDING)
- Deliverables
  - Introduce `TablesProvider[F, Provides]` (with `TablesProvider.empty`) as the canonical dependency handle (naming highlights what the provider supplies; blueprints consume the same tuple via `Needs`)
  - Update `ModuleBlueprint`/`StateModule` to split `Owns` and `Needs`, with providers carried explicitly
  - Remove dependency tuples entirely; provider wiring is the only external requirement
  - Documentation and examples (this ADR) reflecting the provider-backed model
- Tasks
  - Enforce at compile time that a non-empty `Needs` cannot build without a matching `TablesProvider`
  - Adapt `extend`/`composeBlueprint` signatures to keep working for `Needs = EmptyTuple` and surface TODOs for provider composition
  - Provide helper utilities to derive `TablesProvider` from mounted modules (remaining Phase 5.5 follow-up task)
- Tests (planned)
  - Compile-only: blueprint construction fails without provider when `Needs ≠ EmptyTuple`
  - Runtime: reducer can read provider-supplied tables while keeping cross-module access type-safe
  - Regression: existing Phase 5 tests remain green with flattened dependency tuples
- Criteria
  - Nested dependency tuples are eliminated
  - Blueprint authors declare external requirements as `Needs` (Entry tuples) rather than concrete module handles
  - Clear TODOs captured for provider merge strategy before enabling extend/composition for `Needs ≠ EmptyTuple`

Phase 5.6 — Provider Composition (TBD)
- Goal: enable `extend`/composition for modules with non-empty `Needs`
- Scope candidates
  - Define `TablesProvider.merge` (or similar) to compose providers when schemas are disjoint
  - Extend `extend`/`composeBlueprint` signatures to accept provider merge evidence
  - Capture failure modes (conflicting providers, overlapping schemas) with explicit compiler errors
- Outcome
  - Modules requiring external tables can be combined at compile time without losing type safety

Phase 5.7 — Blueprint Composition Generalization (DRAFT)
- Deliverables
  - Broaden `Blueprint.composeBlueprint` to accept any `Blueprint` (single or composed) while preserving routing semantics.
  - Provide a variadic `composeAll` (or equivalent) that folds over an arbitrary number of blueprints using the generalized binary helper.
  - Document evidence requirements (`UniqueNames`, `DisjointSchemas`, `TablesProjection`) and recommended derivation utilities.
- Tasks
  - Refactor the existing binary implementation to operate on the sealed trait; update call sites/tests.
  - Implement the fold-based API and ensure type inference remains stable with nested compositions.
  - Add regression tests covering three-plus module composition and mixed Module/Composed inputs.
- Tests
  - Compile-time checks ensuring incorrect routing (non-`ModuleRoutedTx`) continues to fail.
  - Runtime tests verifying delegating reducers still select the correct moduleId head segment.
  - Property/regression tests for `composeAll` to confirm associative folding and schema evidence reuse.
- Criteria
  - Users can chain compositions without manual unwrapping of intermediate composed blueprints.
  - Routing failures still yield precise `RoutingFailure` diagnostics.
  - Documentation and samples reflect the new API surface (ADR updated in Phase 5.7 notes above).

Phase 6 — Example Blueprints (Accounts, Group)
- See ADR‑0010 (Blockchain Account Model and Key Management) and ADR‑0011 (Blockchain Account Group Management) for detailed schemas, transactions, and reducer rules.
- Deliverables
  - `AccountsBP` and `GroupBP` implemented per ADR‑0010/0011.
- Tasks
  - Implement schemas and `ByteCodec` for demo types; provide `StateReducer0` per ADRs.
- Tests
  - Compose‑then‑mount at `("app")`; scenario: create account → create group → add member; verify `Lookup` and branded keys.
- Criteria
  - End‑to‑end scenario passes; prefix‑free and `Requires` invariants hold.

Phase 7 — Law & Property Tests ✅ (2025-10-31 완료)
- Deliverables
  - OrderedCodec law checks ✅
  - Prefix-free validator coverage ✅
  - Path-level framing implementation ✅
- Tasks
  - Add property tests for OrderedCodec and encoded path ordering where needed ✅
  - Fuzz tests for prefix encoding and composition ✅
  - **Critical Fix**: Path-level framing to prevent shorter paths from being prefixes of longer paths ✅
- Criteria
  - Laws hold across supported primitives and demo types ✅
  - All 270 tests passing including aggressive fuzz tests ✅
- Key Findings
  - Empty segment prefix collision discovered and fixed with path-level length header
  - OrderedCodec required only for KEY types (not path encoding)
  - Path encoding is prefix-free but does NOT preserve lexicographic ordering (intentional)

Phase 8 — AccessLog & Conflicts ✅ **COMPLETED**
- Deliverables
  - ✅ `AccessLog` accumulation and simple conflict predicates (W∩W, R∩W)
  - ✅ `StoreState` wrapper integrating AccessLog with MerkleTrieState
  - ✅ Automatic access recording in `StateTable` operations
- Tasks
  - ✅ Integrate logging into StateTable operations (get/put/remove record accesses)
  - ✅ Size caps and metrics surfaces (readCount, writeCount, exceedsLimits helper)
  - 📋 DEFERRED: Bloom/roaring filters for read optimization (premature optimization)
  - 📋 DEFERRED: Automatic enforcement of size limits (exceedsLimits exposed but not called)
- Criteria
  - ✅ Conflicts detected on crafted overlapping txs (41 comprehensive tests)
  - ⚠️  Memory bounds available via exceedsLimits helper, enforcement deferred to higher layer
  - ✅ All tests passing (311 total, including 41 AccessLogTest cases)
- Test Coverage
  - `AccessLogTest`: 41 tests covering basic operations, combine, conflicts, metrics, real-world scenarios
  - Conflict detection validated: W∩W (write-write), R∩W (read-write), W∩R (write-read)
  - Real-world scenarios: concurrent account creates, parallel operations, batch size limits
  - Integration: StateTable operations automatically record accesses in AccessLog
- Implementation Notes
  - AccessLog keys by ByteVector table prefix (not String) for precise prefix-free guarantees
  - readCount/writeCount measure unique keys (Set size), not individual operation counts
  - exceedsLimits is a query helper; automatic enforcement deferred to transaction execution layer
  - StoreState wrapper combines MerkleTrieState + AccessLog in single state monad

## References
- 구현 레퍼런스: `modules/core/shared/src/main/scala/org/sigilaris/core/merkle/MerkleTrie.scala`
- 기존 ADR들과 성능/코덱 규약: `docs/adr/0001-0008*`, `docs/perf/criteria.md`
- ADR‑0010(Accounts): `docs/adr/0010-blockchain-account-model-and-key-management.md`
- ADR‑0011(Group): `docs/adr/0011-blockchain-account-group-management.md`
