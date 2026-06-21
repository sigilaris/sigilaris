# 0022 - HotStuff Transitive Artifact Relay Plan

## Status

Published (v0.2.6)

## Created

2026-06-20

## Last Updated

2026-06-21

`Last Updated` reflects the newest date in `Revision Notes`.

## Revision Notes

- 2026-06-21: Refreshed after Plan 0021 completion. The relay plan now treats
  finalized tx range observations as part of the audit finalization acceptance
  surface, explicitly preserves the proposal tx uniqueness relay contract, and
  records the current append-only in-memory relay source as a concrete
  retention blocker before default-on validator relay.
- 2026-06-20: Initial plan for changing HotStuff consensus artifact relay
  semantics so a node can learn the consensus artifact stream through any live
  peer path in the connected component, instead of requiring direct
  subscriptions to every artifact-producing validator.

## Background

The current HotStuff gossip runtime separates artifact receipt from relay. A
node stores structurally valid incoming proposal, vote, timeout-vote, and
new-view artifacts in its local sink. It only republishes those validated
incoming artifacts to its local gossip source when `HotStuffRelayPolicy` enables
relay.

Today the role-derived relay policy enables validated-artifact relay only for
audit nodes:

- validators validate and store received artifacts, but do not re-publish them;
- audit nodes validate, store, and re-publish received artifacts;
- finalization is not a separate gossip topic; it is derived locally from the
  proposal chain and embedded QCs / locally assembled QCs.

That means a node attached to only one validator cannot assume that validator
will replay all consensus artifacts it learned from other validators. The
embedding application must either connect audit nodes to every artifact
producer or accept that audit nodes may miss parts of the proposal chain and
never observe finalization.

The desired model is more operationally forgiving:

- a node should be able to attach to one well-connected peer and eventually
  receive all validated consensus artifacts known in that peer's connected
  component;
- validators should behave as relay-capable gossip participants, not only as
  local validators;
- audit nodes should not need a full validator fanout just to materialize
  finalized blocks;
- when audit nodes observe finalization transitively, the Plan 0021 finalized
  tx range handoff (`currentFinalizedTxRangeObservations` and
  `recentFinalizedTxRangeObservations`) should advance on the audit runtime
  from the same relayed proposal chain, so embedders can evict finalized tx ids
  without direct validator fanout.

ADR-0018 still defines the initial production topology baseline: validators are
placed in the same data center with low-latency direct connectivity, while
remote nodes are normally audits. This plan does not replace that validator-core
assumption. Validator relay is an additive reachability feature, primarily so
audit nodes and sparse observers can learn the live consensus artifact stream
through a healthy path. If validators themselves are deployed sparsely enough
that relay becomes load-bearing for quorum formation, that is a different
deployment profile with a higher liveness and bandwidth risk surface.

## Goal

Change Sigilaris HotStuff relay semantics so validated consensus artifacts are
transitively relayed by default across validator and audit nodes.

The target outcome is:

- any node with at least one live path into the consensus gossip component can
  learn proposals, votes, timeout votes, and new-view messages from the whole
  component, subject to retention and catch-up limits;
- audit nodes can follow finalized blocks without direct sessions to every
  validator;
- audit nodes can consume finalized tx range observations for relayed proposal
  chains, subject to the same bounded in-memory handoff window defined by Plan
  0021;
- relay remains validation-gated and duplicate-suppressed;
- relay behavior is observable enough to diagnose stale peers, relay drops, and
  missing artifact chains;
- existing local signing, proposal validation, and application payload
  contracts remain unchanged.

## Scope

- Change the default HotStuff relay policy so validators relay validated
  consensus artifacts as well as audits.
- Keep validation-before-relay: a node only relays artifacts accepted by its
  HotStuff sink.
- Keep duplicate suppression by stable artifact id so transitive relay does not
  create infinite re-broadcast loops.
- Define whether the policy applies to all consensus artifact topics:
  proposal, vote, timeout-vote, and new-view.
