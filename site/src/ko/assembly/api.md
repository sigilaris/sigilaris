# Assembly DSL API 참조

[← 개요](README.md) | [English →](../../en/assembly/api.md)

---

## BlueprintDsl

특정 경로에 블루프린트를 마운트하는 고수준 DSL입니다.

### 시그니처

```scala
object BlueprintDsl:
  def mount[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Name, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, (Name,), Owns, Needs, Txs, StateReducer[F, (Name,), Owns, Needs]]
  
  def mountAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Path, ModuleBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, Path, Owns, Needs, Txs, StateReducer[F, Path, Owns, Needs]]
  
  def mountComposed[F[_], Name <: String & Singleton, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Name, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, (Name,), Owns, Needs, Txs, RoutedStateReducer[F, (Name,), Owns, Needs]]
  
  def mountComposedAtPath[F[_], Path <: Tuple, MName <: String, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple](
    binding: (Path, ComposedBlueprint[F, MName, Owns, Needs, Txs])
  )(using ...): StateModule[F, Path, Owns, Needs, Txs, RoutedStateReducer[F, Path, Owns, Needs]]
```

### 동작

**mount**: ModuleBlueprint를 단일 세그먼트 경로에 마운트합니다. 경로 이름은 튜플에서 추출되어 모듈의 배포 경로의 첫 번째 세그먼트가 됩니다.

**mountAtPath**: ModuleBlueprint를 임의의 다중 세그먼트 경로에 마운트합니다. 중첩된 모듈 계층에 유용합니다.

**mountComposed**: ComposedBlueprint(`Blueprint.composeBlueprint`를 통해 생성)를 단일 세그먼트 경로에 마운트합니다. 결과 모듈은 `moduleId.path`를 기반으로 트랜잭션을 라우팅하는 RoutedStateReducer를 사용합니다.

**mountComposedAtPath**: ComposedBlueprint를 다중 세그먼트 경로에 마운트합니다.

모든 메서드는 자동으로 다음 증거를 요구합니다:
- `Monad[F]`: 이펙트 타입이 모나드여야 함
- `PrefixFreePath[Path, Owns]`: 경로 + 스키마가 prefix-free 바이트 접두어를 생성
- `NodeStore[F]`: MerkleTrie 저장소 백엔드
- `SchemaMapper[F, Path, Owns]`: 스키마로부터 테이블 인스턴스화 가능

### 예제

```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.application.module.blueprint.ModuleBlueprint
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given

// 단일 세그먼트에 마운트
def simpleBlueprint(): ModuleBlueprint[IO, "simple", EmptyTuple, EmptyTuple, EmptyTuple] = ???
// val module1 = mount("accounts" -> simpleBlueprint())

// 다중 세그먼트 경로에 마운트
// val module2 = mountAtPath(("app", "v1", "accounts") -> simpleBlueprint())
```

---

## EntrySyntax

타입 레벨 이름을 가진 Entry 인스턴스를 생성하는 문자열 인터폴레이터입니다.

### 시그니처

```scala
object EntrySyntax:
  extension (inline sc: StringContext)
    transparent inline def entry[K, V]: Entry[?, K, V]
```

### 동작

문자열 리터럴에서 컴파일 타임에 `Name`이 추출되는 `Entry[Name, K, V]`를 생성합니다. 매크로는 다음을 보장합니다:
- 보간된 문자열이 리터럴(표현식이 아님)
- 이름이 추출되고 타입 레벨에서 보존됨
- 키와 값 타입이 타입 매개변수를 통해 지정됨

### 예제

```scala mdoc:reset:silent
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// 타입 레벨 이름으로 Entry 생성
val accounts = entry"accounts"[Utf8, Utf8]
val balances = entry"balances"[Utf8, BigInt]

// 타입은 Entry["accounts", Utf8, Utf8]
val accountsType: Entry["accounts", Utf8, Utf8] = accounts

// 스키마로 조합
val schema = accounts *: balances *: EmptyTuple
```

```scala mdoc:compile-only
import org.sigilaris.core.assembly.EntrySyntax.*

// 컴파일 오류: 문자열 리터럴이 아님
// val name = "users"
// val entry = entry"$name"[Utf8, Int]
```

---

## TablesProviderOps

마운트된 모듈로부터 TablesProvider를 추출하는 확장 메서드입니다.

### 시그니처

```scala
object TablesProviderOps:
  extension [F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
    module: StateModule[F, Path, Owns, Needs, Txs, R]
  )
    def toTablesProvider: TablesProvider[F, Owns]
```

### 동작

마운트된 StateModule로부터 `TablesProvider[F, Owns]`를 추출합니다. 프로바이더는 모듈의 소유 테이블을 의존 모듈에 노출합니다. 이것이 모듈 간 의존성 주입의 주요 메커니즘입니다.

### 예제

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.ModuleBlueprint
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// 모듈 A가 테이블 제공
type ModuleASchema = Entry["accounts", Utf8, Utf8] *: EmptyTuple
def moduleABlueprint(): ModuleBlueprint[IO, "moduleA", ModuleASchema, EmptyTuple, EmptyTuple] = ???
// val moduleA = mount("moduleA" -> moduleABlueprint())
// val provider = moduleA.toTablesProvider

