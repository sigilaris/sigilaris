# 0027 - HotStuff Gossip Sink Retention And Memory Pressure Plan

## Status
Draft

## Created
2026-06-26

## Last Updated
2026-06-26

## Background

A remote downstream deployment reported synchronous transaction latency above
six seconds and token-definition API timeouts. The immediate user-visible
symptom was that `/tx?waitFor=persisted` did not return until finalization. For
the observed application request, the batch was accepted quickly but spent most
of its time between certification and finalization:

- received: `2026-06-26T07:38:46.143Z`;
- certified: `2026-06-26T07:38:49.426Z`;
- finalized: `2026-06-26T07:38:52.214Z`;
- total persisted wait: about 6.1 seconds.

A later token-definition batch reached certification on all validators but did
not finalize. The cluster showed finalized chain height 17 while height 18
remained certified. Validator-c was behind the other validators at the HotStuff
pacemaker level:

- validator-a/b/d had progressed to active height 19 with highest QC height 18;
- validator-c remained around active height 16 and highest QC height 15;
- validator-c emitted repeated elevated timeout diagnostics and timeout
  windows;
- both remote hosts showed high Java CPU and cats-effect starvation warnings.

The heap and GC evidence supports a retained consensus/gossip artifact
hypothesis rather than a transient allocation spike:

- all four validators had old generation usage near the configured 2 GiB heap
  limit;
- full GC did not materially reduce old generation occupancy;
- class histograms were dominated by `ByteVector`, `HotStuffWindow`,
  `TimeoutVote`, `NewView`, `Vote`, `GossipEvent`, `Signature`, and
  quorum/timeout certificate objects;
- object counts were very similar across validators, which suggests a
  deterministic retention path shared by the cluster rather than a single-node
  leak.

This evidence does not, by itself, prove whether the dominant retainer is the
source event log, the sink snapshot, or another structure that references the
same artifact bodies. Phase 0 must confirm the retaining path with heap
dominator or reference-path analysis before treating sink pruning as the sole
root-cause fix.

The current in-memory HotStuff artifact source is bounded. `SourceTopicState`
drops older events according to `HotStuffArtifactSourceRetention.default`, which
keeps 4096 events per chain topic. In the current model a `ChainTopic` is keyed
by `(chainId, topic)`, not by window or validator, but Phase 0 still needs to
confirm the deployed topic cardinality and application topic usage. The sink
side has no comparable bound:

- `InMemoryHotStuffSinkSnapshot` stores proposals, votes, vote accumulators,
  timeout votes, timeout vote accumulators, timeout certificates, new-view
  messages, QCs, finalization snapshots, duplicate event samples, and sink
  diagnostics;
- duplicate events are appended to an unbounded `Vector`;
- accepted proposal, vote, timeout-vote, and new-view events update maps and
  accumulators without pruning;
- `withFinalization` already derives `snapshot.finalization`, which can supply
  finalized-height watermark data, but that data is not currently used to
  remove old sink artifacts.

The in-memory implementation has been useful as a deterministic test/runtime
baseline, but remote long-running validators now rely on it for the HotStuff
artifact plane. That makes unbounded sink retention an operational memory and
liveness risk.

## Goal

Bound the HotStuff in-memory gossip sink so a long-running validator can survive
view changes, timeout storms, duplicate relays, and ordinary consensus progress
without retaining every historical artifact forever.

The target behavior is:

1. source and sink retention are both explicit, configurable, and visible in
   diagnostics;
2. old proposals, votes, timeout votes, new views, QCs, timeout certificates,
   accumulators, and duplicate records are pruned after they are no longer
   needed for safety, finalization, bootstrap diagnostics, or recent catch-up;
3. retention never removes data still needed to assemble a QC, assemble a
   timeout certificate, validate a descendant, derive finalization, or serve a
   bounded recent gossip request;
4. sustained timeout windows do not cause linear unbounded heap growth or
   growing full-scan QC/TC assembly cost;
5. `/status` and in-memory diagnostics expose enough counts to identify memory
   pressure before GC starvation stalls the pacemaker.

## Scope