- Add diagnostics for relayed artifact counts, duplicate drops, rejected
  artifacts, and relay policy mode.
- Add tests proving a downstream audit node can observe finalization through a
  single connected validator when the validator learned some artifacts from
  other validators.
- Extend those tests to assert the downstream audit node's finalized tx range
  handoff advances for the relayed chain.
- Update docs and release notes to describe the new relay contract and its
  limits.

## Non-Goals

- Do not guarantee delivery across network partitions or disconnected gossip
  components.
- Do not relay artifacts before structural validation.
- Do not relay application topics by default unless Phase 0 explicitly chooses
  to include them. This plan is primarily about HotStuff consensus artifacts.
- Do not add a dedicated finalization gossip topic. Finalization remains a local
  derivation from proposals and QCs.
- Do not change proposal, vote, timeout-vote, new-view, QC, or signature
  canonical bytes.
- Do not make every node connect to every peer.
- Do not replace bootstrap snapshot sync, forward catch-up, or historical
  backfill. Transitive relay improves live propagation but does not by itself
  solve unbounded offline catch-up.

## Related ADRs And Docs

- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](../adr/0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0021: Snapshot Sync And Background Backfill From Best Finalized Suggestions](../adr/0021-snapshot-sync-and-background-backfill-from-best-finalized-suggestions.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0029: HotStuff Proposal Tx Uniqueness Policy](../adr/0029-hotstuff-proposal-tx-uniqueness-policy.md)
- [0017 - HotStuff Application Proposal Validation Hook Plan](0017-hotstuff-application-proposal-validation-hook-plan.md)
- [0019 - HotStuff Application Gossip Topic Extension Plan](0019-hotstuff-application-gossip-topic-extension-plan.md)
- [0020 - Armeria Tapir Client Gossip Transport Plan](0020-armeria-tapir-client-gossip-transport-plan.md)
- [0021 - HotStuff Proposal Tx Uniqueness Policy Plan](0021-hotstuff-proposal-tx-uniqueness-policy-plan.md)

## Decisions To Lock Before Implementation

1. **Default relay policy**
   - Production default should relay validated consensus artifacts for both
     validator and audit roles.
   - The simplest policy shape is:

     ```scala
     HotStuffRelayPolicy(relayValidatedArtifacts = true)
     ```

     for every HotStuff role.
   - Phase 0 must decide whether to keep a role-derived helper such as
     `forRole`, replace it with an explicit `default`, or support both with
     `forRole` delegating to the same default.
   - If an opt-out mode remains, name it explicitly as local-only or
     non-transitive, and keep it out of production bootstrap defaults.

2. **Validator topology and load-bearing boundary**
   - ADR-0018's same-DC, low-latency validator placement remains the production
     baseline.
   - Validator relay must be treated as additive reachability for audits and
     sparse observers, not as a replacement for direct validator-core
     connectivity.
   - In a dense validator core, receiver-side deduplication prevents state
     loops and unbounded re-application, but it does not prevent duplicate
     network transmissions from being sent in the first place.
   - Sparse validator topologies that rely on relay for QC formation must be
     documented and tested as a separate deployment profile.
   - Sparse validator chains in this plan's tests are artificial validation
     topologies used to force transitive paths; they are not the production
     topology recommendation.

3. **Artifact scope**
   - Relay should cover all validated HotStuff consensus topics:
     - proposals;
     - votes;
     - timeout votes;
     - new-view messages.
   - This is consistent with the current sink implementation, where the same
     `relayValidatedArtifacts` flag controls relay envelopes for all four
     artifact families.
   - Phase 0 must decide whether future policies need per-topic switches or
     whether one all-consensus-artifacts switch is enough.

