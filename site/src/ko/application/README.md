# Application Module

[← 메인](../../README.md) | [English →](../../en/application/README.md)

---

[API](api.md)

---

## 개요

Sigilaris application 모듈은 컴파일 타임 스키마 검증, 타입 안전한 테이블 접근, 자동 의존성 주입을 통해 모듈형 블록체인 애플리케이션을 구축하기 위한 핵심 추상화를 제공합니다. ADR-0009에 설명된 블루프린트 기반 아키텍처를 구현합니다.

**왜 application 모듈이 필요한가?** 블록체인 애플리케이션은 다양한 기능이 독립적으로 개발, 테스트, 조합될 수 있는 모듈형 상태 관리가 필요합니다. 이 모듈은 유연한 조합 패턴을 가능하게 하면서 컴파일 타임에 정확성을 보장하는 타입 레벨 장치를 제공합니다.

**주요 기능:**
- **2단계 아키텍처**: 경로 독립 블루프린트 → 경로 바인딩 런타임 모듈
- **컴파일 타임 검증**: 스키마 요구사항, 고유성, prefix-free 제약
- **타입 안전 의존성**: 모듈 간 테이블 접근을 위한 프로바이더 시스템
- **유연한 조합**: 배포 시점에 독립 모듈 결합
- **제로 비용 추상화**: 런타임에 제거되는 타입 레벨 증거

## 아키텍처: Blueprint → Module

application 모듈은 ADR-0009에서 영감을 받은 **2단계 설계**를 따릅니다:

### 1단계: Blueprint (경로 독립)

블루프린트는 다음을 정의하는 **배포 독립적 명세**입니다:
- `Owns`: 이 모듈이 생성하고 소유하는 테이블
- `Needs`: 이 모듈이 다른 모듈로부터 요구하는 테이블
- `StateReducer0`: `Owns ++ Needs`에서 작동하는 트랜잭션 로직
- `TxRegistry`: 등록된 트랜잭션 타입

**핵심 통찰**: 블루프린트는 배포 위치를 모릅니다. 이는 다음을 가능하게 합니다:
- **재사용성**: 동일한 블루프린트를 다른 경로에 배포
- **테스트 가능성**: 배포 고려 없이 단위 테스트
- **조합 가능성**: 배포 전에 블루프린트 결합

### 2단계: Module (경로 바인딩)

블루프린트를 특정 `Path`에 마운팅하면 **StateModule**이 생성됩니다:
- 테이블 접두어 계산: `encodePath(Path) ++ tableName`
- 각 Entry에 대한 `StateTable[F]` 인스턴스 생성
- 구체적인 테이블 구현으로 리듀서 바인딩
- 마운트 시점에 prefix-free 속성 검증

**예시**:
```scala
val blueprint: ModuleBlueprint[IO, "accounts", ...] = ...

// 다른 경로에 배포
val mainAccounts = blueprint.mount[("app", "v1", "accounts")]
val testAccounts = blueprint.mount[("test", "accounts")]  // 테스트용 격리
```

### 왜 2단계인가?

1. **관심사의 분리**: 로직(블루프린트) vs 배포(모듈)
2. **타입 안전성**: 경로가 모듈 타입의 일부가 되어 접두어 검사 가능
3. **유연성**: 동일 블루프린트, 다중 배포
4. **제로 비용**: 모든 경로 계산이 컴파일 타임에 수행

## 주요 타입

### Blueprint (경로 독립)

**ModuleBlueprint**: 소유 및 필요 테이블을 가진 단일 모듈 명세

**ComposedBlueprint**: 라우팅과 함께 결합된 다중 모듈

### StateModule (경로 바인딩)

특정 경로에 인스턴스화된 테이블을 가진 런타임 모듈

### 상태 타입

**StoreF**: EitherT와 StateT를 결합한 효과적인 상태 모나드

**StoreState**: 접근 로깅과 함께 MerkleTrie 상태 결합

**AccessLog**: 병렬 실행 분석을 위한 테이블 레벨 작업 기록

### 트랜잭션 모델

**Tx**: 모든 트랜잭션의 기본 트레이트

**ModuleRoutedTx**: 모듈 상대 라우팅을 가진 트랜잭션

### 의존성 시스템

**TablesProvider**: 의존 모듈에 외부 테이블 제공

