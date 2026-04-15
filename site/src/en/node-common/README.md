# Node Common

`sigilaris-node-common` is the shared node contract layer between
`sigilaris-core` and runtime-specific node implementations. Its public surface
is cross-platform and lives under `org.sigilaris.node.gossip` and
`org.sigilaris.node.gossip.tx`.

## Current Baseline

- transport-neutral gossip/session contracts
- static peer registry and authenticator abstractions
- topic contract registry and canonical rejection model
- producer-session and polling state machinery
- transaction anti-entropy runtime logic shared across runtimes

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

## Current Limitations

- This layer does not ship a standalone daemon or transport implementation.
- Static peer topology is the baseline assumption inherited by the current
  runtime stack.
- Detailed runtime packaging and operator flow live above this layer.

## Related Pages

- [Node JVM](../node-jvm/README.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
