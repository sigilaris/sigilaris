# Node JVM

`sigilaris-node-jvm` is the JVM runtime bundle layered on top of
`sigilaris-node-common`. It owns the current runtime lifecycle seams, config and
bootstrap assembly, HotStuff integration, Armeria HTTP transport adapters, and
SwayDB-backed persistence helpers.

## Current Baseline

- runtime lifecycle seams under `org.sigilaris.node.jvm.runtime.*`
- static peer topology and transport-auth configuration loaders
- Armeria transport adapters for session open, event polling, control batches,
  and bootstrap HTTP flows
- HotStuff bootstrap, catch-up, pacemaker, and artifact validation runtime
- SwayDB-backed storage helpers for the current durable baseline

## Section Guide

- [Bootstrap And Sync](bootstrap-and-sync.md) covers static trust-root
  verification, snapshot sync, and historical backfill.
- [HotStuff And Pacemaker](hotstuff-and-pacemaker.md) covers proposal/vote/QC
  flow plus timeout/new-view progression.
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