4. **Validation-before-relay**
   - A node relays only artifacts accepted by the sink's structural and
     configured proposal-validation path.
   - Rejected artifacts remain local diagnostics and must not be inserted into
     the relay source.
   - Relay candidacy is based on structural artifact acceptance and retention,
     not local vote eligibility.
   - Application proposal validation can suppress local votes, but structurally
     retained proposals remain relay candidates according to the existing
     validation contract.
   - Generic transaction uniqueness checks from ADR-0029 / plan 0021 can also
     suppress a local vote for a conflicting proposal. If that proposal is still
     structurally valid and retained, it remains a relay candidate for catch-up.
   - Plan 0021's finalized tx range observations are derived from retained
     proposals. Transitive relay must not special-case or suppress those
     observations; if the audit sink receives the finalized proposal chain, the
     latest and recent range handoff surfaces should update normally.

5. **Duplicate suppression and loop control**
   - Stable artifact id remains the deduplication boundary.
   - If the sink already knows an artifact, the duplicate apply result must not
     append another relay event.
   - Relayed artifacts receive local source cursors so peers can poll them from
     this node, but they keep their original stable ids and signed payloads.
   - No hop count or TTL is required for correctness if duplicate suppression
     is enforced before local re-publication.
   - Phase 0 must verify that every relay append path is reachable only from an
     `applied=true, duplicate=false` sink transition.

6. **Retention and catch-up boundary**
   - Transitive relay only covers artifacts retained in live gossip sources.
   - A node that was offline past retention still needs bootstrap discovery,
     snapshot sync, forward catch-up, or historical backfill.
   - The public contract should say "one live path into the connected component
     is sufficient for live propagation while artifacts remain retained", not
     "one peer can serve infinite history".
   - Before enabling validator relay by default, verify that the consensus
     artifact source/publisher retention is actually bounded. Observation-state
     ring buffers are not sufficient evidence; the relay source that receives
     re-published artifacts must have its own retention boundary or pruning
     strategy.
   - As of 2026-06-21, `InMemoryHotStuffArtifactSource` keeps per-chain/topic
     events in append-only `Vector`s. Phase 1 must add a cap or pruning policy
     for that source, or route relay through a bounded source, before enabling
     validator relay by default.

7. **Diagnostics**
   - Expose relay policy mode in runtime diagnostics.
   - Add counters or bounded diagnostics for:
     - relayed validated artifacts by topic;
     - duplicate artifacts suppressed before relay;
     - rejected artifacts not relayed by reason;
     - source read-by-id misses by topic;
     - peer subscription lag or stale cursor errors.
   - Diagnostics must not expose application payload bodies by default.

8. **Application topics**
   - Consensus artifact relay changes must not accidentally change application
     topic relay semantics.
   - Phase 0 must decide whether application-topic relay needs a separate plan.
   - Embedder-owned admission or application fanout topics are application
     topics and may keep their existing topic-specific source/sink semantics.

9. **Compatibility**
   - Existing tests that expected validators not to relay incoming artifacts
     should be updated to the new production contract.
   - If low-level tests require local-only behavior, create an explicit
     test-only relay policy rather than relying on validator role defaults.
   - Document any bandwidth or memory increase from validator relay.

10. **ADR requirement**
   - This changes the long-lived HotStuff gossip contract.
   - Phase 0 should either add a short ADR or amend an existing HotStuff gossip
     ADR so the transitive relay invariant is not only captured in this plan.
   - The ADR work must explicitly confirm that a role-neutral relay default does
     not conflict with ADR-0018's definition of audit nodes as relay-capable
     non-voting participants.

## Locked Phase 0 Decisions

- Production relay policy is default-on for every HotStuff role.
- `HotStuffRelayPolicy.forRole` remains as a compatibility helper, but it
  delegates to the same role-neutral production default for validators and
  audits.
- One relay switch covers all four HotStuff consensus artifact topics:
  proposal, vote, timeout-vote, and new-view.
- Application-topic relay remains out of scope for this plan.
- Validator relay is additive audit reachability and does not replace ADR-0018's
  same-DC validator core baseline.
- The in-memory HotStuff artifact source must gain bounded per-chain-topic
  retention before validator relay is enabled by default.
