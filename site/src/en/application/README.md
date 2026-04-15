# Application Module

The application module is the path-independent state model that `sigilaris-core`
ships today. It provides typed blueprints, path-bound mounted modules, reducer
contracts, transaction envelopes, and store abstractions for composing
application-specific blockchain state.

## Current Surface

- `org.sigilaris.core.application.module.*` defines `ModuleBlueprint`,
  `ComposedBlueprint`, `StateReducer0`, `StateModule`, and `TablesProvider`.
- `org.sigilaris.core.application.transactions.*` defines `Tx`,
  `TxEnvelope`, signatures, and routing types.
- `org.sigilaris.core.application.state.*` defines `Entry`, `Tables`,
  `StateTable`, `StoreF`, and `StoreState`.
- `org.sigilaris.core.application.feature.accounts.*` and
  `org.sigilaris.core.application.feature.group.*` are the current concrete
  feature modules documented in this site.

## Why It Exists

Sigilaris separates stateful application logic into:

- path-independent blueprints that describe owned tables, dependencies, and
  transaction coverage
- path-bound mounted modules that instantiate concrete table prefixes and make
  reducer wiring executable

That split lets the same module logic be mounted under different deployment
paths while still keeping schema validation, prefix-free safety, and provider
projection at compile time.

## Compile-Time Building Blocks

- `Entry["name", K, V]` describes a table in the schema.
- `Requires`, `UniqueNames`, and `PrefixFreePath` keep schema composition
  coherent before runtime.
- `TablesProvider` carries read access to dependency tables without collapsing
  module boundaries.
- `StoreF` threads Merkle state and failure handling through reducers.

## Current Blueprint Wiring

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

The current repository uses exactly this style of mounted-module wiring in its
integration and scheduling tests. The assembly layer adds a thinner DSL over
the same primitives; it does not replace the application module.

## Current Baseline

- Accounts and groups are the shipped reference feature modules.
- Blueprints own typed schemas and reducers; mounted modules own path-bound
  table instances.
- Dependency wiring is explicit: the group module consumes an accounts provider
  rather than reaching through a global registry.
- The generated API under
  [API](https://sigilaris.github.io/sigilaris/api/index.html) remains the canonical
  inventory for method and type details.

## Related Pages

- [Accounts Module](accounts.md)
- [Group Module](group.md)
- [Assembly DSL](../assembly/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
