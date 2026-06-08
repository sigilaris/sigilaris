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
- local proposal votes can be gated by an application-neutral validation
  provider before the validator signs

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
payload bodies. Unexpected provider exceptions use the exception class name as
detail, not the exception message.

## Application Proposal Validation

Proposal validation is separate from proposal input. The input provider is used
only when the local node is the leader and needs proposal body data. The
validation provider is used when a node is about to sign a local vote for a
received proposal.

Embedders can pass a `HotStuffProposalValidationRuntimeConfig` to
`HotStuffNodeRuntime` or the assembled bootstrap entrypoints. The configured
`HotStuffProposalValidationProvider` receives HotStuff context only: the
proposal, local voter, validation time, and validator set. It returns
`Accepted`, `Rejected`, `Unavailable`, or `Failed`.

Rejected, unavailable, failed, or missing-required validation never signs a
local proposal vote. `legacyCompatible` keeps the previous allow-all behavior.
Production embedders can select `requireProvider(provider)` or
`requireValidationProvider`; automatic consensus fails fast if validation is
required but no provider is configured.

Validation diagnostics are recorded in pacemaker snapshots with window,
proposal id, block id, local voter, outcome, reason/detail, and whether the
vote was suppressed. Proposal payload bodies are not included. Structural
artifact retention stays in the HotStuff sink: a structurally valid proposal can
remain retained even when local application validation suppresses a vote.

## Finalization Observability

`HotStuffNodeRuntime.currentFinalizationObservations` exposes, per chain, the
current best-finalized anchor observation as a `FinalizedAnchorObservation`. The
accessor is always available and returns an empty map when no in-memory
diagnostic sink is present.

Each observation carries the finalized anchor identity (`chainId`,
`proposalId`, `blockId`, `height`), the finalization proof (`childProposalId`,
`grandchildProposalId`),
the anchor proposal window validator-set hash (`validatorSetHash`), and two
local timestamps. `proposalObservedAt` is the local first acceptance time for
the anchor proposal, and `finalizedObservedAt` is the local first observation
time for the verified finalized anchor.

These timestamps are local-runtime observation times from the runtime clock.
They are not the gossip producer `event.ts` timestamp and not a claim about
when a quorum objectively finalized. Consensus-layer finalization latency can
be derived as `finalizedObservedAt - proposalObservedAt`.

Consensus finalization observability stays separate from application
materialization. Materialization timing, retry, and terminal policy remain
embedder-owned, and v0.2.3 adds no materialization hook.

Safety faults remain separate high-severity diagnostics. A faulted height is
excluded from the observation map while the fault remains visible through
existing diagnostics. Pacemaker timing values are liveness policy, not
finalization SLAs.

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
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](https://github.com/sigilaris/sigilaris/blob/main/docs/adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [Bootstrap And Sync](bootstrap-and-sync.md)
- [Static Launch](static-launch.md)
- [API Reference](https://sigilaris.github.io/sigilaris/api/index.html)