- Diagnostics live on the in-memory HotStuff artifact source and sink surfaces:
  source diagnostics expose retention, read-by-id misses, and stale cursor
  counts; sink diagnostics expose policy mode, successful relay counts,
  duplicate suppression counts, and rejected non-relay counts.
- Phase 4 will add a short ADR for the HotStuff transitive relay contract and
  explicitly call out ADR-0018 compatibility.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Policy.scala`
  - Change `HotStuffRelayPolicy.forRole` so validator and audit roles both
    relay validated artifacts by default, or replace role-derived policy with a
    role-neutral default.
  - Add explicit local-only/test policy names if needed.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`
  - Confirm relay append happens only for newly applied, validated artifacts.
  - Verify whether the artifact source/publisher used for relay has bounded
    retention. If it is unbounded, add a cap or pruning policy before enabling
    validator relay by default.
  - Add relay diagnostics hooks around successful relay, duplicate suppression,
    and rejection.
  - Include the finalized tx range observation surfaces in the propagation
    audit: relay should not require direct validator fanout for
    `recentFinalizedTxRangeObservations` to include the relayed finalization
    range.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala`
  - Wire the new default relay policy into bootstrap services.
  - Expose policy mode in diagnostics if bootstrap owns the runtime diagnostic
    shape.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffApplicationGossip.scala`
  - Keep consensus-topic subscription unchanged.
  - Document that subscription to one peer still includes all consensus topics;
    transitive behavior comes from peer relay, not a new topic.
- `modules/node-common/shared/src/main/scala/org/sigilaris/node/gossip/tx/TxGossipRuntime.scala`
  - Add or expose peer/source diagnostics only if existing runtime diagnostics
    are insufficient to understand relay lag.

### Tests

- Update low-level relay policy tests for the new role defaults.
- Add a multi-node gossip test with at least:
  - four validators connected as a sparse graph;
  - one audit node connected to only one validator;
  - a proposal/vote chain whose artifacts originate from validators not
    directly connected to the audit node;
  - assertion that the audit node eventually stores the proposal chain and
    observes finalization;
  - assertion that `currentFinalizedTxRangeObservations` and
    `recentFinalizedTxRangeObservations` expose the finalized tx ids from the
    relayed chain;
  - note that this sparse graph is a test harness for forcing relay hops, not
    the production validator-topology recommendation from ADR-0018.
- Add duplicate-loop tests:
  - relay an artifact around a cycle;
  - assert each node stores it once and does not append unbounded source events.
- Add rejection tests:
  - invalid artifacts are not relayed;
  - duplicate artifacts are not re-relayed.
- Add diagnostics tests for relay counters or bounded diagnostic entries.
- Add a compatibility test for explicit local-only/test relay policy if such a
  policy remains.

### Docs

- Add or update an ADR for the HotStuff transitive relay contract.
- Update developer handoff docs to state that validators relay validated
  consensus artifacts by default.
- Update release notes for the target version.
- Add deployment guidance: audit nodes need at least one healthy path into the
  consensus gossip component, not direct edges to every validator.
- Document that audit finalization observation lag grows with relay hop count
  because live propagation is pull-based: a node appends to its local source,
  then peers advance their subscription cursors and relay again.

## Implementation Phases

### Phase 0: Policy And Contract Lock

- Lock whether relay is default-on for every role or expressed by an explicit
  production relay profile.
- Lock whether policy remains role-derived or becomes role-neutral.
- Lock consensus artifact scope: all four HotStuff topics or per-topic flags.
- Lock application-topic non-goal and any separate follow-up needed for
  application topics.
- Lock the topology boundary: transitive relay is additive audit reachability
  and does not replace ADR-0018 same-DC validator connectivity.
- Verify bounded retention for the consensus artifact source/publisher that
  receives relayed artifacts, or make default-on validator relay conditional on
  adding such a bound.
- Lock diagnostics shape and whether relay counters live in HotStuff runtime
  diagnostics or lower-level gossip runtime diagnostics.
- Decide whether to write a new ADR or amend an existing ADR before code
  changes.

### Phase 1: Relay Policy Core

