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
- autonomous leader proposals can consume application-neutral input through a
  provider-backed proposal hook

## Runtime Boundary

The runtime page is intentionally separate from the transport page:

- gossip/session contracts come from `sigilaris-node-common`
- JVM transport adapters live under `org.sigilaris.node.jvm.transport.armeria`
- consensus runtime and validation live under
  `org.sigilaris.node.jvm.runtime.consensus.hotstuff`

That split is important because the current repository treats HotStuff logic as
runtime-owned, while transport remains an adapter layer.

## Application Proposal Input

Autonomous pacemaker proposal emission is no longer limited to synthetic empty
blocks. Embedders can pass a `HotStuffProposalInputRuntimeConfig` to the
in-memory runtime helper or the assembled bootstrap entrypoints. The configured
`HotStuffProposalInputProvider` receives HotStuff context only: window, proposer,
parent, height, justify QC, local time, and proposal bounds.

The provider returns a `HotStuffProposalInput` containing the proposal tx-set and
block-header commitments that Sigilaris can sign. Application-specific queues,
lanes, manifests, and fairness rules stay outside `sigilaris-node-jvm`; embedders
adapt those concepts to the HotStuff-owned input contract.

Legacy empty proposals remain available through the explicit
`AllowLegacyEmpty` fallback policy. Production embedders that require
application input can select `RequireProviderInput`; automatic consensus then
fails visibly when no provider is configured and suppresses fallback when the
provider reports no work, rejection, or failure.

Provider no-work, rejection, failure, invalid input, and fallback behavior are
recorded in pacemaker diagnostics as reason/detail metadata with a
`fallbackUsed` flag. Diagnostics intentionally do not include application
payload bodies.

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

- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [Bootstrap And Sync](bootstrap-and-sync.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
