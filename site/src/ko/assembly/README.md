# Assembly DSL

[← 메인](../../README.md) | [English →](../../en/assembly/README.md)

---

[API](api.md)

---

## 개요

Sigilaris assembly 패키지는 모듈형 블루프린트로부터 블록체인 애플리케이션을 구성하기 위한 고수준 DSL을 제공합니다. 컴파일 타임 검증과 타입 안전한 테이블 접근을 통해 배포 경로에 모듈을 마운트하는 편리한 도구를 제공합니다.

**왜 assembly 패키지가 필요한가?** 모듈형 블록체인 애플리케이션을 구축하려면 독립적인 모듈을 조합하고, 의존성을 연결하며, 모듈 경계를 넘어 타입 안전성을 보장해야 합니다. 이 패키지는 컴파일 타임 검증을 강제하면서 이러한 문제를 처리하는 인체공학적 DSL 메서드를 제공합니다.

**주요 기능:**
- **고수준 마운팅 DSL**: 경로에 블루프린트를 마운트하는 간단한 메서드
- **컴파일 타임 검증**: PrefixFreePath, UniqueNames, 스키마 검증
- **타입 안전 테이블 접근**: 테이블 조회를 위한 자동 증거 유도
- **의존성 주입**: 프로바이더 추출 및 연결
- **Entry 인터폴레이터**: 타입 안전한 Entry 생성을 위한 문자열 보간

## 빠른 시작 (30초)

```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.state.*
import org.sigilaris.core.application.transactions.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// entry 인터폴레이터로 스키마 정의
val accountsEntry = entry"accounts"[Utf8, Utf8]
val balancesEntry = entry"balances"[Utf8, BigInt]

// 간단한 블루프린트 생성 (플레이스홀더)
def createBlueprint(): ModuleBlueprint[IO, "myModule", (Entry["accounts", String, String], Entry["balances", String, BigInt]), EmptyTuple, EmptyTuple] = ???

// 단일 세그먼트 경로에 마운트
// val module = mount("myapp" -> createBlueprint())

// 의존 모듈을 위한 프로바이더 추출
// val provider = module.toTablesProvider
```

완료! assembly DSL은 자동으로:
- 경로와 스키마가 prefix-free인지 검증
- 경로로부터 테이블 접두어 계산
- 스키마 요구사항에 대한 증거 유도
- 모듈 간 의존성 활성화

## 문서

### 핵심 개념
- **[API 참조](api.md)**: BlueprintDsl, EntrySyntax, 프로바이더 유틸리티 상세 문서

### 주요 타입

#### BlueprintDsl
특정 경로에 블루프린트를 마운트하는 고수준 DSL:
- `mount`: 단일 세그먼트 경로에 ModuleBlueprint 마운트
- `mountAtPath`: 다중 세그먼트 경로에 ModuleBlueprint 마운트
- `mountComposed`: 단일 세그먼트 경로에 ComposedBlueprint 마운트
- `mountComposedAtPath`: 다중 세그먼트 경로에 ComposedBlueprint 마운트

모든 메서드는 자동으로 다음을 위한 증거를 요구합니다:
- `PrefixFreePath`: 경로 + 스키마가 prefix-free인지 검증
- `SchemaMapper`: Entry 명세로부터 테이블 인스턴스화
- `NodeStore`: MerkleTrie 저장소 백엔드 제공

#### EntrySyntax
Entry 인스턴스 생성을 위한 문자열 인터폴레이터:
```scala mdoc:reset:silent
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.state.Entry
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// 타입 레벨 이름으로 Entry 생성
val accounts = entry"accounts"[Utf8, BigInt]
val balances = entry"balances"[Utf8, BigInt]

// 스키마 튜플로 조합
val schema = accounts *: balances *: EmptyTuple
```

`entry` 인터폴레이터는 테이블 이름이 문자열 리터럴(표현식이 아님)임을 보장하고 스키마 검증을 위한 타입 레벨 정보를 보존합니다.

#### TablesProviderOps
마운트된 모듈로부터 프로바이더를 추출하는 확장 메서드:
```scala mdoc:reset:silent
import cats.effect.IO
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.application.module.provider.TablesProvider

// 모듈로부터 프로바이더 추출 (플레이스홀더)
def extractProvider[F[_], Path <: Tuple, Owns <: Tuple, Needs <: Tuple, Txs <: Tuple, R](
  module: StateModule[F, Path, Owns, Needs, Txs, R]
): TablesProvider[F, Owns] = module.toTablesProvider
```

#### TablesAccessOps
타입 안전 테이블 접근을 위한 증거 유도 유틸리티:
- `deriveLookup`: 테이블 접근을 위한 Lookup 증거 유도
- `deriveRequires`: 스키마 검증을 위한 Requires 증거 유도
- `providedTable`: 자동 증거를 통한 직접 테이블 접근 확장 메서드

#### PrefixFreeValidator
prefix-free 테이블 구성을 위한 런타임 검증:
- `validateSchema`: 컴파일 타임 검증
- `validateWithNames`: 디버깅 정보를 포함한 런타임 검증

