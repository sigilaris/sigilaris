# Sigilaris

Functional and secure cryptographic operations library for Scala.

## Overview

Sigilaris is a purely functional cryptographic library built with cats-effect ecosystem, providing type-safe cryptographic primitives for both JVM and JavaScript platforms.

## Features

- **Purely Functional**: Built on cats-effect for composable and referential transparent operations
- **Cross-Platform**: Supports both JVM and Scala.js
- **Type-Safe**: Leverages Scala's type system for compile-time safety
- **Secure**: Industry-standard cryptographic algorithms
- **Deterministic Byte Codec**: Blockchain-ready encoding with guaranteed consistency for hashing and signatures

## Getting Started

Add Sigilaris to your `build.sbt`:

```scala
libraryDependencies += "org.sigilaris" %%% "sigilaris-core" % "@VERSION@"
```

## Documentation

### Core Modules

#### Byte Codec
Deterministic byte encoding/decoding for blockchain applications.
- [한국어 문서](ko/byte-codec/README.md) | [English Documentation](en/byte-codec/README.md)
- Use cases: Transaction signing, block hashing, merkle tree construction

#### JSON Codec
Library-agnostic JSON encoding/decoding with customizable configuration.
- [한국어 문서](ko/json-codec/README.md) | [English Documentation](en/json-codec/README.md)
- Use cases: API serialization, configuration files, data interchange

### API Documentation
- [Latest Release API](https://javadoc.io/doc/org.sigilaris/sigilaris-core_3/latest/index.html)
- [Development API](https://sigilaris.github.io/sigilaris/api/index.html)

### Coming Soon
- Merkle Tree
- Consensus Algorithms
- Network Protocols

## License

Sigilaris is dual-licensed:
- [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.en.html) for open source projects
- Commercial license available - contact [contact@sigilaris.org](mailto:contact@sigilaris.org)

## Links

- [GitHub Repository](https://github.com/sigilaris/sigilaris)
