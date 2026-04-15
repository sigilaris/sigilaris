# Assembly DSL

Assembly package는 application module 위에 얹힌 ergonomic layer다. typed
module model은 그대로 유지하되, blueprint mount, provider derivation,
entry descriptor 생성을 더 짧은 surface로 제공한다.

## 현재 표면

- `BlueprintDsl`은 module/composed blueprint를 path literal에 mount한다.
- `EntrySyntax`는 typed table descriptor를 위한 `entry"..."` interpolator를
  제공한다.
- `TablesProviderOps`는 mounted module에서 dependency provider를 추출한다.
- `TablesAccessOps`와 관련 compile-time evidence helper는 mounted-table access를
  typed 상태로 유지한다.

## 현재 Mounting 스타일

```scala mdoc:compile-only
import cats.data.{EitherT, Kleisli}
import cats.effect.IO
import org.sigilaris.core.application.feature.accounts.module.AccountsBP
import org.sigilaris.core.application.feature.group.module.GroupsBP
import org.sigilaris.core.assembly.BlueprintDsl.*
import org.sigilaris.core.assembly.TablesProviderOps.*
import org.sigilaris.core.merkle.{MerkleTrie, MerkleTrieNode}

given MerkleTrie.NodeStore[IO] = Kleisli: (_: MerkleTrieNode.MerkleHash) =>
  EitherT.rightT[IO, String](None)

val accountsModule = mount("accounts" -> AccountsBP[IO])
val accountsProvider = accountsModule.toTablesProvider
val groupsModule = mount("groups" -> GroupsBP[IO](accountsProvider))
```

## DSL이 추가하는 것

- `StateModule.mount` 위의 더 짧은 mounting syntax
- prefix-free validation을 위한 명시적 path-literal seam
- dependency boundary를 드러내는 provider extraction
- type-level table name을 보존하는 entry construction

Reducer/module type을 더 직접적으로 제어해야 하면
[애플리케이션 모듈](../application/README.md)로 내려가면 된다. Assembly layer는
helper이지 별도의 runtime model이 아니다.

## 현재 Baseline

- DSL은 현재 shipped accounts/groups blueprint model과 이미 정렬돼 있다.
- 이 레이어는 wiring/mounting을 위한 것이지 generated API inventory를
  대체하지 않는다.
- 상세 시그니처는
  [API](https://sigilaris.github.io/sigilaris/api/index.html)를 canonical
  reference로 둔다.

## 관련 페이지

- [애플리케이션 모듈](../application/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
