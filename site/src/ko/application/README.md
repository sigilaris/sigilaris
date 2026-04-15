# 애플리케이션 모듈

애플리케이션 모듈은 현재 `sigilaris-core`가 제공하는 path-independent
상태 모델이다. 이 모듈은 typed blueprint, path-bound mounted module,
reducer contract, transaction envelope, store abstraction을 통해
애플리케이션 특화 블록체인 상태를 조합한다.

## 현재 표면

- `org.sigilaris.core.application.module.*`는 `ModuleBlueprint`,
  `ComposedBlueprint`, `StateReducer0`, `StateModule`, `TablesProvider`를
  제공한다.
- `org.sigilaris.core.application.transactions.*`는 `Tx`, `TxEnvelope`,
  signature, routing type을 제공한다.
- `org.sigilaris.core.application.state.*`는 `Entry`, `Tables`,
  `StateTable`, `StoreF`, `StoreState`를 제공한다.
- `org.sigilaris.core.application.feature.accounts.*`와
  `org.sigilaris.core.application.feature.group.*`가 현재 사이트에서 다루는
  concrete feature module이다.

## 왜 필요한가

Sigilaris는 상태 변경 로직을 다음 두 단계로 나눈다.

- owned table, dependency, transaction coverage를 선언하는
  path-independent blueprint
- 실제 table prefix를 인스턴스화하고 reducer wiring을 실행 가능한 형태로
  바꾸는 path-bound mounted module

이 분리 덕분에 같은 모듈 로직을 다른 배포 경로에 올리면서도 schema
validation, prefix-free safety, provider projection을 컴파일 타임에 유지할
수 있다.

## 컴파일 타임 빌딩 블록

- `Entry["name", K, V]`는 schema의 table을 설명한다.
- `Requires`, `UniqueNames`, `PrefixFreePath`는 runtime 전에 schema 구성을
  검증한다.
- `TablesProvider`는 모듈 경계를 무너뜨리지 않고 dependency table 읽기
  접근을 전달한다.
- `StoreF`는 reducer 실행 동안 Merkle state와 failure handling을 함께
  스레딩한다.

## 현재 Blueprint Wiring

```scala mdoc:compile-only
import cats.data.{EitherT, Kleisli}
import cats.effect.IO
import org.sigilaris.core.application.feature.accounts.module.AccountsBP
import org.sigilaris.core.application.feature.group.module.GroupsBP
import org.sigilaris.core.application.module.provider.TablesProvider
import org.sigilaris.core.application.module.runtime.StateModule
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

given MerkleTrie.NodeStore[IO] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
  EitherT.rightT[IO, String](None)

val accountsModule =
  StateModule.mount[("app", "accounts")](AccountsBP[IO])
val accountsProvider = TablesProvider.fromModule(accountsModule)
val groupsModule =
  StateModule.mount[("app", "groups")](GroupsBP[IO](accountsProvider))
```

현재 저장소의 integration/scheduling test도 이 방식의 mounted-module wiring을
사용한다. assembly layer는 이 primitive 위에 얇은 DSL을 더하는 것이지,
application module을 대체하지 않는다.

## 현재 Baseline

- Accounts와 Groups가 현재 shipped reference feature module이다.
- Blueprint는 typed schema와 reducer를 소유하고, mounted module은 path-bound
  table instance를 소유한다.
- Dependency wiring은 명시적이다. Group module은 전역 registry를 우회하지
  않고 accounts provider를 소비한다.
- 메서드/타입의 canonical inventory는 여전히
  [API](https://sigilaris.github.io/sigilaris/api/index.html)가 담당한다.

## 관련 페이지

- [Accounts Module](accounts.md)
- [Group Module](group.md)
- [Assembly DSL](../assembly/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
