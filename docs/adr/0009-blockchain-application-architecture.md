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
  - 모듈 설계도(경로 없음): `ModuleBlueprint` — 여러 `StateTable`, 트랜잭션 집합, `StateReducer0`(경로 비의존)를 소유. 어디에 배치될지 모른다는 가정으로 설계.
  - 장착된 모듈(경로 부여됨): `StateModule` — 설계도를 특정 Path에 장착하여 접두어를 합성하는 실체. DApp을 구성하는 단위.
  - 애플리케이션 상태: `DAppState` — 특정 Path, 특정 스키마의 머클 상태 래퍼(타입 레벨 증거로만 생성/소비).
  - 전이기: `StateReducer` — 트랜잭션을 해석/적용하여 상태를 전이시키고 결과/이벤트 및 접근 로그를 축적. 설계도 단계에서는 Path 비의존, 장착 시 Path 바인딩.

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
type StoreF[F[_]] = StateT[Eff[F], MerkleTrieState, *]

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

// 전이기: 정적 증거 + 동적 AccessLog 축적
final case class AccessLog(
  reads:  Map[String, Set[ByteVector]],
  writes: Map[String, Set[ByteVector]]
)

trait StateReducer[F[_], Path <: Tuple, Schema <: Tuple]:
  def apply[T <: Tx](tx: T)(using Requires[T#Reads, Schema], Requires[T#Writes, Schema])
    : StoreF[F, (T#Result, List[T#Event])]

// 모듈: 테이블/리듀서/트랜잭션/의존을 소유하고 접두어 규칙을 보장
trait UniqueNames[Schema <: Tuple]
trait PrefixFreePath[Path <: Tuple, Schema <: Tuple] // encodePath(Path) ++ Name 기반 byte prefix-free 증거

final class StateModule[F[_], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
  val tables: Tables[F, Schema],
  val reducer: StateReducer[F, Path, Schema],
  val txs: TxRegistry[Txs],
  val deps: Deps
)(using UniqueNames[Schema], PrefixFreePath[Path, Schema])

type DApp[F[_], Path <: Tuple, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple] =
  StateModule[F, Path, Schema, Txs, Deps]

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

final case class ModuleId(path: Tuple)

trait ModuleRoutedTx extends Tx:
  def moduleId: ModuleId

// 경로 없는 설계도(Blueprint)와 장착(mount) 스케치
trait StateReducer0[F[_], Schema <: Tuple]:
  def apply[T <: Tx](tx: T)(using Requires[T#Reads, Schema], Requires[T#Writes, Schema])
    : StoreF[F, (T#Result, List[T#Event])]

final class ModuleBlueprint[F[_], MName <: String, Schema <: Tuple, Txs <: Tuple, Deps <: Tuple](
  val tables: Tables[F, Schema],
  val reducer0: StateReducer0[F, Schema], // Path 비의존 리듀서
  val txs: TxRegistry[Txs],
  val deps: Deps,
)(using UniqueNames[Schema])

extension [F[_], MName <: String, S <: Tuple, T <: Tuple, D <: Tuple]
  def mount[Path <: Tuple](bp: ModuleBlueprint[F, MName, S, T, D])(using PrefixFreePath[Path, S])
      : StateModule[F, Path, S, T, D] =
    new StateModule[F, Path, S, T, D](
      tables = bp.tables,
      reducer = new StateReducer[F, Path, S]:
        def apply[X <: Tx](tx: X)(using Requires[X#Reads, S], Requires[X#Writes, S]) =
          bp.reducer0.apply(tx),
      txs = bp.txs,
      deps = bp.deps,
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

// 두 설계도를 하나로 합성 (스키마/Tx/Deps ∪)
// IMPORTANT: Composed blueprints produce a RoutedStateReducer0 that requires
// transactions to implement ModuleRoutedTx. This is enforced at compile time.
def composeBlueprint[F[_], MOut <: String,
  M1 <: String, S1 <: Tuple, T1 <: Tuple, D1 <: Tuple,
  M2 <: String, S2 <: Tuple, T2 <: Tuple, D2 <: Tuple,
](
  a: ModuleBlueprint[F, M1, S1, T1, D1],
  b: ModuleBlueprint[F, M2, S2, T2, D2],
)(using UniqueNames[S1 ++ S2], ValueOf[M1], ValueOf[M2]): ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2] =
  new ModuleBlueprint[F, MOut, S1 ++ S2, T1 ++ T2, D1 ++ D2](
    tables   = tupleConcat(a.tables, b.tables),
    reducer0 = new RoutedStateReducer0[F, S1 ++ S2]:
      // Type bound T <: Tx & ModuleRoutedTx ensures compile-time safety
      def apply[T <: Tx & ModuleRoutedTx](tx: T)(using Requires[T#Reads, S1 ++ S2], Requires[T#Writes, S1 ++ S2]) =
        // moduleId.path is ALWAYS module-relative (MName *: SubPath)
        // It is NEVER prepended with the mount path
        // Full paths (mountPath ++ moduleId.path) are only constructed at edges for telemetry
        val pathHead = tx.moduleId.path.head.asInstanceOf[String]
        if pathHead == valueOf[M1] then a.reducer0.apply(tx)
        else if pathHead == valueOf[M2] then b.reducer0.apply(tx)
        else sys.error(s"TxRouteMissing: $pathHead ∉ {${valueOf[M1]}, ${valueOf[M2]}}")
    ,
    txs      = a.txs.combine(b.txs),
    deps     = tupleConcat(a.deps, b.deps),
  )

// 베이스 경로 아래 하위 경로로 장착 (Base ++ Sub)
extension [F[_], MName <: String, S <: Tuple, T <: Tuple, D <: Tuple]
  def mountAt[Base <: Tuple, Sub <: Tuple](bp: ModuleBlueprint[F, MName, S, T, D])
      (using PrefixFreePath[Base ++ Sub, S])
      : StateModule[F, Base ++ Sub, S, T, D] =
    mount[Base ++ Sub](bp)
```

### Aggregator Examples: Shared vs Sandboxed
```scala
// 블루프린트 정의 (Path-비의존)
val AccountsBP: ModuleBlueprint[F, "accounts", AccountsSchema, AccountsTxs, EmptyTuple] = ???
val GroupBP   : ModuleBlueprint[F, "group"   , GroupSchema   , GroupTxs   , EmptyTuple] = ??? // Requires[AccountsSchema, _]
val TokenBP   : ModuleBlueprint[F, "token"   , TokenSchema   , TokenTxs   , EmptyTuple] = ??? // Requires[AccountsSchema, _]

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
- `++` 별칭은 `Tuple.Concat`의 얕은(flat) 결합을 의도한다. 런타임에서도 `tupleConcat`을 사용해 동일한 평탄 구조를 유지해야 `Tables`, `Deps` 등 타입 수준 정보와 일치한다.
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
- 권장 인코딩: 경로(Path) 세그먼트와 테이블 이름을 모두 길이 접두(length‑prefix) + 구분자(sentinel, 예: 0x00)로 인코딩.
  - 예: `encodePath[("app", "accounts")] ++ encodeSegment["balances"]`
  - 길이 접두만으로도 prefix‑free가 성립하며, 구분자는 디버깅/가독성 향상에 유용.
- 대안: 각 세그먼트/테이블 이름을 고정 길이 해시(예: Keccak256)로 치환 후 결합(사전 정의된 충돌 위험 허용 시).
- 검증 범위: 모듈 내부 + 의존/집합 결합된 모든 테이블 집합에 대해 전역 prefix‑free 검사.

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

def extend[F[_], Path <: Tuple, S1 <: Tuple, S2 <: Tuple, T1 <: Tuple, T2 <: Tuple, D1 <: Tuple, D2 <: Tuple](
  a: StateModule[F, Path, S1, T1, D1],
  b: StateModule[F, Path, S2, T2, D2],
)(using
  UniqueNames[S1 ++ S2],
  PrefixFreePath[Path, S1 ++ S2],
): StateModule[F, Path, S1 ++ S2, T1 ++ T2, (D1, D2)] =
  new StateModule(
    tables  = mergeTables(a.tables, b.tables),
    reducer = mergeReducers(a.reducer, b.reducer),
    txs     = mergeTxs(a.txs, b.txs),
    deps    = (a.deps, b.deps),
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

// 집합 결합(aggregate): 팩토리로 모듈을 Path-매개화하여 동일 Path로 빌드 후 extend로 결합
// blueprint-first를 선호한다면: 여러 blueprint를 composeBlueprint로 합친 뒤 최종적으로 한 번 mount.
trait ModuleFactory[F[_], S <: Tuple, T <: Tuple]:
  def build[Path <: Tuple]: StateModule[F, Path, S, T, EmptyTuple]

def aggregate[F[_], S1 <: Tuple, S2 <: Tuple, T1 <: Tuple, T2 <: Tuple](
  m1: ModuleFactory[F, S1, T1],
  m2: ModuleFactory[F, S2, T2],
): ModuleFactory[F, S1 ++ S2, T1 ++ T2] = new:
  def build[Path <: Tuple] = extend(m1.build[Path], m2.build[Path])
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

## Compile-Time Checks (권장)
- ByteCodec 존재: 모든 `Entry[Name, K, V]`에 `ByteCodec[K]`, `ByteCodec[V]` 필요.
- UniqueNames: 모듈 스키마(및 의존 모듈 포함) 내 테이블 이름 중복 금지.
- PrefixFreePath(Path, Schema): `encodePath(Path) ++ encodeSegment(Name)` 바이트(길이 접두) 기준 동일/접두어-관계 금지.
- OrderedCodec[K]: 범위/사전식 스트림을 쓰는 테이블의 키 인코딩은 순서 보존을 증명.
- Requires: 각 `Tx`의 `Reads`/`Writes` ⊆ 모듈 스키마 증거 필요.
- DAppStateTag: DApp 상태를 소비하는 API는 `using DAppStateTag[S]` 요구.
- Dependency DAG: 모듈 의존 관계는 비순환, 결합 시 UniqueNames/PrefixFree 유지.
- FixedSize[K, N]: 고정 길이 키 요구 테이블에 크기 불변을 강제.
- Proof Coupling: `MerkleProof[Name, K]`에 테이블/키 타입 팬텀을 부여해 교차 사용 방지.
- Effect Stack: 테이블/리듀서는 고정 스택(`StateT[EitherT[...]]`)을 사용, NodeStore 증거 없인 호출 불가.

## Open Questions
- PrefixFree 증거 합성을 어떻게 간결하고 자동으로 만들 것인가?(의존/결합 시)
- OrderedCodec/FixedSize 같은 특성 증거를 어디까지 자동 유도할 것인가?
- AccessLog 포맷과 크기를 어떻게 제어할 것인가?(블록 단위/샤딩)
- 모듈 간 교차 트랜잭션의 증명(Proof)을 어떤 경계에서 생성/검증할 것인가?

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
  - `composeBlueprint` (schema/tx/deps union)
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

Phase 5 — Assembly (PARTIAL: Core Patterns Proven, Advanced Features Experimental)
- **Status**: Mount-then-extend pattern is production-ready; ModuleFactory/aggregate remain experimental
- **Production-Ready Core**
  - ✅ `extend`: merge two StateModules at same Path (Module.scala:246-275)
    - **Fully functional**: merges modules mounted at same path
    - Combines schemas (S1 ++ S2), transactions (T1 ++ T2), dependencies ((D1, D2))
    - Tests verify 4 combined tables from 2 modules
    - **Requirement**: Both modules must already be mounted at the same path
  - ✅ `mergeReducers`: error-based fallback with transaction execution tests (Module.scala:296-356)
    - **Strategy**: try r1 first; if r1 fails (Left) → try r2 as fallback
    - **Fixed**: r1 succeeds with 0 events → no fallback (query-only transactions work)
    - Tests: r1 succeeds → CreateAccount → AccountCreated event verified
    - Tests: r1 fails → r2 succeeds → CreateGroup → GroupCreated event verified
    - Tests: r1 succeeds with empty events → r2 NOT called (verified with flag)
    - **Limitation**: May attempt both reducers for unhandled transactions (use ModuleRoutedTx for explicit routing)
  - ✅ Shared vs Sandboxed assembly examples in Phase5Spec
    - Shared: single mount, Lookup-based access (Phase 4 pattern)
    - Sandboxed: multiple mounts at different paths, verified isolation
- **Experimental/Limited Features** (NOT Production-Ready)
  - ⚠️ `ModuleFactory`: **LIMITED** - safe signature but limited use cases (Module.scala:390-421)
    - ✅ **Fixed**: fromBlueprint now requires `Deps = EmptyTuple` (compile-time enforcement)
    - ✅ Prevents factory creation from blueprints with cross-module dependencies
    - ✅ Safe for: self-contained modules, sandboxed deployment (multiple paths)
    - ⚠️ **Limitation**: Useful only for modules without Phase 4 Lookup dependencies
    - **Recommendation**: Use direct mount or composeBlueprint for dependent modules
  - ❌ `aggregate`: **UNSAFE - DO NOT USE** - fabricates evidence via casts (Module.scala:423-493)
    - ❌ Manufactures SchemaMapper/PrefixFreePath/UniqueNames via `asInstanceOf`
    - ❌ If factories reuse table names, compiles but extend fails at runtime
    - ❌ Only works at same path (defeats purpose of "aggregating independent modules")
    - ❌ Test only checks non-null (Phase5Spec:204-229), doesn't exercise unsafe paths
    - **Status**: Should be internal/experimental-only or require explicit evidence from caller
    - **BLOCKER**: Requires proper subset derivation (SchemaMapper[S1] from SchemaMapper[S1++S2])
    - **ALTERNATIVE**: Use proven mount → extend pattern instead
- What Actually Works (Mount-then-extend Pattern)
  - ✅ Mount two blueprints independently at same path
  - ✅ Use extend to merge them: schemas (S1 ++ S2), transactions (T1 ++ T2), dependencies
  - ✅ Execute transactions through merged reducer (CreateAccount, CreateGroup tested)
  - ✅ **Fallback routing FIXED**: r1 fails (Left) → r2 executes (error-based, not empty-events)
  - ✅ **Empty events handling**: r1 succeeds with 0 events → no fallback to r2 (verified)
  - ✅ Shared assembly: mount once, other modules access via Phase 4 Lookup
  - ✅ Isolated assembly: mount same blueprint at different paths for sandboxing
  - ✅ Tests verify table counts, isolation, and transaction execution
- What Remains Limited
  - ⚠️ aggregate: type-checks but uses unsafe casts (deferred, use extend instead)
  - ⚠️ mergeReducers: error-based fallback may attempt both reducers for unhandled transactions
    - **Fixed**: no longer breaks on empty events (query-only transactions work)
    - **Limitation**: both reducers may be attempted, causing duplicate work
    - **Production alternative**: Use ModuleRoutedTx for explicit routing
- Production Recommendation (per Assembly Flow Guidance)
  - **Prefer**: composeBlueprint (Phase 3) → mount (Phase 2)
    - Single composition, single mount, all evidence derived correctly
  - **Alternative**: mount each blueprint → extend for post-deployment merge
    - Works when modules are independently mounted at same path
    - Proven with transaction execution tests (Phase5Spec)
  - **Avoid**: aggregate (EXPERIMENTAL, unsafe casts, not independently usable)
  - **Avoid**: ModuleFactory for modules with cross-module dependencies
- Optional Future Enhancements (not required for Phase 5)
  1. ✅ ~~Transaction execution tests for mergeReducers~~ (COMPLETED)
     - ✅ r1 succeeds → CreateAccount → AccountCreated event verified
     - ✅ r1 fails → r2 succeeds → CreateGroup → GroupCreated event verified
     - ✅ r1 succeeds with empty events → no fallback to r2 (verified with flag)
  2. ✅ ~~Dependencies in ModuleFactory~~ (FIXED with compile-time enforcement)
     - ✅ fromBlueprint now requires `Deps = EmptyTuple` (signature changed)
     - ✅ Blueprints with dependencies cannot become factories (won't compile)
     - ✅ Safe for self-contained modules only
  3. ✅ ~~mergeReducers fallback strategy~~ (FIXED - error-based, not empty-events)
     - ✅ Changed from "empty events = unhandled" to "Left = failed, try r2"
     - ✅ Allows legitimate empty-event transactions (queries, no-op operations)
     - ✅ Comprehensive tests cover all three scenarios
  4. Proper evidence derivation for aggregate (deferred - use extend instead)
     - Derive SchemaMapper[F, Path, S1] from SchemaMapper[F, Path, S1 ++ S2]
     - Derive PrefixFreePath[Path, S1] from PrefixFreePath[Path, S1 ++ S2]
     - Remove unsafe casts
     - **Status**: Kept experimental, recommend hiding or gating behind explicit preconditions
  5. Reducer registry pattern (future enhancement - current fallback works)
     - Replace error-based fallback with explicit transaction-to-reducer mapping
     - Use ModuleRoutedTx for explicit routing (eliminates duplicate work)
  6. AccessLog integration (deferred to Phase 8)

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

Phase 7 — Law & Property Tests
- Deliverables
  - OrderedCodec law checks
  - Prefix-free validator coverage
- Tasks
  - Add property tests for OrderedCodec and encoded path ordering where needed
  - Fuzz tests for prefix encoding and composition
- Criteria
  - Laws hold across supported primitives and demo types

Phase 8 — AccessLog & Conflicts
- Deliverables
  - `AccessLog` accumulation and simple conflict predicates (W∩W, R∩W)
- Tasks
  - Integrate logging into reducers; add options for Bloom/roaring for reads
  - Size caps and metrics surfaces
- Criteria
  - Conflicts detected on crafted overlapping txs; memory bounded by policy

## References
- 구현 레퍼런스: `modules/core/shared/src/main/scala/org/sigilaris/core/merkle/MerkleTrie.scala`
- 기존 ADR들과 성능/코덱 규약: `docs/adr/0001-0008*`, `docs/perf/criteria.md`
- ADR‑0010(Accounts): `docs/adr/0010-blockchain-account-model-and-key-management.md`
- ADR‑0011(Group): `docs/adr/0011-blockchain-account-group-management.md`