- Change the default relay policy so validators relay validated consensus
  artifacts.
- Add explicit local-only/test policy helpers where needed.
- Verify relay append paths are validation-gated and duplicate-gated.
- Add or tighten consensus artifact source retention before default-on if Phase
  0 finds the relay source is unbounded.
- Add focused unit tests for role/default policy behavior.

### Phase 2: Diagnostics

- Add relay diagnostics or counters at the chosen layer.
- Include topic, outcome, reason, and bounded counts without payload bodies.
- Add tests for successful relay, duplicate suppression, and rejected-artifact
  non-relay diagnostics.

### Phase 3: Multi-Node Propagation Tests

- Build a sparse topology where an audit node is connected to only one
  validator.
- State in the test fixture or test documentation that the sparse validator
  chain is test-only and intentionally differs from ADR-0018's production
  same-DC validator core.
- Emit artifacts from validators that are not audit direct neighbors.
- Drain gossip through the connected component.
- Assert the audit node receives the proposal chain and observes finalization.
- Assert the audit node's finalized tx range handoff includes the tx ids from
  the relayed finalized chain.
- Add a cycle test proving duplicate suppression prevents relay storms.

### Phase 4: Docs And Release Notes

- Add or update the ADR selected in Phase 0.
- Update handoff/deployment docs.
- Update release notes.
- Document migration from direct-audit-fanout assumptions to transitive relay.
- Document that audit observation latency is expected to scale with consensus
  relay hop count and peer polling cadence.

## Test Plan

- Run focused HotStuff suites covering:
  - gossip loopback / source-sink behavior;
  - launch smoke sparse topology;
  - pacemaker integration if relay policy construction is in bootstrap;
  - runtime bootstrap policy tests.
- Add a new sparse transitive relay scenario:
  - validator-a connected to validator-b;
  - validator-b connected to validator-c;
  - audit-a connected only to validator-a;
  - consensus artifacts originate at validator-c;
  - audit-a eventually reads them through validator-a after relay propagation.
  - this topology is intentionally sparse to test transitive behavior and is not
    the ADR-0018 production validator-core recommendation.
- Add finalization-specific assertion:
  - audit-a's sink has the proposals needed for the three-chain proof;
  - `currentFinalizationObservations` contains the finalized anchor;
  - `currentFinalizedTxRangeObservations` and
    `recentFinalizedTxRangeObservations` include the newly finalized proposal tx
    sets from the relayed chain.
- Add failure-path coverage:
  - invalid proposal is rejected and not relayed;
  - duplicate artifact around a cycle is applied once and not endlessly
    re-appended;
  - local-only test policy disables transitive propagation when explicitly
    configured.
- Run full `sbt "nodeJvm/test"` before closing the plan.

## Risks And Mitigations

- **Risk: relay storms or unbounded duplicate source events.**
  - Mitigation: relay only after a newly applied non-duplicate sink transition;
    add cycle tests and bounded diagnostics.

- **Risk: increased bandwidth and memory from validator relay.**
  - Mitigation: keep retention bounded, expose relay counters, and allow
    explicit local-only test profiles where needed.

- **Risk: dense validator cores see duplicate network transmissions.**
  - Mitigation: document that receiver-side dedup prevents loops and repeated
    application, not duplicate sends; expose counters so operators can measure
    amplification before tuning topology or relay policy.

- **Risk: artifact relay source retention is unbounded.**
  - Mitigation: verify source/publisher retention before default-on validator
    relay, and add a cap or pruning policy if the current source is unbounded.

- **Risk: sparse validator deployments accidentally make relay load-bearing for
  consensus liveness.**
  - Mitigation: keep ADR-0018 same-DC validator connectivity as the production
    baseline, and require separate documentation/tests for sparse validator
    deployment profiles.

- **Risk: relaying invalid or application-rejected artifacts.**
  - Mitigation: keep validation-before-relay and test that rejected artifacts
    never enter the relay source.

