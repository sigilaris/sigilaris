# Sigilaris

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
libraryDependencies += "org.sigilaris" %%% "sigilaris-core" % "@VERSION@"
```

## Documentation

### Core Modules

#### Data Types
Type-safe opaque types for blockchain primitives with built-in codec support.
- [한국어 문서](ko/datatype/README.md) | [English Documentation](en/datatype/README.md)
- Includes: BigNat (arbitrary-precision naturals), UInt256 (256-bit unsigned), Utf8 (length-prefixed strings)
- Zero-cost abstractions with compile-time safety and automatic validation

#### Byte Codec
Deterministic byte encoding/decoding for custom blockchain implementations.
- [한국어 문서](ko/byte-codec/README.md) | [English Documentation](en/byte-codec/README.md)
- Essential for: Transaction signing, block hashing, state commitment, consensus mechanisms
- Guarantees identical byte representation across all nodes

#### JSON Codec
Library-agnostic JSON encoding/decoding for blockchain APIs and configuration.
- [한국어 문서](ko/json-codec/README.md) | [English Documentation](en/json-codec/README.md)
- Use cases: RPC API serialization, node configuration, off-chain data interchange
- Flexible backend support for seamless integration

#### Crypto
High-performance cryptographic primitives for blockchain applications.
- [한국어 문서](ko/crypto/README.md) | [English Documentation](en/crypto/README.md)
- Features: secp256k1 ECDSA, Keccak-256 hashing, signature recovery, Low-S normalization
- Cross-platform: Unified API for JVM (BouncyCastle) and JS (elliptic.js)

### API Documentation
- [Latest Release API](https://javadoc.io/doc/org.sigilaris/core_3/latest/index.html)
- [Development API](https://sigilaris.github.io/sigilaris/api/index.html)

### Coming Soon
- **Merkle Tree**: Efficient state verification and proof generation
- **Consensus Algorithms**: Pluggable consensus for private blockchain networks
- **P2P Networking**: Node discovery and communication protocols
- **State Management**: Persistent storage abstractions for blockchain state

## License

Sigilaris is dual-licensed to support both open-source and commercial blockchain projects:
- [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.en.html) for open source and public blockchain projects
- Commercial license available for private/enterprise blockchain deployments - contact [contact@sigilaris.org](mailto:contact@sigilaris.org)

## Links

- [GitHub Repository](https://github.com/sigilaris/sigilaris)
