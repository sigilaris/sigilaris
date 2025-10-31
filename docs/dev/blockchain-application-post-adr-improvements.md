# ADR 0009~0012 후속 개선 제안

이 문서는 ADR 0009(블록체인 애플리케이션 아키텍처)와 ADR 0010~0012(계정/그룹/서명 요구 사항) 구현 이후, 사용자 친화성과 타입 세이프티를 강화하고 중복을 줄이기 위한 후속 개선 항목을 제안한다. 제안 항목은 모듈 저자와 애플리케이션 조립자가 모두 체감할 수 있는 개발 경험 개선을 목표로 한다.

## 우선순위 요약
- **P0**: 트랜잭션 서명/권한 검증 공통화, `TablesProvider` 자동 주입 도구 정비
- **P1**: 블루프린트/모듈 조립 DSL 제공, 테스트/샘플 코드 정리
- **P2**: 패키지 구조 일원화, 문서 및 가이드 확장, 빌더/도메인 타입 디폴트 제공

## 추천 워크스트림

### 1. API 사용성 개선
- 블루프린트 합성·장착 DSL: `BlueprintDsl.mount("app" -> AccountsBP, "group" -> GroupBP)`와 같이 경로 지정과 증거 소환을 자동화하는 빌더를 제공해 반복되는 `summonInline`/`Tuple.Concat` 호출을 제거한다.
- `Signed` 트랜잭션 헬퍼: ADR 0012에 맞춰 `SignedTxBuilder`(테스트/프로덕션 공용)를 `modules/core/shared/src/main/scala/org/sigilaris/core/application/support` 계층에 추가해 반복되는 `Hash`/`Sign` 호출과 예외 래핑을 캡슐화한다.
- 모듈 실행 파사드: `StateModuleExecutor.run(state, Signed(tx))(using module)` 형태의 고수준 API를 추가해 호출부가 `StateT.run`/`EitherT.value`를 직접 다루지 않도록 한다.
- 스키마 선언 단축: `Entry` 생성 시 이름을 문자열 리터럴로 반복 기입하는 패턴을 줄이기 위해 `entry"accounts"[Utf8, AccountInfo]` 스타일 매크로나 헬퍼를 도입한다.

### 2. 타입 세이프티 및 증거 자동화
#### 2.1 `TablesProvider` 자동 유도
- 목표: 장착된 모듈이 보유한 테이블 메타 정보를 바탕으로 `TablesProvider` 인스턴스를 자동 생성해 의존 모듈 주입 시 타입 미스매치 가능성을 제거한다.
- 구현: `TablesProvider.fromModule(module)` 헬퍼를 `core.application.support` 계층에 추가하고, 모듈 블루프린트 등록 시 테이블 레지스트리를 노출하도록 공용 인터페이스를 확정한다. 내부적으로는 `module.tables` 기반의 주어진 증거를 재활용하되, 미노출 테이블에 대해서는 컴파일 타임 오류를 유도한다.
- 후속 작업: Group/Accounts 등 의존 모듈 진입부를 `TablesProvider.fromModule`로 교체하고, 조립 DSL(`BlueprintDsl.mount`)에서 신규 헬퍼를 기본값으로 사용하는 테스트를 추가한다.

#### 2.2 Needs ≠ EmptyTuple 조합 지원
- 목표: 의존 모듈이 존재하는 블루프린트 합성 시 `Needs =:= EmptyTuple` 제약으로 막혀 있던 경로를 열어 다양한 모듈 확장 조합을 허용한다.
- 구현: `TablesProvider.merge[A, B]` 증거를 `Tuple.Concat`/`Tuple.Union` 기반으로 재도입하고, 충돌 시 `CompileTimeError`를 포함한 매크로 진단을 제공한다. 조립 DSL에서는 merge 경로를 기본으로 사용해 의존 관계가 있는 모듈도 동일한 API로 장착된다.
- 검증: Accounts+Group 조합, Group 확장 블루프린트 등 최소 두 가지 합성 시나리오에 대한 단위 테스트와 스칼라메타 기반 컴파일 타임 테스트를 작성한다.

#### 2.3 `Requires` / `Lookup` 파생기
- 목표: 각 모듈에서 반복되는 `given Lookup[Key, Table]` 및 `given Requires[Module, Dependency]` 선언을 자동으로 생성해 템플릿 코드와 파생 누락 위험을 줄인다.
- 구현: `LookupAuto.derive` 인라인 매크로를 제공해 `SummonFrom`을 활용한 재귀 파생을 수행하고, 실패 시 필요한 증거 타입을 명시하는 에러 메시지를 반환한다. `RequiresAuto`는 모듈 메타를 순회해 의존 항목을 수집한 뒤 `ProductOf` 기반으로 합성한다.
- 통합: 기존 모듈의 수동 `given` 정의를 제거하고 자동 파생으로 대체한다. 해당 변경은 규칙 테스트(`LookupLaws`, `RequiresLaws`)로 회귀 검증한다.

