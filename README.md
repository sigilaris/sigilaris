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

When you need the JVM node/runtime bundle as well:

```scala
libraryDependencies ++= Seq(
  "org.sigilaris" %%% "sigilaris-core" % "VERSION",
  "org.sigilaris" %% "sigilaris-node-jvm" % "VERSION",
)
```

## Core Modules

- `sigilaris-core`: cross-platform deterministic blockchain primitives, codecs, crypto, and state/model abstractions.
- `sigilaris-node-jvm`: JVM-only node bundle layered on top of `sigilaris-core`, providing runtime lifecycle seams, runtime-owned gossip/session sync substrate, Armeria HTTP transport adapters, and SwayDB storage helpers.

### JVM Networking Baseline
- `sigilaris-node-jvm` now ships a runtime-owned tx gossip/session substrate under `org.sigilaris.node.jvm.runtime.gossip` and `org.sigilaris.node.jvm.runtime.gossip.tx`.
- The current HTTP-friendly baseline maps directional sessions to Armeria resources for session-open handshake, NDJSON event polling/keepalive, and batched control requests.
- Negotiated heartbeat/liveness, opening timeout expiry, and pre-open reject-and-close are now enforced by the shipped runtime/Armeria baseline rather than manual disconnect hooks alone.
- Static peer topology and direct-neighbor admission are the current deployment baseline. Dynamic discovery and peer scoring remain follow-up work.
- The concrete JVM baseline loader reads static peer topology from `sigilaris.node.gossip.peers` (`local-node-identity`, `known-peers`, `direct-neighbors`) and wires it into runtime bootstrap/admission.
- The HotStuff baseline loader reads `sigilaris.node.consensus.hotstuff` for local role, validator/key-holder inventory, local signer set, and proposal/vote gossip policy, then assembles an explicit bootstrap/service graph on top of the static topology baseline.
- Reconnect now replays a full re-handshake under the existing peer correlation id for half-open recovery, and new directional sessions start with empty filter/control state instead of carrying prior `setFilter` state.
- Topic-neutral producer session state, polling, and batching/QoS hooks are available under `org.sigilaris.node.jvm.runtime.gossip`, so follow-up topic owners do not need to rewrite the tx runtime internals just to reuse the substrate.
- The shipped JVM baseline now includes HotStuff non-threshold-signature proposal/vote/QC artifact modeling under `org.sigilaris.node.jvm.runtime.consensus.hotstuff`, plus `consensus.proposal` / `consensus.vote` gossip integration with exact known-set windows, bounded `requestById`, same-window retry budget enforcement, QC assembly, audit read-only follow, validated-artifact relay, and consensus-priority QoS.
- Import-rule tests keep `org.sigilaris.node.jvm.runtime.gossip` free of direct consensus/HotStuff imports and prevent `org.sigilaris.node.jvm.runtime.consensus.hotstuff` from importing transport implementations or the current concrete storage packages (`org.sigilaris.node.jvm.storage.memory`, `org.sigilaris.node.jvm.storage.swaydb`) directly.
- Pacemaker timeout vote / timeout certificate / new-view wire contracts remain follow-up work owned by ADR-0017 and `docs/plans/0004-hotstuff-consensus-without-threshold-signatures-plan.md`.

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
- **[v0.1.1 Release Notes](docs/dev/v0.1.1-release-notes.md)** — Latest published release notes and upgrade notes
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
- JVM node bundle: `org.sigilaris:sigilaris-node-jvm_3:VERSION`

Current downstream adoption work in this repository uses local `0.1.2-SNAPSHOT` artifacts via `publishLocal`.

**Scala Version:** 3.7.3
