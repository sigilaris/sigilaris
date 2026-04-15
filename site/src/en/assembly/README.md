# Assembly DSL

The assembly package is the ergonomic layer on top of the application module.
It keeps the same typed module model, but adds a smaller surface for mounting
blueprints, deriving providers, and constructing entry descriptors.

## Current Surface

- `BlueprintDsl` mounts module and composed blueprints at path literals.
- `EntrySyntax` provides the `entry"..."` interpolator for typed table
  descriptors.
- `TablesProviderOps` extracts dependency providers from mounted modules.
- `TablesAccessOps` and related compile-time evidence helpers keep mounted-table
  access typed.

## Current Mounting Style

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

## What The DSL Adds

- shorter mounting syntax over `StateModule.mount`
- a clear path-literal seam for prefix-free validation
- provider extraction that keeps dependency boundaries explicit
- entry construction that preserves type-level table names

If you need the lower-level reducer and module types directly, go back to the
[Application Module](../application/README.md). The assembly layer is a helper,
not a separate runtime model.

## Current Baseline

- The DSL is already aligned with the shipped accounts/groups blueprint model.
- It is intended for wiring and mounting, not for replacing the generated API
  inventory.
- Detailed method signatures remain in
  [API](https://sigilaris.github.io/sigilaris/api/index.html).

## Related Pages

- [Application Module](../application/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
