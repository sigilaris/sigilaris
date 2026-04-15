# HotStuff And Pacemaker

This page summarizes the current HotStuff runtime that ships in
`sigilaris-node-jvm`.

## Current Baseline

- proposal, vote, and quorum-certificate artifact modeling lives in the JVM
  runtime package
- consensus gossip uses exact-known windows, bounded `requestById`, and
  consensus-priority QoS
- canonical block modeling is split into header/body/view artifacts with a
  consensus-neutral block-store seam
- pacemaker timeout-vote, timeout-certificate assembly, and new-view progression
  are part of the shipped runtime
- deterministic leader activation, timer/backoff wiring, and timeout/view-change
  advancement are runtime-owned rather than manual test hooks

## Runtime Boundary

The runtime page is intentionally separate from the transport page:

- gossip/session contracts come from `sigilaris-node-common`
- JVM transport adapters live under `org.sigilaris.node.jvm.transport.armeria`
- consensus runtime and validation live under
  `org.sigilaris.node.jvm.runtime.consensus.hotstuff`

That split is important because the current repository treats HotStuff logic as
runtime-owned, while transport remains an adapter layer.

## Current Limitations

- the validator set is still static
- the deployment model is still static-topology and same-DC oriented
- the runtime is not presented as a full operator product with automated
  orchestration

## Follow-Up Work

- validator rotation and broader trust-root evolution
- recovery and failover automation beyond the current operator-managed baseline
- additional long-lived reference documentation if the runtime surface settles

## Related Pages

- [Bootstrap And Sync](bootstrap-and-sync.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