## 사용 사례

### 간단한 애플리케이션 구축

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.EntrySyntax.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.state.*
import org.sigilaris.core.codec.byte.ByteCodec.given
import org.sigilaris.core.datatype.Utf8

// 스키마 정의
val accountsEntry = entry"accounts"[Utf8, Utf8]
val balancesEntry = entry"balances"[Utf8, BigInt]
val accountsSchema = accountsEntry *: EmptyTuple
val balancesSchema = balancesEntry *: EmptyTuple

// 블루프린트 생성 (플레이스홀더)
type AccountsSchema = Entry["accounts", Utf8, Utf8] *: EmptyTuple
type BalancesSchema = Entry["balances", Utf8, BigInt] *: EmptyTuple
def accountsBP(): ModuleBlueprint[IO, "accounts", AccountsSchema, EmptyTuple, EmptyTuple] = ???
def balancesBP(): ModuleBlueprint[IO, "balances", BalancesSchema, EmptyTuple, EmptyTuple] = ???

// 경로에 모듈 마운트
// val accountsModule = mount("accounts" -> accountsBP())
// val balancesModule = mount("balances" -> balancesBP())
```

### 의존성 연결

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.application.module.blueprint.*
import org.sigilaris.core.application.module.provider.TablesProvider

// 모듈 A가 테이블 제공 (플레이스홀더)
def createModuleA(): ModuleBlueprint[IO, "moduleA", EmptyTuple, EmptyTuple, EmptyTuple] = ???
// val moduleA = mount("moduleA" -> createModuleA())
// val providerA = moduleA.toTablesProvider

// 모듈 B가 A의 테이블에 의존 (플레이스홀더)
def createModuleB(provider: TablesProvider[IO, EmptyTuple]): ModuleBlueprint[IO, "moduleB", EmptyTuple, EmptyTuple, EmptyTuple] = ???
// val moduleB = mount("moduleB" -> createModuleB(providerA))
```

### 블루프린트 조합

```scala mdoc:compile-only
import cats.effect.IO
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.application.module.blueprint.{Blueprint, ModuleBlueprint}

// 두 독립 모듈 조합 (플레이스홀더)
def blueprint1(): ModuleBlueprint[IO, "mod1", EmptyTuple, EmptyTuple, EmptyTuple] = ???
def blueprint2(): ModuleBlueprint[IO, "mod2", EmptyTuple, EmptyTuple, EmptyTuple] = ???

// val composed = Blueprint.composeBlueprint[IO, "app"](blueprint1(), blueprint2())
// val composedModule = mountComposed("app" -> composed)
```

## 타입 규칙

### Path 타입
경로는 문자열 리터럴의 튜플 타입으로 표현됩니다:
- 단일 세그먼트: `"accounts"`는 타입 레벨에서 `("accounts",)`가 됨
- 다중 세그먼트: `("app", "v1", "accounts")`

### Schema 타입
스키마는 Entry 타입의 튜플입니다:
```scala mdoc:reset:silent
import org.sigilaris.core.application.state.Entry

type MySchema = (
  Entry["accounts", String, String],
  Entry["balances", String, BigInt],
)
```

### 증거 타입
assembly DSL은 자동으로 유도합니다:
- `UniqueNames[Schema]`: 스키마 내 테이블 이름이 고유함
- `PrefixFreePath[Path, Schema]`: 경로 + 스키마가 prefix-free 바이트 접두어를 생성
- `SchemaMapper[F, Path, Schema]`: Entry 명세로부터 테이블 인스턴스화 가능

## 설계 원칙

**타입 안전성**: 모든 검증이 컴파일 타임에 발생합니다. 잘못된 구성(중복 이름, prefix-free가 아닌 스키마)은 컴파일러에 의해 거부됩니다.

**인체공학**: DSL 메서드는 일반적인 작업에 간결한 구문을 제공합니다. 임포트 기반 기능 활성화로 API를 깔끔하게 유지합니다.

**조합성**: 메서드가 자연스럽게 조합됩니다. 프로바이더 추출은 의존성 주입 패턴을 가능하게 합니다.

**제로 비용**: 타입 레벨 추상화는 효율적인 런타임 코드로 컴파일됩니다. 리플렉션이나 런타임 검증 오버헤드가 없습니다.

## 다음 단계

- **[API 참조](api.md)**: 상세 API 문서
- **[Application 모듈](../application/README.md)**: 기본 모듈 시스템
- **[ADR-0009](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)**: 아키텍처 결정 기록

## 제한사항

- Entry 인터폴레이터는 문자열 리터럴(표현식이 아님)을 요구
- 증거 유도는 복잡한 스키마에 대해 명시적 타입 주석이 필요할 수 있음
- 조합된 블루프린트는 ModuleRoutedTx 트랜잭션을 요구

## 참고 자료

- [Typelevel 문서](https://typelevel.org/)
- [Cats Effect](https://typelevel.org/cats-effect/)
- [ADR-0009: 블록체인 애플리케이션 아키텍처](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)

---

© 2025 Sigilaris. All rights reserved.