#### 2.4 이벤트/결과 타입 브랜드화
- 목표: 동일한 `Event`/`Result` 서브타입이 모듈 간 공유될 때 발생하는 혼동을 방지하고, 트랜잭션 경로 추적을 용이하게 한다.
- 구현: 각 모듈의 트랜잭션 패키지에 브랜드 타입 별칭(`type Event = AccountsEvent[Underlier]`)을 도입하고, 공용 인터페이스에는 브랜드가 유지되도록 `opaque type` 또는 새로운 래퍼를 적용한다.
- 영향: 이벤트 버스 필터링, 결과 매핑 등 후속 처리 경로에서 모듈 브랜드를 활용할 수 있도록 API 문서를 보강하고, 교차 모듈 시나리오에 대한 골든 테스트를 추가한다.

### 3. 중복 제거 및 공통 모듈화
- 서명 검증 공통 모듈: `modules/core/shared/.../application/security/SignatureVerifier.scala`를 추가해 `AccountsReducer`(파일 경로: `.../AccountsBlueprint.scala`)에 분산된 서명 검증 로직을 재사용 가능하게 만든다. `GroupReducer` 등 차후 모듈도 동일 API를 사용하고, 토큰 시나리오는 별도 애플리케이션 가이드에서 이 헬퍼를 호출하는 예제로만 다룬다.
- `StateT` 에러 리프팅 헬퍼: `StateT.liftF(EitherT.leftT(...))` 패턴을 반복적으로 사용하므로 `StoreF.raise(failure)` 헬퍼를 추가해 가독성과 실수를 줄인다.
- AccessLog 한계 가드: 퍼블릭 체인의 gas 정책과 달리, 프라이빗 환경에서는 노드 메모리 보호가 목적이므로 `TransactionGuards` 유틸을 도입해 AccessLog 키/접근 한도를 모듈별로 쉽게 적용할 수 있도록 한다.

### 4. 패키지 및 디렉터리 구조 정돈
- `org.sigilaris.core.application` 하위 구조를 `domain`(순수 데이터/ADT), `transactions`(Tx 정의), `module`(블루프린트/리듀서), `support`(헬퍼)로 구분해 책임을 명확히 한다. 예: `AccountsTypes.scala` → `domain`, `AccountsTransactions.scala` → `transactions`, `AccountsBlueprint.scala` → `module`.
- 블루프린트 조립 전용 패키지(`org.sigilaris.core.assembly`)를 생성해 DSL, 증거 파생 유틸, Prefix 검증기를 모아 개발자가 한 위치에서 참조할 수 있도록 한다.
- **Accounts/Group 하이브리드 구조 채택**: `org.sigilaris.core.security` 패키지를 신설해 서명 검증·복구 등 보안 핵심을 모으고, Accounts/Group은 `domain`/`transactions`/`module` 구성을 유지한 채 보안 의존성만 `security`에서 참조한다. Accounts의 서명 관련 헬퍼(`SignatureVerifier`, 키 파생 유틸 등)는 `security.accounts`로 이동하고, Group은 최소한의 가드 로직만 `security` 패키지를 의존한다.
- 토큰 관련 구현은 라이브러리로 제공하지 않고, 위 구조에 맞춘 예제 가이드를 별도의 `examples` 또는 문서에 배치한다.

### 5. 타입 증거 후속 작업
- OrderedCodec 스트리밍 제약: `StateTable`의 스트리밍/범위 조회 API에 `using OrderedCodec` 제약을 연결해 기존 법칙 테스트와 실행 경로를 일치시킨다.
- Dependency DAG 검증: 모듈 조립 시 순환 의존을 차단하는 `AcyclicDependencies`(가칭) evidence를 도입하고, `ModuleBlueprint` 합성 지점에서 소환한다.

### 6. 테스트 및 샘플 강화 (문서화는 후순위)
- 모듈별 골든 테스트: 계정/그룹 모듈에 대해 `Signed` 트랜잭션 시나리오를 통합 테스트로 추가하고, AccessLog 충돌 케이스(동일 키 갱신 등)를 표준화한다.
- 샘플 애플리케이션: `io/example` 수준의 경량 샘플 프로젝트를 마련해 실제 `composeBlueprint → mount → 실행` 흐름을 보여준다.
- 문서화 작업은 코드 재구성이 완료된 뒤 진행하며, 본 계획에서는 제외한다.

## 단기 착수 제안 (Next Steps)
- `SignatureVerifier`와 `StoreF.raise` 유틸을 먼저 도입해 기존 계정 모듈에 적용하고, 동일 패턴이 필요한 곳(TODO 주석 존재 여부 분석 포함)을 식별한다.
- 모듈 조립 DSL 초안과 `TablesProvider.fromModule` 구현을 동시에 진행해 Group 모듈 의존 주입을 간소화한다.
- 하이브리드 패키징 PoC를 우선 착수해 `security` 패키지 경계를 확정하고, 같은 마일스톤에서 OrderedCodec 제약 연결 작업을 진행한다.
- Dependency DAG evidence는 패키징 변경 안정화 후 순차적으로 반영한다.

## 참고 자료
- ADR 0009: `docs/adr/0009-blockchain-application-architecture.md`
- ADR 0010: `docs/adr/0010-blockchain-account-model-and-key-management.md`
- ADR 0011: `docs/adr/0011-blockchain-account-group-management.md`
- ADR 0012: `docs/adr/0012-signed-transaction-requirement.md`
- 구현 참고: `modules/core/shared/src/main/scala/org/sigilaris/core/application/accounts/AccountsBlueprint.scala`
