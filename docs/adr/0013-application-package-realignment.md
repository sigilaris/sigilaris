# ADR-0013: Application Package Realignment

## Status
Accepted

## Context
- `org.sigilaris.core.application` currently mirrors identical sources under `.../application/domain`, creating duplicated entry points and uncertainty about the canonical API.
- Support code mixes compile-time derivation helpers, runtime executors, and encoding utilities in a single `support` namespace, making intent discovery difficult.
- Feature modules (e.g., accounts, group) follow a consistent three-tier layout (`domain`/`transactions`/`module`) but are not grouped under a shared boundary, which complicates future feature additions.
- We have no downstream consumers pinned to the pre-existing package names, so we can freely realign namespaces without providing legacy aliases.

## Decision
1. **Establish a single canonical root**
   - Move shared state abstractions (`AccessLog`, `StateTable`, `StoreState`, `Entry`) into `org.sigilaris.core.application.state`.
   - Remove the redundant top-level aliases; all code must import from the new subpackages.

2. **Group module lifecycle logic**
   - Relocate blueprint/mounting constructs into `org.sigilaris.core.application.module.blueprint` (`Blueprint.scala`, `SchemaInstantiation.scala`).
   - Move path-bound runtime wiring into `org.sigilaris.core.application.module.runtime` (`Module.scala`, `StateModule.scala`, reducer mounting helpers).
   - Keep provider abstractions in `org.sigilaris.core.application.module.provider` (`TablesProvider.scala`, future provider combinators).

3. **Segment execution helpers**
   - Place `StateModuleExecutor` and related tooling under `org.sigilaris.core.application.execution` to separate "mounted reducer execution flows" from blueprint instantiation concerns.
   - Provide a focused entry point for integration tests and driver code.

4. **Split support utilities by responsibility**
   - `support.compiletime`: inline derivation, evidence pretty printers, type-level helpers, and the compile-time proofs that rely on them (`RequiresAuto`, `LookupAuto`, `EvidencePretty`, `Evidence`/`Requires`, `PrefixFreePath`, `UniqueNames`).
   - `support.encoding`: runtime path/segment encoding helpers (`PathEncoding`, `tablePrefixRuntime`, etc.).
   - `support.runtime`: host-side builders and helpers (`SignedTxBuilder`).
   - `support.syntax`: reserved for future syntax/extension re-exports (currently empty to avoid premature abstractions).

5. **Clarify security scope without reintroducing feature coupling**
   - Extract account-specific logic from `SignatureVerifier` into `feature.accounts.security` helpers so the shared verifier can move to `org.sigilaris.core.application.transactions.security` without depending on account models.
   - Delay the relocation until the split lands to prevent circular dependencies during the transition (current verifier imports `Account`, `KeyId20`, and `KeyInfo`, so moving it prematurely would create `transactions.security` → `feature.accounts.domain` → `transactions` cycles).

6. **Consolidate transaction model types**
   - `org.sigilaris.core.application.transactions.model`: `Tx`, `ModuleId`, `Signed`, `AccountSignature`.
   - Feature-specific transactions remain in their feature modules (e.g., accounts, group) but import model primitives from the shared namespace.

7. **Create a feature boundary**
   - Introduce `org.sigilaris.core.application.feature.accounts` and `.feature.group`, each with subpackages:
     - `domain`: domain models and branding types.
     - `transactions`: feature-specific transaction payloads.
     - `module`: state reducers / module blueprints (rename existing `module` folders to `module` under the new feature boundary).
   - Future features (tokens, staking, etc.) will follow the same pattern.

8. **Documentation and re-exports**
   - Provide a short package index under `docs/` describing the new layout.
   - Avoid legacy type aliases; migration is handled entirely by package refactors.

9. **Testing alignment**
   - Ensure unit tests mirror the production package tree under `modules/core/shared/src/test/scala/org/sigilaris/core/application` (see "Testing Improvements" for concrete tasks).
   - Add property-based tests for path encoding/prefix proofs and module wiring smoke tests to guard the refactor.

## Testing Improvements
- **Package mirroring**: Move existing `application`-level test suites to follow the same package hierarchy (`state`, `module`, `transactions`, `feature.<name>`) so future regressions surface in the correct scope (e.g., `test/.../state/AccessLogSpec.scala`).
- **Prefix encoding properties**: Introduce ScalaCheck suites validating (a) differing paths never share a common byte-prefix and (b) `PrefixFreePath` derivations reject schemas where concatenated table names would collide after the refactor.
- **Module wiring smoke tests**: Add integration-style tests that mount representative blueprints with mocked `TablesProvider`s to ensure route heads, Needs/Owns wiring, and `StateModuleExecutor` interactions survive the repackage.
- **Security split coverage**: When splitting `SignatureVerifier`, provide unit tests for the shared verifier (pure crypto path) and feature-specific authorization helpers to keep the dependency graph honest.
- **AccessLog regressions**: Extend existing access-log tests to live under `test/.../state` (e.g., `AccessLogSpec`, `StoreStateSpec`) and assert the logging/export contract after the move.

## Consequences
- Imports must be updated across the repository, but the changes are straightforward mechanical refactors.
- Developers gain a predictable mental model: core state abstractions under `.state`, module wiring under `.module`, derivation utilities grouped by role, and feature modules neatly partitioned.
- Tooling (IDE auto-import, documentation generators) will present a cleaner package tree.
- The absence of legacy aliases eliminates ambiguity about the supported API surface but requires all work-in-progress branches to adopt the new paths.

## Migration Plan
1. Rename/move files to the target packages using `git mv` to preserve history.
2. Update package declarations and imports to reflect the new namespaces.
3. Remove `Transaction.scala` aliases once all call sites import from `transactions.model`.
4. Split the `support` directory into the new subdirectories, moving files accordingly.
5. Author `docs/application-package-index.md` summarizing the new package structure (mirrors README snippet described in "Next Steps").
6. Run Scala format/lint to ensure consistent package ordering.
7. Update ADR references (e.g., ADR-0009/0010) if they point to outdated packages, including embedded package names in code snippets and directory diagrams.

_Steps 3 and 4 can proceed in parallel provided call sites compile after each rename._

## Next Steps
- Execute the migration plan during the current branch to avoid divergent structures.
- Publish a concise README snippet that links to `docs/application-package-index.md` once the layout is in place.
- Track the rollout; no further ADR updates required unless the plan changes materially.
