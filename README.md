# Sigilaris

[![Maven Central](https://img.shields.io/maven-central/v/org.sigilaris/sigilaris-core_3.svg)](https://central.sonatype.com/artifact/org.sigilaris/sigilaris-core_3)
[![Scala Version](https://img.shields.io/badge/scala-3.7.3-red.svg)](https://www.scala-lang.org/)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0.en.html)

A purely functional library for building application-specific private blockchains in Scala.

## Overview

Sigilaris provides type-safe, deterministic building blocks for constructing custom blockchain applications. Built on the cats-effect ecosystem, it offers cross-platform support for both JVM and JavaScript, enabling you to create tailored blockchain solutions with compile-time guarantees.

## Features

- **Application-Specific Design**: Build custom private blockchains tailored to your exact requirements
- **Deterministic Encoding**: Guaranteed consistent byte representation for hashing and signatures
- **Purely Functional**: Built on cats-effect for composable and referential transparent operations
- **Cross-Platform**: Supports both JVM and Scala.js
- **Type-Safe**: Leverages Scala's type system for compile-time safety
- **Library-Agnostic**: Flexible JSON codec works with any backend (Circe, Play JSON, etc.)

## Getting Started

Add Sigilaris to your `build.sbt`:

```scala
libraryDependencies += "org.sigilaris" %%% "sigilaris-core" % "VERSION"
```

Cross-platform support:
- `%%` for JVM-only projects
- `%%%` for cross-platform (JVM + Scala.js) projects

When you need the shared node contract layer directly on JVM/JS, add
`sigilaris-node-common`. When you need the JVM runtime bundle, add
`sigilaris-node-jvm` (it already depends on `sigilaris-node-common`):

```scala
libraryDependencies ++= Seq(
  "org.sigilaris" %%% "sigilaris-core" % "VERSION",
  "org.sigilaris" %%% "sigilaris-node-common" % "VERSION", // optional when consuming shared node contracts directly
  "org.sigilaris" %% "sigilaris-node-jvm" % "VERSION",     // optional JVM runtime bundle; transitively depends on node-common
)
```

## Core Modules

- `sigilaris-core`: cross-platform deterministic blockchain primitives, codecs, crypto, and state/model abstractions.
- `sigilaris-node-common`: cross-platform node contract layer layered on top of `sigilaris-core`, providing transport-neutral gossip/session/bootstrap model, service contracts, and tx anti-entropy runtime logic.
- `sigilaris-node-jvm`: JVM-only node bundle layered on top of `sigilaris-node-common`, providing runtime lifecycle seams, config/bootstrap assembly, HotStuff consensus runtime integration, Armeria HTTP transport adapters, and SwayDB storage helpers.

### Node Networking Baseline
- `sigilaris-node-common` now ships the transport-neutral gossip/session substrate under `org.sigilaris.node.gossip` and `org.sigilaris.node.gossip.tx`.
- `sigilaris-node-jvm` consumes the shared contracts while keeping JVM-only config loaders, bootstrap assembly, transport adapters, and storage integrations under `org.sigilaris.node.jvm.*`.
- On the JVM transport baseline, Armeria maps directional sessions to resources for session-open handshake, binary event polling/keepalive (`application/octet-stream` length-prefixed frames), and batched JSON control requests.
- Negotiated heartbeat/liveness, opening timeout expiry, and pre-open reject-and-close are now enforced by the shipped runtime/Armeria baseline rather than manual disconnect hooks alone.
- Static peer topology and direct-neighbor admission are the current deployment baseline. Dynamic discovery and peer scoring remain follow-up work.
- The concrete JVM baseline loader reads static peer topology from `sigilaris.node.gossip.peers` (`local-node-identity`, `known-peers`, `direct-neighbors`) and wires it into runtime bootstrap/admission.
- The HotStuff baseline loader reads `sigilaris.node.consensus.hotstuff` for local role, validator/key-holder inventory, local signer set, and proposal/vote gossip policy, then assembles an explicit bootstrap/service graph on top of the static topology baseline.
- The shipped HotStuff JVM bootstrap baseline now covers finalized-anchor suggestion discovery, static-trust-root verification, snapshot trie sync, anchor-pinned forward catch-up with tx-based vote-hold gating, and low-priority historical backfill under `org.sigilaris.node.jvm.runtime.consensus.hotstuff`.
- The shipped bootstrap trust root still remains the static validator set from `HotStuffBootstrapConfig.validatorSet`; ADR-0023 now drafts validator-set rotation, checkpoint / weak-subjectivity trust-root classes, and historical lookup semantics, while concrete checkpoint/root runtime integration lives in `docs/plans/0007-snapshot-sync-and-background-backfill-plan.md` and archive-grade backfill acceleration remains explicit follow-up work.
- The shipped transport now enforces static peer principal binding with per-peer shared-secret HMAC request proofs and session-bound bootstrap capability tokens; parent session open-state revalidation, principal mismatch rejection, and stale capability rejection are part of the current baseline.
- Reconnect now replays a full re-handshake under the existing peer correlation id for half-open recovery, and new directional sessions start with empty filter/control state instead of carrying prior `setFilter` state.
- Topic-neutral producer session state, polling, batching/QoS hooks, and tx anti-entropy runtime logic now live in `sigilaris-node-common`, so follow-up runtime modules do not need to rewrite the substrate to reuse it.
- The shipped JVM baseline now includes HotStuff non-threshold-signature proposal/vote/QC artifact modeling under `org.sigilaris.node.jvm.runtime.consensus.hotstuff`, plus `consensus.proposal` / `consensus.vote` gossip integration with exact known-set windows, bounded `requestById`, same-window retry budget enforcement, QC assembly, audit read-only follow, validated-artifact relay, and consensus-priority QoS.
- Canonical block modeling now lives under `org.sigilaris.node.jvm.runtime.block`, with `BlockHeader` / `BlockBody` / `BlockView`, header-only `BlockId`, deterministic `bodyRoot` verification, and a consensus-neutral `BlockStore` query/storage seam for split header/body lookup.
- Import-rule tests keep `sigilaris-core` free of `org.sigilaris.node.*`, keep `sigilaris-node-common` free of JVM-specific packages, and keep the HotStuff consensus core free of transport implementations or the current concrete storage packages (`org.sigilaris.node.jvm.storage.memory`, `org.sigilaris.node.jvm.storage.swaydb`) outside the explicit bootstrap/persistence assembly edges.
- State snapshot transport, remote body fetch, proof-serving, receipt/event sub-root expansion, and persisted block compatibility policy remain explicit follow-up work owned by ADR-0019 and `docs/plans/0005-canonical-block-structure-migration-plan.md`.
- Pacemaker timeout vote / timeout certificate / new-view modeling, validation, topic contracts, timer/backoff wiring, deterministic leader activation, and runtime-owned timeout/view-change progression are now part of the shipped JVM HotStuff baseline.

### Reference Launch Smoke
- Run `sbt "testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffLaunchSmokeSuite"` to exercise the repo-local static multi-node launch harness.
- The current harness seeds the bootstrap prefix once, automatically forms a static full-mesh session set, keeps background event polling alive, and then lets the runtime-owned proposal/vote/QC and timeout/new-view paths drive progression.
- The current harness verifies multi-node wiring, contiguous height advancement across three validator heights, timeout-driven view change turnover, newcomer/audit follower bootstrap readiness within the bounded retry window, DR relocation flow, and persisted historical archive reopen on a process-equivalent restart root.
- The DR smoke path fences the old validator holder first, then restarts the same validator identity/key on the pre-provisioned audit node; the old holder must stay stopped, and the replacement node must keep the static peer graph and signer material preconfigured before restart.
- The harness is still test-only launch proof, not a productized daemon/CLI or orchestration package; operators still own process start/stop, restart sequencing, and config rollout.
- Current limitations remain the static validator set, static peer topology, same-DC assumptions, operator-managed restart/fencing flow, and the missing product launcher/orchestrator; dynamic discovery, validator rotation, automatic failover, and remote signer/KMS integration are still follow-up work.

### Static Launch Notes
- Minimal peer config lives under `sigilaris.node.gossip.peers` and must provide `local-node-identity`, `known-peers`, `direct-neighbors`, plus `transport-auth.peer-secrets` for every static peer principal that can open a session or call bootstrap endpoints.
- Minimal HotStuff config lives under `sigilaris.node.consensus.hotstuff` and must provide `local-role`, `validators`, `key-holders`, and `local-signers`; `historical-sync-enabled` defaults to `true`, while `bootstrap-trust-root` and `historical-validator-sets` are optional extensions when trusted checkpoints or historical validator-set lookup are needed.
- Startup order is static and operator-managed: provision the same validator inventory, key-holder mapping, peer-secret set, and direct-neighbor graph on every node first; then start validator nodes that own active signer material; then start audit/newcomer nodes that need bootstrap/backfill against the already-running validator mesh.
- Restart/DR order is also operator-managed: stop and fence the old validator holder first, keep its signer inactive, enable the same validator id/key only on the replacement node, and restart the replacement with the pre-provisioned static peer graph and storage root before allowing it to rejoin quorum.
- Startup should fail fast on config drift: peer principal mismatch, missing signer material, stale holder maps, or partial topic registration are treated as startup failures rather than background self-healing.

### Data Types
Type-safe opaque types for blockchain primitives with built-in codec support.
- **BigNat**: Arbitrary-precision natural numbers
- **UInt256**: 256-bit unsigned integers
- **Utf8**: Length-prefixed UTF-8 strings
- Zero-cost abstractions with compile-time safety and automatic validation

### Byte Codec
Deterministic byte encoding/decoding for custom blockchain implementations.
- Essential for: Transaction signing, block hashing, state commitment, consensus mechanisms
- Guarantees identical byte representation across all nodes

### JSON Codec
Library-agnostic JSON encoding/decoding for blockchain APIs and configuration.
- Use cases: RPC API serialization, node configuration, off-chain data interchange
- Flexible backend support for seamless integration

### Crypto
High-performance cryptographic primitives for blockchain applications.
- **secp256k1 ECDSA**: Industry-standard elliptic curve cryptography
- **Keccak-256 hashing**: Ethereum-compatible hash function
- **Signature recovery**: Public key recovery from signatures
- **Low-S normalization**: Canonical signature format enforcement
- Cross-platform: Unified API for JVM (BouncyCastle) and JS (elliptic.js)

### Blockchain Application Architecture
- **State Management**: Merkle trie-based deterministic state with type-safe tables
- **Module System**: Composable blueprints with compile-time dependency tracking
- **Transaction Model**: Type-safe transactions with access control and conflict detection
- **Access Logging**: Automatic read/write tracking for parallel execution optimization
- Path-based prefix-free namespace isolation preventing table collisions

## Documentation

- **[API Documentation](https://javadoc.io/doc/org.sigilaris/sigilaris-core_3/latest/index.html)** — Comprehensive Scaladoc
- **[v0.2.0 Release Notes](docs/dev/v0.2.0-release-notes.md)** — Latest published release notes and upgrade notes
- **[Latest Release](https://github.com/sigilaris/sigilaris/releases/latest)** — Release notes and artifacts
- **[GitHub Repository](https://github.com/sigilaris/sigilaris)** — Source code and examples

## Architecture Highlights

This release incorporates significant architectural decisions:

- **Compile-time prevention** of table name collisions (`UniqueNames`)
- **Path-level prefix-free** namespace encoding preventing key conflicts
- **Type-level dependency tracking** with `Needs`/`Provides` model
- **Automatic access logging** for transaction conflict detection
- **Zero-cost branded keys** preventing cross-table key misuse

See [docs/adr/](docs/adr/) for detailed architectural decision records.

## Performance

Comprehensive JMH benchmarks demonstrate:
- Optimized crypto operations with caching strategies
- Minimal allocation patterns in hot paths
- Cross-platform consistency between JVM and JS implementations
- Deterministic performance characteristics

The benchmark harness is public under [benchmarks/](benchmarks/). Archived regression baselines and historical report JSONs stay in the private canonical repository.

## License

Sigilaris is dual-licensed to support both open-source and commercial blockchain projects:
- **[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.en.html)** for open source and public blockchain projects
- **Commercial license** available for private/enterprise blockchain deployments

For commercial licensing inquiries: [contact@sigilaris.org](mailto:contact@sigilaris.org)

## Coming Soon

- **Consensus Algorithms**: Pluggable consensus for private blockchain networks
- **P2P Networking**: Node discovery and communication protocols
- **State Management**: Persistent storage abstractions for blockchain state

## Contributing

Contributions are welcome! Please see our contribution guidelines (coming soon).

## Acknowledgments

Built with:
- [Cats Effect](https://typelevel.org/cats-effect/) — Pure functional effects
- [BouncyCastle](https://www.bouncycastle.org/) — JVM cryptography
- [elliptic](https://github.com/indutny/elliptic) — JavaScript elliptic curve cryptography
- [Scala.js](https://www.scala-js.org/) — Cross-platform compilation

---

**Maven Coordinates:**
- JVM core: `org.sigilaris:sigilaris-core_3:VERSION`
- Scala.js core: `org.sigilaris:sigilaris-core_sjs1_3:VERSION`
- JVM node-common: `org.sigilaris:sigilaris-node-common_3:VERSION`
- Scala.js node-common: `org.sigilaris:sigilaris-node-common_sjs1_3:VERSION`
- JVM node bundle: `org.sigilaris:sigilaris-node-jvm_3:VERSION`

Current downstream adoption work in this repository uses local `0.2.0` artifacts via `publishLocal`.

**Scala Version:** 3.7.3