- **Risk: embedders misunderstand transitive relay as infinite catch-up.**
  - Mitigation: document retention limits and keep snapshot/backfill paths as
    the mechanism for offline or stale nodes.

- **Risk: application-topic relay semantics are accidentally widened.**
  - Mitigation: scope the code change to HotStuff consensus artifacts and
    document application-topic relay as a separate decision.

- **Risk: audit operators expect direct-validator latency from multi-hop relay.**
  - Mitigation: document that audit finalization observation lag is proportional
    to relay hop count and peer polling cadence.

- **Risk: tests relying on validator non-relay behavior become ambiguous.**
  - Mitigation: introduce explicit local-only/test relay policy names and update
    tests to request them directly.

## Acceptance Criteria

1. Validators and audits relay validated HotStuff consensus artifacts by
   default.
2. A node connected to one peer in a live connected component can receive
   proposal/vote/new-view/timeout-vote artifacts originated elsewhere in the
   component while those artifacts remain retained.
3. An audit node connected to only one validator can observe finalization for a
   proposal chain produced by validators outside its direct-neighbor set.
4. That audit node's finalized tx range handoff surfaces include the tx ids from
   the relayed finalized chain, so pending-pool eviction does not require direct
   validator fanout.
5. Invalid artifacts and duplicate artifacts are not re-relayed.
6. Relay behavior is visible through diagnostics or counters.
7. Documentation states the exact guarantee and its retention/connectivity
   limits.
8. Documentation states that validator relay is additive reachability and does
   not replace ADR-0018's production validator-core connectivity baseline.
9. Consensus artifact source retention is verified bounded, or default-on
   validator relay is blocked until a bound or pruning policy is added.
10. Deployment docs state that audit finalization observation lag can grow with
   relay hop count and peer polling cadence.

## Checklist

### Phase 0: Policy And Contract Lock

- [x] Production relay policy default locked.
- [x] Role-derived vs role-neutral policy API locked.
- [x] Consensus artifact topic scope locked.
- [x] Application-topic scope explicitly excluded or moved to follow-up.
- [x] Validator topology boundary documented against ADR-0018.
- [x] Consensus artifact source/publisher retention verified bounded, or a
      required retention fix is created for Phase 1.
- [x] Diagnostics shape locked.
- [x] ADR write/amend decision made, including ADR-0018 role/topology
      compatibility.

### Phase 1: Relay Policy Core

- [x] Validator default relay enabled.
- [x] Audit default relay preserved.
- [x] Explicit local-only/test policy helper added if needed.
- [x] Relay append audited for validation and duplicate gates.
- [x] Artifact source retention bound or pruning added if Phase 0 found a gap.
- [x] Focused policy tests pass.

### Phase 2: Diagnostics

- [x] Relay success diagnostics or counters added.
- [x] Duplicate suppression diagnostics or counters added.
- [x] Rejected-artifact non-relay diagnostics or counters added.
- [x] Diagnostic tests pass.

### Phase 3: Multi-Node Propagation Tests

- [x] Sparse topology relay test added.
- [x] Sparse relay test documented as a transitive-behavior harness, not a
      production topology recommendation.
- [x] Audit single-peer finalization observation test added.
- [x] Audit finalized tx range handoff test added for the relayed chain.
- [x] Relay cycle duplicate-suppression test added.
- [x] Focused HotStuff gossip/runtime tests pass.

### Phase 4: Docs And Release Notes

- [x] ADR added or amended.
- [x] Developer/deployment docs updated with topology boundary, retention
      limits, and hop-count latency expectations.
- [x] Release notes updated.
- [x] Full `sbt "nodeJvm/test"` passes or failures are documented.

## Follow-Ups

- Consider a separate application-topic relay policy if embedders need the same
  transitive behavior for application gossip topics.
- Consider persistent relay diagnostics if operators need post-restart delivery
  analysis.
- Consider explicit relay-retention tuning per topic after observing production
  bandwidth and memory behavior.
- Consider validator-neighbor-aware relay suppression if production diagnostics
  show dense-core duplicate transmissions are material.