**Requires**: 트랜잭션 요구사항이 충족되었다는 컴파일 타임 증거

## 실제 사용 예시

### 실제 예시: Accounts 모듈 (ADR-0010)

ADR-0010의 Accounts 모듈은 완전한 블루프린트 구현을 보여줍니다. 이 모듈은 Named Account(이름 기반 계정)와 키 관리를 처리하며, 가디언을 통한 키 복구 메커니즘을 제공합니다.

**AccountsBP의 핵심 특징**:
- `Needs = EmptyTuple`: 외부 의존성 없음
- 트랜잭션: `CreateNamedAccount`, `AddKeyIds`, `RemoveKeyIds`, `UpdateAccount`
- 상태: `accountInfo` (계정 메타데이터), `nameKey` (키 등록 정보)

### 모듈 의존성: Group 모듈 (ADR-0011)

Group 모듈은 코디네이터 검증을 위해 Accounts에 의존합니다. 이는 `TablesProvider`를 통한 의존성 주입의 실제 예시입니다.

**GroupBP의 핵심 특징**:
- `Needs`: Accounts의 `accountInfo` 테이블
- `Owns`: `groupData` (그룹 메타데이터), `groupMember` (멤버십)
- 트랜잭션: `CreateGroup`, `AddAccounts`, `RemoveAccounts`, `DisbandGroup`
- 코디네이터가 유효한 계정인지 `accountInfo`에서 검증

### 배포 패턴

**패턴 1: 공유 Accounts** (두 모듈이 동일한 인스턴스 사용)
```scala
val accountsModule = accountsBP.mount[("app", "shared")]
val groupModule = groupBP.mount[("app", "group")]
  // accountsModule.tables를 provider로 주입
```

**패턴 2: 샌드박스 Accounts** (그룹이 격리된 계정 인스턴스 보유)
```scala
val groupAccounts = accountsBP.mount[("app", "group", "accounts")]
val groupModule = groupBP.mount[("app", "group")]
  // groupAccounts.tables를 provider로 주입
```

**핵심 통찰**: 동일한 `accountsBP` 블루프린트를 여러 경로에 배포하여 공유 또는 격리된 상태를 만들 수 있습니다.

## 타입 규칙

### Schema 타입
스키마는 Entry 타입의 튜플입니다.

### Owns vs Needs
- **Owns**: 이 모듈이 생성하고 관리하는 테이블
- **Needs**: 이 모듈이 다른 모듈로부터 읽는 테이블
- 결합 스키마 `Owns ++ Needs`가 트랜잭션이 작동하는 대상

### Path 타입
경로는 배포 위치를 나타내는 문자열 리터럴의 튜플입니다.

## 설계 원칙

**Blueprint 단계**: 모듈은 배포 경로를 모릅니다. 이는 재사용성과 테스트 독립성을 가능하게 합니다.

**Runtime 단계**: 마운팅은 블루프린트를 경로에 바인딩하여 테이블 접두어를 계산하고 StateTable 인스턴스를 인스턴스화합니다.

**타입 안전성**: 모든 스키마 요구사항이 컴파일 타임에 검증됩니다. 누락된 테이블, 중복 이름, prefix-free가 아닌 스키마는 컴파일러에 의해 거부됩니다.

**의존성 주입**: TablesProvider는 모듈 정의와 의존성 연결 사이의 깔끔한 분리를 가능하게 합니다.

## 다음 단계

- **[API 참조](api.md)**: 상세 API 문서
- **[Assembly DSL](../assembly/README.md)**: 고수준 마운팅 유틸리티
- **[ADR-0009](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)**: 아키텍처 결정 기록

## 제한사항

- 조합된 블루프린트는 ModuleRoutedTx 트랜잭션을 요구
- 프로바이더 축소는 복잡한 경우 명시적 TablesProjection 증거 필요
- 스키마 인스턴스화는 SchemaMapper 유도 필요

## 참고 자료

- [Typelevel 문서](https://typelevel.org/)
- [Cats Effect](https://typelevel.org/cats-effect/)
- [ADR-0009: 블록체인 애플리케이션 아키텍처](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0009-blockchain-application-architecture.md)
- [ADR-0010: 블록체인 계정 모델 및 키 관리](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0010-blockchain-account-model-and-key-management.md)

---

© 2025 Sigilaris. All rights reserved.
