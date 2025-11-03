# Sigilaris

[![Maven Central](https://img.shields.io/maven-central/v/org.sigilaris/core_3.svg)](https://central.sonatype.com/artifact/org.sigilaris/core_3)
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
libraryDependencies += "org.sigilaris" %%% "core" % "0.1.0"
```

Cross-platform support:
- `%%` for JVM-only projects
- `%%%` for cross-platform (JVM + Scala.js) projects

## Core Modules

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

- **[API Documentation](https://javadoc.io/doc/org.sigilaris/core_3/latest/index.html)** — Comprehensive Scaladoc
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

See [benchmarks/reports/](benchmarks/reports/) for detailed performance data.

## License

Sigilaris is dual-licensed to support both open-source and commercial blockchain projects:
- **[AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.en.html)** for open source and public blockchain projects
- **Commercial license** available for private/enterprise blockchain deployments

For commercial licensing inquiries: [contact@sigilaris.org](mailto:contact@sigilaris.org)

## Coming Soon

- **Merkle Tree**: Efficient state verification and proof generation
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
- JVM: `org.sigilaris:core_3:0.1.0`
- Scala.js: `org.sigilaris:core_sjs1_3:0.1.0`

**Scala Version:** 3.7.3