- Add an explicit `HotStuffArtifactSinkRetention` policy.
- Implement sink pruning for proposals, votes, vote accumulators, timeout
  votes, timeout accumulators, timeout certificates, new views,
  `newViewsBySenderWindow`, QCs, and duplicates.
- Define pruning anchors based on finalized height, highest known certified
  height/window, and a bounded safety margin.
- Confirm source versus sink retainership with heap dominator/reference-path
  analysis before relying on sink pruning as the complete memory fix.
- Add sink retention counters and current retained-size diagnostics.
- Wire the retention policy through runtime bootstrap and in-memory service
  construction.
- Add deterministic tests that reproduce many views, timeouts, duplicates, and
  finalization progress while asserting bounded sink size.
- Update operator docs with memory-pressure diagnostics and restart guidance.

## Non-Goals

- Do not change HotStuff voting safety, QC validation, timeout certificate
  validation, or finalization rules.
- Do not change downstream transaction semantics, `/tx` wait semantics, token
  definition APIs, or application reducers.
- Do not introduce a durable production consensus artifact database in this
  plan.
- Do not make heap-size increases the primary fix. Larger heaps may remain a
  temporary mitigation only.
- Do not remove recent artifacts needed by newcomer bootstrap, bounded gossip
  replay, or proposal dependency backfill.
- Do not guarantee a 500 ms remote downstream latency target. This plan removes
  an unbounded memory and liveness failure mode; latency tuning remains
  separate.

## Related ADRs And Docs

