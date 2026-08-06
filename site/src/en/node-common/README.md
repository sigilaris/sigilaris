# Node Common

`sigilaris-node-common` is the shared node contract layer between
`sigilaris-core` and runtime-specific node implementations. Its public surface
is cross-platform and lives under `org.sigilaris.node.gossip`,
`org.sigilaris.node.gossip.tx`, and `org.sigilaris.node.txpipeline`.

## Current Baseline

- transport-neutral gossip/session contracts
- static peer registry and authenticator abstractions
- topic contract registry and canonical rejection model
- producer-session, cursor, polling-compatibility, and streaming state machinery
- transaction anti-entropy runtime logic shared across runtimes
- normalized transaction-pipeline models and the cross-platform
  `TxPipelineIdentityStrategy` contract

The goal of this layer is to keep gossip protocol contracts and shared runtime
rules reusable without forcing JVM transport or storage details into the common
surface.

## When To Depend On It

Depend directly on `sigilaris-node-common` when you need:

- the shared gossip/session model on JVM and Scala.js
- topic contracts and artifact-source/sink abstractions
- transaction anti-entropy logic without the JVM runtime bundle

If you need Armeria transport, Typesafe config loading, SwayDB helpers, or the
HotStuff runtime assembly, move up to
[Node JVM](../node-jvm/README.md).

## Transaction Pipeline Identity

`TxPipelineIdentityStrategy[F]` calculates one `TxPipelineIdentity` from a
normalized request. The result contains both the durable
`canonicalPayloadHash` and the `pipelineId`; admission must reuse that same
result for replay checks, record creation, collision convergence, and
idempotency alias binding.

`TxPipelineIdentityStrategy.v1(identityScope)` preserves the existing
`bbgo.tx-pipeline.id.v1` preimage and lowercase SHA-256 output on both JVM and
Scala.js. The JVM uses its platform `MessageDigest` provider while Scala.js
uses the cross-platform implementation pinned to the same vectors. The strategy
excludes `waitFor`, includes the explicit scope, and derives the pipeline id as
`txp_` followed by the canonical payload hash. Embedders may provide a custom
strategy, but its canonical hash must be deterministic and collision-resistant
over the normalized request and identity scope. Admission treats equal hashes
as the same canonical request for replay, alias binding, and keyless
convergence. The pipeline id and hash/id pair must remain stable. Changing an
active strategy changes durable identity semantics and therefore requires
coordinated rollout and data compatibility planning.

## Current Limitations

- This layer does not ship a standalone daemon or transport implementation.
- Static peer topology is the baseline assumption inherited by the current
  runtime stack.
- Detailed runtime packaging and operator flow live above this layer.

## Related Pages

- [Node JVM](../node-jvm/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