// 모듈 B가 A의 테이블에 의존
// def moduleBBlueprint(p: TablesProvider[IO, ModuleASchema]): ModuleBlueprint[IO, "moduleB", EmptyTuple, ModuleASchema, EmptyTuple] = ???
// val moduleB = mount("moduleB" -> moduleBBlueprint(provider))
```

---

## TablesAccessOps

타입 안전 테이블 접근을 위한 증거 유도 유틸리티입니다.

### 시그니처

```scala
object TablesAccessOps:
  def deriveLookup[Schema <: Tuple, Name <: String, K, V]: Lookup[Schema, Name, K, V]
  
  def deriveRequires[Needs <: Tuple, Schema <: Tuple]: Requires[Needs, Schema]
  
  extension [F[_], Schema <: Tuple](provider: TablesProvider[F, Schema])
    def providedTable[Name <: String, K, V](using Lookup[Schema, Name, K, V]): StateTable[F] { type Name = Name; type K = K; type V = V }
```

### 동작

**deriveLookup**: `Lookup[Schema, Name, K, V]` 증거를 유도하며, 이는 스키마에 지정된 이름과 타입을 가진 엔트리가 포함되어 있음을 증명합니다. 모듈 시스템에서 내부적으로 사용됩니다.

**deriveRequires**: `Requires[Needs, Schema]` 증거를 유도하며, 이는 `Needs`의 모든 엔트리가 `Schema`에 존재함을 증명합니다. 트랜잭션 요구사항 검증에 사용됩니다.

**providedTable**: 자동 증거 유도를 통해 프로바이더로부터 테이블에 접근하는 확장 메서드입니다. 정제된 타입을 가진 `StateTable[F]`를 반환합니다.

### 예제

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.TablesAccessOps.*
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

type MySchema = (
  Entry["accounts", Utf8, Utf8],
  Entry["balances", Utf8, BigInt],
)

// Lookup 증거 유도
// val lookup = deriveLookup[MySchema, "accounts", Utf8, Utf8]

// 확장 메서드로 테이블 접근
def accessTable(provider: TablesProvider[IO, MySchema]): Unit =
  // val accountsTable = provider.providedTable["accounts", Utf8, Utf8]
  ???
```

---

## PrefixFreeValidator

prefix-free 테이블 구성을 위한 런타임 검증입니다.

### 시그니처

```scala
object PrefixFreeValidator:
  sealed trait ValidationResult
  case object Valid extends ValidationResult
  case class PrefixCollision(prefix1: ByteVector, prefix2: ByteVector) extends ValidationResult
  case class IdenticalPrefixes(prefix: ByteVector, count: Int) extends ValidationResult
  
  def validateSchema[Path <: Tuple, Schema <: Tuple]: ValidationResult
  
  def validateWithNames(prefixes: List[(String, ByteVector)]): ValidationResult
```

### 동작

**validateSchema**: 주어진 경로 + 스키마 조합이 prefix-free 바이트 접두어를 생성하는지 컴파일 타임에 검증합니다. 모든 접두어가 구별되고 prefix-free이면 `Valid`를 반환합니다.

**validateWithNames**: 디버깅 정보를 포함한 런타임 검증입니다. (이름, 접두어) 쌍의 목록을 받아 충돌을 확인합니다. 테스트 및 스키마 구성 디버깅에 유용합니다.

**ValidationResult**: 검증 결과를 나타내는 ADT:
- `Valid`: 모든 접두어가 prefix-free
- `PrefixCollision`: 한 접두어가 다른 접두어의 접두사
- `IdenticalPrefixes`: 여러 테이블이 동일한 접두어를 가짐

### 예제

```scala mdoc:compile-only
import scodec.bits.ByteVector
import org.sigilaris.core.assembly.PrefixFreeValidator
import org.sigilaris.core.application.support.encoding.tablePrefix
import org.sigilaris.core.application.state.Entry

type MySchema = (
  Entry["accounts", String, String],
  Entry["balances", String, BigInt],
)

// 이름과 함께 런타임 검증
val prefixes = List(
  ("accounts", ByteVector(0x01)),
  ("balances", ByteVector(0x02))
)
val result = PrefixFreeValidator.validateWithNames(prefixes)

result match
  case PrefixFreeValidator.Valid =>
    println("스키마가 prefix-free입니다")
  case PrefixFreeValidator.PrefixCollision(prefix1, prefix2) =>
    println(s"충돌: ${prefix1}이 ${prefix2}의 접두사입니다")
  case PrefixFreeValidator.IdenticalPrefixes(prefix, count) =>
    println(s"중복 접두어: ${prefix}가 ${count}번 나타남")
```

---

## 헬퍼 함수

### tablePrefix

```scala
inline def tablePrefix[Path <: Tuple, Name <: String]: ByteVector
```

경로와 이름이 주어진 테이블의 바이트 레벨 접두어를 계산합니다. 각 경로 세그먼트에 길이 접두 인코딩을 사용합니다.

### encodePath

```scala
inline def encodePath[Path <: Tuple]: ByteVector
```

경로 세그먼트의 튜플을 길이 접두 인코딩을 사용하여 ByteVector로 인코딩합니다.

### encodeSegment

```scala
inline def encodeSegment[S <: String]: ByteVector
```

단일 경로 세그먼트(문자열 리터럴)를 길이 접두와 null 종료자로 인코딩합니다.

---

[← 개요](README.md) | [English →](../../en/assembly/api.md)