- [ADR-0016: Multiplexed Gossip Session Sync](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](../adr/0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [ADR-0030: HotStuff Transitive Artifact Relay](../adr/0030-hotstuff-transitive-artifact-relay.md)
- [0022 - HotStuff Transitive Artifact Relay Plan](0022-hotstuff-transitive-artifact-relay-plan.md)
- [0024 - HotStuff Bounded Descendant Finality Drive Plan](0024-hotstuff-bounded-descendant-finality-drive-plan.md)
- [0026 - HotStuff Proposal Application Artifact Prefetch Plan](0026-hotstuff-proposal-application-artifact-prefetch-plan.md)
- [HotStuff Gossip Sink Retention Runbook](../dev/hotstuff-gossip-sink-retention-runbook.md)

## Current Implementation Gap

### Source Retention Is Bounded

`HotStuffArtifactSourceRetention.default` keeps a finite event tail per
`ChainTopic`. When the source appends a new event, `SourceTopicState.append`
drops older events beyond `retainedEventsPerTopic` and updates pruning
diagnostics.

This protects the producer-side event log, but it does not protect the sink
state that validates and stores incoming artifacts.

Current code keys a source stream by `ChainTopic(chainId, topic)`, so consensus
source retention is not window-keyed or validator-keyed. The implementation
phase must still verify deployed topic cardinality because application topics
and multiple chains can multiply the 4096-event tail. Heap histograms alone
cannot distinguish source-retained artifact bodies from sink-retained artifact
bodies.

### Sink Retention Is Unbounded

`InMemoryHotStuffSinkSnapshot` currently holds the following state:

- `proposals: Map[ProposalId, Proposal]`;
- `votes: Map[VoteId, Vote]`;
- `accumulator: VoteAccumulator`;
- `timeoutVotes: Map[TimeoutVoteId, TimeoutVote]`;
- `timeoutAccumulator: TimeoutVoteAccumulator`;
- `timeoutCertificates: Map[TimeoutVoteSubject, TimeoutCertificate]`;
- `newViews: Map[NewViewId, NewView]`;
- `newViewsBySenderWindow: Map[(HotStuffWindow, ValidatorId), NewView]`;
- `qcs: Map[ProposalId, QuorumCertificate]`;
- `finalization: Map[ChainId, FinalizationTrackerSnapshot]`;
- `duplicates: Vector[GossipEvent[HotStuffGossipArtifact]]`;
- `diagnostics: InMemoryHotStuffSinkDiagnostics`.

The artifact maps, accumulators, and duplicate vector are the main unbounded
retention candidates. The `finalization` map and `diagnostics` field are not
the primary growth vectors, but they directly shape this plan: existing
finalization snapshots should be used as pruning watermark input, and the
existing diagnostics class should be extended rather than replaced.

The vote and timeout accumulators duplicate much of the retained vote state in
their own maps. Their `votesFor` methods perform full scans over retained maps,
so retained history also increases CPU cost during QC/TC assembly. That CPU
cost is directly relevant to the observed pacemaker starvation hypothesis, not
only to heap pressure.

### Finalization Data Exists But Does Not Trigger Artifact Eviction

`withFinalization` already derives `snapshot.finalization` from
`HotStuffFinalizationTracker.trackAll(snapshot.proposals.values)`. That means
the finalized-height watermark source already exists inside the sink snapshot.
The missing behavior is using that derived state to evict old proposals, QCs,
votes, or timeout artifacts. Once a height is finalized, most older per-view
artifacts are no longer needed for normal progress, yet remain live in the
sink.

Pruning must preserve this ordering invariant: derive finalization from the
pre-prune proposal set, fix the finalized/certified watermarks and any proposal
IDs required by the retained finalization/QC chain, then prune artifacts that
fall outside the retained set. A regression test must prove that pruning does
not prevent subsequent finalization derivation.

### Duplicate Tracking Is Not A Bounded Diagnostic Tail

Duplicate artifacts are useful for tests and diagnostics, but storing every
duplicate event body is expensive. The deployed remote cluster saw repeated
peer poll cancellations and timeout windows; in that condition unbounded
duplicate retention can amplify memory pressure while adding little operator
value. Because `recordDuplicate` already increments
`InMemoryHotStuffSinkDiagnostics.duplicateArtifactsSuppressedByTopic`,
production defaults should retain zero or only a very small bounded body sample
while preserving counters.

### Retainer Attribution Is Not Yet Proven

The observed class histograms are compatible with both source and sink
retention because both can reference `ByteVector`, `GossipEvent`, vote,
timeout-vote, proposal, and certificate objects. Phase 0 must therefore capture
one of the following before implementation is treated as root-cause complete:

- a heap dump dominator tree showing the dominant retained path;
- a reference-path sample for representative `TimeoutVote`, `NewView`,
  `GossipEvent`, and `ByteVector` instances;
- source diagnostics showing retained events by `ChainTopic` and ruling out a
  source-topic cardinality explosion.

## Proposed Design

### Sink Retention Policy

Introduce a sink-side retention policy next to the existing source retention:

```scala
final case class HotStuffArtifactSinkRetention(
    finalizedHeightLag: Long,
    certifiedHeightLag: Long,
    retainedTimeoutWindows: Long,
    retainedNewViewWindows: Long,
    retainedDuplicateEvents: Int,
    retainedRejectedEventSamples: Int,
)
```

The exact field names can change during implementation, but the policy must
separate:

- finalized-history retention for proposals, votes, QCs, and finalization
  derivation;
- certified-history retention for recent but not yet finalized branches;
- timeout/new-view retention by HotStuff window;
- duplicate/rejection sample retention as diagnostic tails.

Defaults are conservative and retain more history than the protocol minimum:
`finalizedHeightLag = 128`, `certifiedHeightLag = 128`,
`retainedTimeoutWindows = 256`, `retainedNewViewWindows = 256`,
`retainedDuplicateEvents = 16`, and `retainedRejectedEventSamples = 0`.
`certifiedHeightLag` must be greater than or equal to `finalizedHeightLag`.
Height artifacts use the highest known finalized/certified floor, so the
certified floor can still bound post-finalization stalls while explicit
finalization-proof proposal protection preserves the live proof chain.
Duplicate counters remain available through diagnostics even when the retained
body sample is small.

### Pruning Watermarks

Compute pruning watermarks from available sink state:

- best finalized height per chain from the existing `snapshot.finalization`;
- highest certified height/window observed locally;
- active bootstrap/finality anchors that must remain inspectable;
- configured lag margins.

The implementation should not invent a parallel finalized-height tracker unless
the existing `finalization` map is proven insufficient. It should first derive
or refresh finalization, capture the watermark values and the retained proposal
IDs needed by finalization/QC chains, and only then prune old artifacts.

Proposals and QCs older than the highest known finalized/certified retention
floor can be eligible for pruning unless they are referenced by a retained QC
chain or an active bootstrap/finality diagnostic anchor.

Votes can be pruned when their target proposal is pruned or when their
proposal/window is older than any known finalized or certified retention
watermark. The certified watermark still bounds retained height artifacts if
finalization stalls after a finalized anchor has already been established.

Timeout votes, timeout certificates, and new views should primarily prune by
window, retaining only recent windows around the latest active/pacemaker window
plus a configured lag. Timeout artifacts are the dominant storm path, so their
bound must not depend only on finalized height.

### Accumulator Pruning

`VoteAccumulator` and `TimeoutVoteAccumulator` need pruning APIs rather than
only replacing the outer snapshot maps. The accumulator maps must be pruned in
lockstep with `votes` and `timeoutVotes`; otherwise old vote objects remain
live through accumulator references.

Required operations:

- remove votes by ID and equivocation key using a predicate or retained ID set;
- remove timeout votes by ID and equivocation key using a predicate or retained
  ID set;
- expose retained counts for diagnostics and tests.

Accumulator pruning intentionally drops historical equivocation-key entries for
pruned votes. The in-memory sink preserves bounded duplicate/equivocation
detection inside the retained window; durable evidence remains the
responsibility of audit/history surfaces, not the bounded relay sink.

### Pruning Trigger Points

Pruning should run after state-changing sink applies, not on every read:

- after accepting a proposal;
- after accepting a vote and possibly assembling a QC;
- after accepting a timeout vote and possibly assembling a TC;
- after accepting a new-view artifact;
- after deriving a new finalization observation;
- optionally after recording duplicate samples.

For proposal and vote paths, the required order is:

1. validate and store the newly accepted artifact;
2. assemble any newly available QC or TC;
3. run `withFinalization` on the unpruned proposal set;
4. compute retention watermarks and retained chain IDs;
5. prune eligible artifacts and accumulators.

To keep per-event overhead bounded, the implementation can use a cheap
`maybePrune` gate based on event count, height/window movement, or map size
threshold. The first implementation may prune every accepted event if tests
show the cost is small, but the design should leave room for gating.

### Diagnostics

Extend the existing `InMemoryHotStuffSinkDiagnostics` with retained-size and
prune counters. It currently records relay, duplicate, and rejection counts; the
new fields should add memory-pressure visibility without replacing those
existing counters.

- current counts for proposals, votes, timeout votes, timeout certificates,
  new views, QCs, duplicate samples, and accumulator entries;
- total pruned counts by artifact kind;
- latest finalized and certified retention watermarks;
- latest pruning reason, if useful;
- retention policy values.

These diagnostics must be accessible through existing in-memory diagnostics and
runtime diagnostics handles used by embedders, so operators can distinguish:

- source event-log growth;
- sink artifact growth;
- timeout/new-view storm growth;
- duplicate/rejection sample growth.

The current node-jvm tree does not define a first-class `/status` endpoint for
HotStuff runtime diagnostics. Deployments that already wrap runtime diagnostics
in a status endpoint should render the sink fields there without exposing
artifact bodies.

### Configuration

Wire the policy through runtime construction with safe defaults:

- `HotStuffNodeRuntime.inMemoryServices`;
- `HotStuffRuntimeBootstrap` and assembled node bootstrap paths;
- any node JVM config loader that currently configures source retention or
  gossip policy.

If no external configuration path exists yet, add one in the node runtime config
namespace and document the default values. Test helpers should keep default
arguments so existing suites can opt in only when they need a smaller bound.

## Decisions To Lock Before Implementation

1. **Pruning floor**
   - Use a default finalized-height lag of 128 as the finalized floor input and
     safety-fault fallback margin. Height artifact retention uses the highest
     known finalized/certified floor, and the current finalization proof is
     protected explicitly by proposal/QC references.
   - Relayed height-based artifacts are pruned when older than any known
     finalized/certified floor for the chain. If finalization is stuck, the
     certified floor still bounds proposals, votes, QCs, and safety-fault
     diagnostics even when a stale finalized floor is present. If no floor
     remains, the sink falls back to retaining only the newest
     `max(finalizedHeightLag, certifiedHeightLag)` safety-fault heights.
   - `certifiedHeightLag` is validated to be at least `finalizedHeightLag`.
     This keeps the certified lag no shorter than the finalized lag while still
     allowing the certified floor to advance above a stale finalized floor
     during long stalls.
   - This may deserve an ADR update if the retained-finalized-history contract
     becomes part of the long-term embedder API.

2. **Timeout-window retention**
   - Retain the most recent 256 observed timeout windows and the most recent
     256 observed new-view windows per chain. The selected source is observed
     artifact windows, so retention remains bounded when finalization is stuck.

3. **Duplicate diagnostics**
   - Production must not retain an unbounded full-event duplicate body vector.
     The default retains a 16-event bounded sample.
   - Preserve duplicate counters in `InMemoryHotStuffSinkDiagnostics` regardless
     of the sample size.

4. **Default values**
   - The initial defaults favor long-running four-validator safety margins over
     minimum memory use and bound timeout/new-view storm retention by window.

5. **Status compatibility**
   - Sigilaris exposes additive runtime diagnostics. Downstream `/status`
     surfaces should render those counts and policy values without including
     artifact bodies.

## Change Areas

### Code

- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`
  - add sink retention policy, pruning implementation, and diagnostics;
  - apply pruning after sink state mutations;
  - bound duplicate retention.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Validation.scala`
  - add pruning/count APIs to `VoteAccumulator` and
    `TimeoutVoteAccumulator`.
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`
  - wire sink retention through in-memory service construction.
- Runtime bootstrap/config files
  - expose policy defaults and optional configuration knobs.
- Status/diagnostic model files
  - surface sink retained sizes and prune counters to operators.

### Tests

- Add unit tests for accumulator pruning.
- Add sink retention tests for proposals, votes, QCs, timeout votes,
  timeout certificates, new views, and duplicates.
- Add an integration-style stress test that generates many timeout windows and
  verifies retained counts stay bounded.
- Add finalization regression tests proving pruning does not remove artifacts
  needed for QC assembly, TC assembly, finalization derivation, or recent
  bootstrap diagnostics.

### Docs

- Update HotStuff operator/runtime docs with the new retention policy and
  diagnostics.
- Update release notes for the version that ships this fix.
- Add a remote incident note or progress update in downstream-owned docs after
  the patched cluster is measured.

## Implementation Phases

### Phase 0: Reproduction And Retention Contract

- Preserve the remote incident evidence in a local note or test fixture:
  heap histogram summary, GC saturation, validator-c pacemaker lag, and the
  certified-but-not-finalized token-definition batch.
- Confirm the dominant retainer with heap dominator or reference-path analysis
  for representative `ByteVector`, `GossipEvent`, `TimeoutVote`, `NewView`, and
  vote/certificate objects.
- Confirm deployed source topic cardinality and the current `ChainTopic`
  keying so `4096 * topicCount` source retention is separated from sink
  retention.
- Define `HotStuffArtifactSinkRetention` fields and default values.
- Decide finalized-height and timeout-window retention watermarks.
- Identify every sink structure that can retain proposal/vote/timeout/new-view
  payloads directly or indirectly.

### Phase 1: Core Sink Pruning

- Add accumulator pruning APIs and tests.
- Add `InMemoryHotStuffSinkSnapshot.prune` or equivalent pure helper.
- Preserve the finalization-before-pruning invariant: run `withFinalization`,
  capture watermarks and retained chain IDs, then prune.
- Prune proposals, votes, QCs, timeout votes, timeout certificates, new views,
  `newViewsBySenderWindow`, accumulators, and duplicate samples.
- Evaluate pruning after accepted sink events and finalization updates; run the
  full prune pass immediately when height watermarks move or when the accepted
  event cadence threshold is reached.
- Keep relay/rejection behavior unchanged.

### Phase 2: Diagnostics And Configuration

- Extend sink diagnostics with retained counts, prune counters, watermarks, and
  policy values.
- Expose diagnostics through existing in-memory runtime handles and node status.
- Wire sink retention through `HotStuffNodeRuntime.inMemoryServices`,
  bootstrap assembly, and node config.
- Document the default profile and operator tuning guidance.

### Phase 3: Stress Verification

- Add a deterministic timeout-storm test that advances many views without
  finalizing every view and asserts bounded timeout/new-view retention.
- Add a long-running finalization progress test that asserts proposals/votes/QCs
  are retained only inside the configured lag and that finalization derivation
  still works after pruning.
- Add a duplicate relay test that confirms duplicate samples are capped.
- Run the relevant node-jvm HotStuff suites and targeted downstream remote smoke
  after deployment.

### Phase 4: Remote Rollout And Measurement

- Build and deploy the patched downstream node assembly built on Sigilaris to
  the remote four-validator cluster.
- Restart validators to clear already-retained old heap state.
- Verify `/status` shows bounded sink counts after warm-up and after token
  definition traffic.
- Re-run the token-definition and `/tx?waitFor=persisted` checks.
- During timeout/catch-up traffic, compare retained counts, pruned counters,
  Java CPU, and cats-effect starvation logs against
  `pruneEveryAcceptedEvents` to decide whether the cadence needs tuning.
- Capture `jstat`, `jcmd GC.heap_info`, and class histogram samples after the
  cluster has processed enough views to demonstrate bounded retention.

## Test Plan

- `sbt --error "nodeJvm / Test / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffValidationSuite"`
  for accumulator pruning.
- Create a focused
  `org.sigilaris.node.jvm.runtime.consensus.hotstuff.InMemoryHotStuffSinkRetentionSuite`
  and run it with
  `sbt --error "nodeJvm / Test / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.InMemoryHotStuffSinkRetentionSuite"`
  for sink pruning behavior.
- `sbt --error "nodeJvm / Test / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffFinalizationSuite"`
  to ensure pruning does not regress finalization derivation.
- `sbt --error "nodeJvm / Test / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffLaunchSmokeSuite"`
  for assembled multi-node runtime behavior.
- Downstream application smoke:
  - single transaction with `waitFor=persisted`;
  - token definition submit;
  - dependent-transaction low-latency smoke if the cluster is otherwise healthy.
- Remote JVM validation:
  - old generation should not remain pinned near heap max after Full GC;
  - heap dominator or reference-path samples should no longer show unbounded
    sink structures as dominant retainers for HotStuff artifacts;
  - source retained counts by `ChainTopic` should match expected topic
    cardinality;
  - HotStuff sink retained counts should plateau under sustained view changes;
  - timeout/new-view object counts should remain within the configured window
    bound.

## Risks And Mitigations

- **Risk: pruning removes data still needed for finalization or validation.**
  - Mitigation: keep conservative defaults, derive finalization before pruning,
    capture retained chain IDs, and add regression tests that finalize after
    pruning.

- **Risk: sink pruning fixes a real bug but not the dominant heap root.**
  - Mitigation: require Phase 0 dominator/reference-path analysis and source
    topic-cardinality diagnostics before claiming root-cause closure.

- **Risk: timeout artifacts still grow while finalization is stuck.**
  - Mitigation: prune timeout votes, timeout certificates, and new views by
    recent HotStuff windows, not only by finalized height.

- **Risk: accumulator maps retain objects even after outer maps are pruned.**
  - Mitigation: add explicit accumulator pruning APIs and test retained counts.

- **Risk: pruning on every event adds CPU overhead.**
  - Mitigation: the implementation gates full pruning on height-watermark
    movement or `pruneEveryAcceptedEvents`; remote rollout must still measure
    Java CPU and cats-effect starvation logs under timeout/catch-up traffic.

- **Risk: diagnostics expose too much payload detail.**
  - Mitigation: expose counts, watermarks, and bounded samples only; avoid
    status payload bodies.

- **Risk: heap remains high after rollout because old retained objects were
  created before the patch.**
  - Mitigation: restart validators after deploying the patch and evaluate
    steady-state growth from a clean process.

## Acceptance Criteria

1. A deterministic HotStuff sink test can process many views and timeout
   windows while retained sink counts remain within configured bounds.
2. Finalization, QC assembly, timeout certificate assembly, duplicate
   suppression, and relay policy tests pass with sink pruning enabled.
3. Node status exposes sink retained counts and prune counters.
4. Phase 0 evidence identifies whether sink structures are the dominant
   HotStuff artifact retainers, and source topic cardinality is recorded.
5. A remote four-validator cluster no longer shows all validators pinned near
   2 GiB old generation after ordinary transaction and token-definition tests.
6. Remote token-definition and persisted transaction checks complete on a
   patched, restarted cluster while sink counts remain bounded; any remaining
   stall must be attributable to a cause other than unbounded retained HotStuff
   sink artifacts.
7. Documentation states the default retention policy, tuning knobs, and restart
   guidance for clusters that already accumulated old in-memory state.

## Checklist

### Phase 0: Reproduction And Retention Contract

- [x] Record remote incident evidence and heap histogram summary in the
      retention runbook.
- [x] Add heap dominator or reference-path capture procedure for representative
      HotStuff artifact objects; concrete remote retainer samples remain a
      rollout measurement item.
- [x] Add source retained-event count path by `ChainTopic` and confirm current
      code keys source streams by `(chainId, topic)`.
- [x] Define `HotStuffArtifactSinkRetention` and conservative defaults.
- [x] Lock finalized-height and timeout-window pruning watermarks.
- [x] Inventory all sink structures and accumulator references that require
      pruning.

### Phase 1: Core Sink Pruning

- [x] Add `VoteAccumulator` pruning and retained-count APIs.
- [x] Add `TimeoutVoteAccumulator` pruning and retained-count APIs.
- [x] Preserve the finalization-before-pruning invariant.
- [x] Implement sink snapshot pruning for proposals, votes, QCs, timeout
      artifacts, new-view artifacts, accumulators, duplicates, and retained
      finalization safety faults.
- [x] Gate full pruning after accepted sink events while preserving immediate
      pruning when finalized/certified watermarks move.
- [x] Preserve relay, rejection, duplicate detection, QC assembly, and TC
      assembly semantics.

### Phase 2: Diagnostics And Configuration

- [x] Add sink retained-size and prune-counter diagnostics.
- [x] Expose sink retention diagnostics through runtime diagnostics handles;
      downstream status renderers can consume the same fields.
- [x] Wire sink retention through in-memory services and bootstrap assembly.
- [x] Add node configuration knobs and document defaults.

### Phase 3: Stress Verification

- [x] Add accumulator pruning unit tests.
- [x] Add sink pruning unit tests for each artifact family.
- [x] Add timeout-storm bounded-retention test.
- [x] Add finalization-after-pruning regression test.
- [x] Add post-finalization-stall regression test showing certified floor
  independently bounds height artifacts.
- [x] Run targeted HotStuff node-jvm suites.

### Phase 4: Remote Rollout And Measurement

- [x] Document build and deploy procedure for patched remote validators;
      remote execution remains environment-owned.
- [x] Document coordinated restart procedure to clear pre-patch retained heap.
- [x] Document `/status` or runtime-diagnostics sink count plateau checks.
- [x] Document token definition and persisted transaction recheck procedure.
- [x] Document post-rollout GC, heap histogram, and representative retainer
      sample collection.
- [ ] Execute remote build/deploy/restart, plateau checks, token/persisted
      transaction checks, and post-rollout heap/GC measurement on the
      environment-owned cluster.

## Follow-Ups

- Consider an ADR update if sink retention becomes a formal long-term
  embedder/runtime contract.
- Evaluate a durable or segmented production artifact sink if future
  deployments require longer historical query windows than an in-memory tail can
  safely provide.
- Add alerting thresholds for sink retained counts, full-GC frequency, old-gen
  occupancy, and cats-effect starvation warnings in downstream deployments.
