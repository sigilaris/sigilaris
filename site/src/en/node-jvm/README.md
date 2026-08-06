# Node JVM

`sigilaris-node-jvm` is the JVM runtime bundle layered on top of
`sigilaris-node-common`. It owns the current runtime lifecycle seams, config and
bootstrap assembly, HotStuff integration, Armeria HTTP transport adapters, and
SwayDB-backed persistence helpers.

## Current Baseline

- runtime lifecycle seams under `org.sigilaris.node.jvm.runtime.*`
- static peer topology and transport-auth configuration loaders
- Armeria transport adapters for session open, long-lived event streams,
  control batches, and bootstrap HTTP flows
- HotStuff bootstrap, catch-up, pacemaker, and artifact validation runtime
- transaction-pipeline admission, durable replay/convergence, and injectable
  identity strategy integration
- SwayDB-backed storage helpers for the current durable baseline

## Transaction Pipeline Admission Identity

New integrations should inject a `TxPipelineIdentityStrategy[F]` into
`TxPipelineAdmissionService`. Admission invokes it exactly once after request
normalization and reuses the returned hash/id pair throughout that submission.
The default is `TxPipelineIdentityStrategy.v1(identityScope)`.

The pre-0.2.12 constructor that accepts `TxPipelineIdGenerator` and
`identityScope` remains available. It adapts those arguments to the new single
identity-result path. `TxPipelineIdGenerator.deterministicSha256` consumes the
already calculated hash when its scope matches the constructor's
`identityScope`, so the legacy default behavior does not hash the normalized
identity payload twice. Historically mismatched scopes remain byte-compatible:
the pipeline id still uses the generator scope while the canonical hash uses
the admission scope. New integrations should keep the scopes equal. Existing
custom id generators remain source-compatible, but their callback now runs once
for every normalized submission, including idempotent replays and requests
rejected by later admission checks, because identity is calculated before
replay lookup and those checks.

Custom identity strategies must keep `canonicalPayloadHash` deterministic and
collision-resistant over the normalized request and identity scope. Admission
treats equal hashes as the same canonical request for replay, alias binding, and
keyless convergence. Keep the `pipelineId` and hash/id pair stable. Roll out
strategy changes across all writers/readers together; otherwise replay and
collision decisions can diverge across nodes.

## Section Guide

- [Bootstrap And Sync](bootstrap-and-sync.md) covers static trust-root
  verification, snapshot sync, and historical backfill.
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md) covers proposal/vote/QC
  flow, provider-backed autonomous proposals, and timeout/new-view progression.
- [Static Launch](static-launch.md) covers the reference smoke harness, minimal
  config shape, and operator-owned startup/restart notes.

## Current Limitations

- Static peer topology and static validator inventory remain the deployed
  baseline.
- Restart, fencing, and DR sequencing remain operator-managed.
- The current public repo ships a reference harness and library runtime, not a
  productized launcher/orchestrator.

## Follow-Up Work

- dynamic discovery and peer scoring
- validator-set rotation and broader trust-root policy evolution
- automatic failover and remote signer/KMS integration

## Related Pages

- [Node Common](../node-common/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
