# Application Package Index

Maintains a map of the canonical `org.sigilaris.core.application` packages after ADR-0013.

## Core Namespaces
- `state`: shared tables, store algebra, and access logging primitives used by every blueprint.
- `module.blueprint`: blueprint definitions and reducer scaffolding used when instantiating modules.
- `module.provider`: provider abstractions that expose mounted table handles to dependent modules.
- `module.runtime`: mounting helpers and runtime wiring (`StateModule`, mount/compose utilities).
- `execution`: host-side executors for running mounted modules against a backing store.

## Feature Boundary
- `feature.<name>.domain`: immutable domain models and brand types for each feature (e.g. accounts, group).
- `feature.<name>.transactions`: feature-specific transaction payloads that depend on shared transaction model types.
- `feature.<name>.module`: reducers and blueprints that wire feature state and transactions together.

## Transaction Model
- `transactions.model`: shared transaction primitives (`Tx`, `ModuleId`, `Signed`, `AccountSignature`).
- `transactions.security`: shared verification pipelines that operate across features (feature-level hooks live under `feature.<name>.security`).

## Support Utilities
- `support.compiletime`: inline derivation helpers, evidence printers, and type-level proofs used during compilation.
- `support.encoding`: runtime helpers for encoding table paths and key prefixes.
- `support.runtime`: builders and utilities used by integration tests or host applications (e.g. `SignedTxBuilder`).
- `support.syntax`: reserved namespace for future syntax extensions; currently empty.

## Working With the Layout
- Prefer importing from `state`, `module.*`, and `feature.*` directly; legacy `application.domain` aliases have been removed.
- Tests mirror the production tree under `modules/core/shared/src/test/scala/org/sigilaris/core/application/...` to ensure coverage lines up with the runtime packages.
- When authoring a new feature, follow the `feature.<name>.{domain,transactions,module}` convention so blueprints, reducers, and API types remain discoverable.
