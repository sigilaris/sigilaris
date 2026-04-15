# Sigilaris

Sigilaris is a Scala toolkit for building deterministic blockchain applications
and running a static-baseline HotStuff node stack. The public repository
currently ships three layers:

- `sigilaris-core`
- `sigilaris-node-common`
- `sigilaris-node-jvm`

## Module Stack

- `sigilaris-core` provides deterministic codecs, cryptography, Merkle-state
  primitives, and the application/assembly model for composing stateful
  blockchain features.
- `sigilaris-node-common` adds the cross-platform gossip/session/bootstrap
  contract layer plus transaction anti-entropy runtime logic.
- `sigilaris-node-jvm` adds the JVM runtime bundle: lifecycle seams, config and
  bootstrap assembly, HotStuff integration, Armeria transport adapters, and
  SwayDB-backed storage helpers.

## Current Baseline

- Static peer topology is the current deployment baseline.
- The shipped transport enforces per-peer shared-secret HMAC proofs and
  session-bound bootstrap capability tokens.
- The HotStuff bootstrap baseline covers finalized-anchor suggestion discovery,
  static trust-root verification, snapshot sync, anchor-pinned forward catch-up,
  and low-priority historical backfill.
- Pacemaker timeout-vote, timeout-certificate, and new-view progression are
  part of the current JVM baseline.
- A repo-local reference smoke harness exercises static multi-node launch with
  `sbt "testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffLaunchSmokeSuite"`.

## Documentation

### English

- Core fundamentals:
  [Data Types](en/datatype/README.md),
  [Byte Codec](en/byte-codec/README.md),
  [JSON Codec](en/json-codec/README.md),
  [Crypto](en/crypto/README.md),
  [Merkle Trie](en/merkle/README.md)
- Application architecture:
  [Assembly DSL](en/assembly/README.md),
  [Application Module](en/application/README.md)
- Node runtime:
  [Node Common](en/node-common/README.md),
  [Node JVM](en/node-jvm/README.md)
- Other notes:
  [Performance](en/performance/crypto-ops.md)

### 한국어

- 코어 문서:
  [데이터 타입](ko/datatype/README.md),
  [바이트 코덱](ko/byte-codec/README.md),
  [JSON 코덱](ko/json-codec/README.md),
  [암호화](ko/crypto/README.md),
  [머클 트라이](ko/merkle/README.md)
- 애플리케이션 아키텍처:
  [Assembly DSL](ko/assembly/README.md),
  [애플리케이션 모듈](ko/application/README.md)
- 노드 런타임:
  [Node Common](ko/node-common/README.md),
  [Node JVM](ko/node-jvm/README.md)
- 기타:
  [성능 노트](ko/performance/crypto-ops.md)

### API Reference

- [Generated API Reference](https://sigilaris.github.io/sigilaris/api/index.html)

## Current Limitations

- Validator set rotation, dynamic peer discovery, automatic failover, and
  productized launcher/orchestrator packaging are not part of the current
  public baseline.
- Restart, fencing, and DR sequencing remain operator-managed.
- The current launch and consensus story assumes a static, same-DC style
  environment rather than an internet-scale deployment model.

## Build

The published narrative site and generated API are built together with:

```bash
sbt ";unidoc;tlSite"
```
