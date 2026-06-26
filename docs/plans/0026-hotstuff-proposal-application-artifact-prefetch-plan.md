# 0026 - HotStuff Proposal Application Artifact Prefetch Plan

## Status
Draft

## Created
2026-06-26

## Last Updated
2026-06-26

## Background

Remote BBGO dependent-payment measurements exposed a correctness and latency
gap in the current stage-based pipeline stack:

- a local four-validator smoke can pass the 500 ms low-latency diagnostic gate;
- a remote single-entrypoint `POST /tx-pipeline` finalized stage 0, but stage 1
  batch and pipeline metadata existed only on the submitting validator;
- the other validators returned `404` for the stage 1 batch and rejected the
  submitting validator's stage 1 proposals with
  `bbgoProposalValidationProposalTxUnavailable`;
- seeding the same pipeline metadata to every validator later made the cluster
  converge;
- seeding every validator from the start removed the stall, but the remote
  two-host deployment still measured around 2 seconds.

The first-order correctness bottleneck is not CPU starvation or audit-node
load. It is that a HotStuff proposal can reference application artifacts in its
`txSet` before the receiving peer has the corresponding application-topic
artifact or pipeline metadata. Waiting until proposal validation misses and then
running request-by-id backfill is only a safety net. For a 500 ms fast path, the
application artifacts that a proposal depends on must already be at the voting
validators, or must arrive in the same peer poll batch before the proposal is
validated.

Sigilaris already has the substrate needed to make this generic:

- peer event polling uses `POST /gossip/events/{sessionId}` and
  `application/octet-stream` binary frames;
- peer control uses `POST /gossip/control/{sessionId}` and JSON control
  batches;
- control operations include `setFilter`, `setKnown.tx`, `setKnown.exact`,
  `requestById.tx`, `requestById.exact`, `nack`, `setCursor`, and `config`;
- `HotStuffPeerArtifact[A]` multiplexes HotStuff consensus artifacts and
  application artifacts in one authenticated peer session;
- `GossipTopicContract` already exposes exact known-set scopes,
  `requestByIdLimit`, `deliveryPriority`, and `producerQoS`;
- `HotStuffProposalTxSync.controlBatchesForProposalOnExactTopics` can build
  bounded `RequestByIdExact` catch-up batches for proposal-visible artifact IDs.

The missing piece is a runtime-level dependency and scheduling model tying a
proposal's `txSet` to the application gossip artifacts needed to validate that
proposal.

## Goal

Add an application-neutral proposal dependency prefetch surface so embedders can
tell Sigilaris which application artifacts are required by a HotStuff proposal,
and so the peer gossip producer can deliver those artifacts before, or in the
same poll batch as, the proposal that references them.

The target behavior is:

1. a proposal dependency resolver maps `Proposal.txSet.txIds` to
   topic-scoped application artifact references;
2. the producer classifies each dependency per peer as `known`, `missing`, or
   `ambiguous` using exact known-set state and Bloom filters;
3. low-latency profiles send small proposal dependency sidecars even when Bloom
   state is ambiguous;
4. the event scheduler can co-schedule dependency artifacts ahead of the
   proposal without embedding application payload bytes inside the consensus
   proposal;
5. proposal-validation misses can trigger immediate bounded
   `RequestByIdExact` backfill without blocking vote validation on network I/O;
6. embedders such as BBGO can use the same API for normal admission artifacts
   and pipeline stage metadata artifacts.

## Scope

- Define a proposal-to-application-artifact dependency model.
- Add an optional embedder registration point for resolving proposal `txSet`
  IDs to application artifact references.
- Add a producer-side prefetch or sidecar scheduler for same-session
  `HotStuffPeerArtifact` delivery.
- Define `known`, `missing`, and `ambiguous` classification from exact known
  sets and Bloom filters.
- Define low-latency small-proposal sidecar policy.
- Add a generic immediate backfill hook for proposal-validation unavailable
  diagnostics.
- Add metrics and status diagnostics for sidecar decisions, skipped sidecars,
  ambiguous Bloom decisions, and request-by-id safety-net triggers.
- Document the downstream embedder-facing extension points and rollout
  requirements.

## Non-Goals

- Do not put application payload bytes into HotStuff proposal, vote, QC, or
  block-header consensus wire formats.
- Do not make Sigilaris interpret BBGO transaction, batch, pipeline, escrow, or
  UTXO semantics.
- Do not run synchronous network fetches inside vote validation.
- Do not make Bloom filters a correctness oracle. Bloom `mightContain=true`
  remains ambiguous unless confirmed by an exact known set.
- Do not require unbounded `SetKnownExact` advertisement.
- Do not guarantee the remote 500 ms target by this plan alone. Host CPU,
  pacemaker windows, finality-drive pacing, and deployment topology remain
  independent latency owners.

## Related ADRs And Docs

- [ADR-0016: Multiplexed Gossip Session Sync](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0030: HotStuff Transitive Artifact Relay](../adr/0030-hotstuff-transitive-artifact-relay.md)
- [ADR-0031: Certified Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](../adr/0032-stage-based-transaction-pipeline-api.md)
- [HotStuff Application Gossip Topic Handoff](../dev/hotstuff-application-gossip-topic-handoff.md)
- [0022 - HotStuff Transitive Artifact Relay Plan](0022-hotstuff-transitive-artifact-relay-plan.md)
- [0023 - Certified Ancestor Dependent Transaction Pipelining Plan](0023-certified-ancestor-dependent-transaction-pipelining-plan.md)
- [0025 - Stage-Based Transaction Pipeline API Plan](0025-stage-based-transaction-pipeline-api-plan.md)
- BBGO downstream plan 0021: Remote Tx Pipeline Stage Metadata Fanout Plan
  (downstream repository; intentionally not linked from Sigilaris to avoid a
  sibling checkout dependency).

## Current Implementation Gap

### Proposal Dependency Model

`Proposal.txSet` carries stable IDs, and application topics can serve artifacts
by stable ID, but the runtime does not know which application topic, exact
scope, or artifact body a proposal needs for voting. Today that mapping is
embedder-owned and happens either before local proposal input or after a
receiver detects missing data.

The result is a race:

1. local proposal input chooses a batch;
2. the proposal artifact is gossiped on `consensus.proposal`;
3. related application artifacts are gossiped independently on an application
   topic;
4. a remote validator can receive and validate the proposal before the
   application artifact is visible locally.

### Topic Ordering

The current polling path sorts subscribed topics by `deliveryPriority` and the
effective runtime ordering is higher numeric priority first. Consensus proposal
default priority is currently `2`, while an application topic that does not
override priority inherits `0`. Raising application priority helps, but it does
not express that a specific application artifact must precede a specific
proposal. A generic prefetch scheduler is still needed.

There is a known implementer trap in the existing code comments: a HotStuff
topic policy comment may describe `deliveryPriority` as lower-is-higher. The
runtime polling sort is the source of truth and currently uses higher numeric
priority first. This plan must document the effective behavior and add a
regression test for the sorting direction, not only for the default proposal
priority value.

### Request-By-ID Timing

`HotStuffProposalTxSync.controlBatchesForProposalOnExactTopics` emits bounded
`RequestByIdExact` batches, but it is a catch-up helper. It does not cause the
producer to send proposal dependencies before the proposal is first delivered.

### Bloom Ambiguity

`TxBloomFilterWire` currently contains `bitsetBase64Url`, `numHashes`, and
`hashFamilyId`. It does not carry inserted count `n`, target false-positive
probability, or age. The producer can prove `missing` only when
`mightContain=false`. When `mightContain=true` and there is no exact known-set
entry, the state is ambiguous.

### Validation Miss Hook

Sigilaris can expose generic request-by-id control paths, but proposal
validation unavailable diagnostics are application-owned. There is no generic
runtime hook that turns a validation miss into immediate application-artifact
backfill against the peer that supplied the proposal.

## Proposed Runtime And API Design

### Dependency Reference

Add an application-neutral dependency reference that can be produced by an
embedder resolver:

```scala
final case class HotStuffProposalApplicationDependency(
    proposalTxId: StableArtifactId,
    topic: GossipTopic,
    artifactId: StableArtifactId,
    exactScope: ExactKnownSetScope,
    estimatedBytes: Option[Long],
    relation: HotStuffProposalDependencyRelation,
    criticality: HotStuffProposalDependencyCriticality,
    recoveryMode: HotStuffProposalDependencyRecoveryMode,
)
```

`proposalTxId` is the ID visible in `Proposal.txSet`. `artifactId` is the
stable ID served by the application topic. They may be the same ID, but the API
must not require that. `exactScope` is required so the scheduler can use the
existing exact scoped `RequestByIdExact` and peer known-set state.

`relation` should start with:

- `DirectProposalTx`: the artifact directly materializes a `txSet` ID;
- `BarrierAncestor`: the artifact materializes a certified-ancestor or pipeline
  stage dependency required to validate a later proposal;
- `MaterializationOnly`: the artifact helps later apply or audit but is not
  needed for the vote.

`criticality` should start with:

- `RequiredForVote`: the receiver cannot safely vote without this artifact;
- `HelpfulForMaterialization`: useful for later apply or audit, but not needed
  before vote validation.

`recoveryMode` should start with:

- `RequestByIdBackfillable`: this dependency is safe for generic single-hop
  receiver backfill using its own `artifactId` and `exactScope`;
- `ProactiveFanoutOnly`: this dependency can be sidecar-scheduled by the
  producer, but a cold receiver cannot derive or request it through the generic
  backfill hook until some embedder-owned index or local state makes a new
  `RequestByIdBackfillable` dependency available.

The prefetch scheduler must prioritize `RequiredForVote`.

The Sigilaris runtime does not recursively interpret application dependency
graphs. If a workload has transitive requirements, such as a T2 stage requiring
T1 stage metadata or T1 admission artifacts, the embedder resolver must flatten
those requirements into the returned dependency vector and mark them with the
appropriate `relation`.

### Dependency Resolver

Add an optional resolver to the HotStuff runtime bootstrap with application
topics:

```scala
trait HotStuffProposalApplicationDependencyResolver[F[_]]:
  def dependenciesForProposal(
      proposal: Proposal,
  ): F[Either[CanonicalRejection.ArtifactContractRejected, Vector[
    HotStuffProposalApplicationDependency,
  ]]]
```

Default behavior is `Vector.empty`, preserving current semantics for embedders
that do not opt in.

The resolver must be usable on both producer and receiver paths:

- the producer uses it before sending a local proposal so it can schedule
  sidecars;
- the receiver uses it after receiving a remote proposal so validation-miss
  backfill can derive the dependency refs currently derivable from the proposal
  and local application state without adding dependency data to the proposal
  wire.

The resolver is effectful and may consult local application state. It is allowed
to return different but convergent dependency sets on producer and receiver
paths. For example, a warm producer may return both a stage-index dependency and
the final stage artifact dependency, while a cold receiver can initially return
only the proposal-tx-hash-addressed stage-index dependency. After that index is
fetched and applied, a later resolver call can return the stage artifact
dependency.

If an embedder cannot resolve dependencies from the proposal alone, it must
provide a validation-provider-specific missing-artifact diagnostic that carries
the required refs to the backfill hook. The generic plan must not rely on
unstated proposal-wire side metadata.

For safety-net backfill to work on a cold receiver, the resolver must return
only currently requestable dependencies as `RequestByIdBackfillable`. A
`RequiredForVote` dependency whose `artifactId != proposalTxId` is valid only if
the resolver can currently derive its `artifactId` and `exactScope` from
proposal-visible data or local state. If the dependency is needed for the fast
path but is not currently receiver-derivable, the resolver must mark it
`ProactiveFanoutOnly`. The generic receiver backfill hook must not attempt
`RequestByIdExact` for `ProactiveFanoutOnly` dependencies.

Operational consequence: `ProactiveFanoutOnly` `RequiredForVote` dependencies
have no generic receiver safety net. Missing the proactive sidecar can cost the
current vote attempt until an embedder-owned index, later local state, or a view
change gives the receiver a `RequestByIdBackfillable` dependency. A downstream
such as BBGO can avoid this category for cold recovery by publishing a
proposal-tx-hash-addressed stage-index artifact first.

Sigilaris owns the single-hop mechanics for the dependency refs returned by the
resolver: group by exact scope, send bounded `RequestByIdExact`, and apply
normal retry budgets. Multi-hop interpretation of an application index artifact,
such as "read this index artifact, parse the stage artifact id, then request the
stage artifact", remains embedder-owned. Convergence for multi-hop recovery is
successive validation-miss or wake cycles: resolve currently derivable refs,
backfill and apply them, wake, rerun the resolver, then request the newly
derivable refs.

A downstream such as BBGO can implement the resolver by mapping proposal tx IDs
to workload-specific admission artifacts, for example `bbgo.admission`
transaction artifacts, and pipeline stage metadata artifacts.

### Per-Peer Known-State Classification

For each dependency and peer producer session:

- `known`: the peer's exact known set contains `artifactId` in `exactScope`;
- `missing`: the peer has no exact known entry and Bloom `mightContain=false`;
- `ambiguous`: the peer has no exact known entry and Bloom `mightContain=true`,
  or the Bloom filter is stale, saturated, unavailable for the scope, or cannot
  be safely evaluated.

`stale` is computable before any Bloom wire metadata change. The producer must
record the local receipt time when it applies a peer's `setFilter` control op.
Phase 1 stale classification uses that local receipt time and a configured
`maxFilterAge`. Optional sender-origin metadata such as `createdAtMillis` can
be added later to distinguish network age from local session age, but Phase 1
must not depend on it.

Only `known` is safe to omit on the correctness-sensitive fast path. `missing`
must be sent if policy limits allow. `ambiguous` is policy-dependent, but
low-latency small proposals should send it.

### Sidecar Policy

Add a policy object:

```scala
final case class HotStuffProposalSidecarPolicy(
    enabled: Boolean,
    lowLatencySmallProposalAlways: Boolean,
    smallProposalTxLimit: Int,
    maxSidecarArtifactsPerProposal: Int,
    maxSidecarBytesPerPeerPoll: Long,
    maxAmbiguousFalsePositiveRisk: BigDecimal,
    holdProposalWhenRequiredSidecarSkipped: Boolean,
    maxRequiredSidecarHold: FiniteDuration,
    maxRequiredSidecarHoldAttempts: Int,
)
```

Initial recommended defaults for the low-latency profile:

- `enabled = true`;
- `lowLatencySmallProposalAlways = true`;
- `smallProposalTxLimit = 16`;
- `maxSidecarArtifactsPerProposal = 128`;
- `maxSidecarBytesPerPeerPoll = 1048576`;
- `maxAmbiguousFalsePositiveRisk = 0.001`;
- `holdProposalWhenRequiredSidecarSkipped = true` for
  `RequiredForVote` dependencies unless the skip reason is a configured large
  proposal cap;
- `maxRequiredSidecarHold = 50 ms`;
- `maxRequiredSidecarHoldAttempts = 1`.

For small proposals below the artifact and byte caps, send all dependencies
that are not exact-known, including ambiguous dependencies. This is intentionally
bandwidth-conservative because a single omitted artifact can cost a vote and a
view.

Holding a proposal is strictly bounded. After the hold duration or attempt count
is exhausted, the producer must either send the proposal with request-by-id
safety-net diagnostics enabled or let the pacemaker/view-change path advance.
The plan must never allow a leader to withhold a proposal from a peer
indefinitely because a sidecar is unavailable.

`smallProposalTxLimit` gates whether the "send ambiguous" fast path applies.
`maxSidecarArtifactsPerProposal` and `maxSidecarBytesPerPeerPoll` still apply to
the flattened dependency vector. If a 16-tx proposal expands to more than 128
artifacts, the artifact cap wins and the bounded hold/fallback path is used.

`estimatedBytes = None` means the pre-read planner cannot prove the byte budget.
The scheduler may read the artifact only if the artifact-count cap allows it,
then it must enforce `maxSidecarBytesPerPeerPoll` against the actual encoded
payload size before emitting the proposal. This is the encoded
`HotStuffPeerArtifact` payload size, not the full event-frame size. An
actual-size overflow takes the same bounded hold/fallback path as any other
required sidecar skip.
The HotStuff composite peer source populates `encodedSizeBytes` by encoding the
wrapped `HotStuffPeerArtifact`; lower-level application sources may still leave
their raw event size unknown, and the sidecar planner treats missing actual size
plus missing estimate as over budget.

### Producer Scheduling

The scheduler should not modify proposal bytes. It should produce an ordered
delivery plan for the existing peer event poll response:

1. resolve proposal dependencies when a local proposal is queued for delivery;
2. classify dependencies against the target peer session;
3. read sidecar artifacts from their registered application topic sources by
   `artifactId`;
4. prepend selected application artifacts to the poll batch;
5. emit the proposal only after all required selected sidecars fit in the same
   poll response, or after policy chooses an explicit skip path;
6. leave ordinary topic polling and cursor replay behavior intact for unrelated
   traffic.

This requires proposal-topic gating, not only topic priority. For a peer with a
pending required sidecar, the producer must not let the ordinary
`consensus.proposal` polling path advance that peer's proposal cursor past the
gated proposal. Later proposals on the same topic must not pass it for that
peer. The gated proposal is emitted only by the sidecar delivery planner, in the
same response after the selected application artifacts, or by the explicit
fallback path after the bounded hold expires.

Application sidecars should be injected as explicit application-topic events
selected by stable ID. Proposal-topic cursor advancement happens only when the
proposal event is actually emitted. Application-topic cursor behavior must use
the existing explicit-artifact/request-by-id semantics and must not require
rewriting unrelated live cursor replay.

Producer-side resolver failure is not the same as sidecar artifact
unavailability. If the local leader has already produced a valid proposal but
the optional dependency resolver fails, the runtime must not block the leader
indefinitely. The producer should emit a structured
`proposalDependencyResolverFailed` diagnostic and send the proposal through the
plain non-sidecar path, relying on receiver validation and request-by-id
safety-net behavior. Resolver failure may block only if the embedder marks the
proposal input itself invalid before the proposal is emitted.

Resolver outcome handling must be locked separately for each path:

- `Right(dependencies)`: use the dependencies normally.
- `Left(ArtifactContractRejected)` on the producer path: treat as dependency
  expression failure, emit diagnostics, and fall back to plain proposal
  delivery unless proposal input had already failed before proposal emission.
- failed effect on the producer path: same fallback as `Left`, with the
  throwable rendered in diagnostics.
- `Left` or failed effect on the receiver path: do not reject the consensus
  proposal solely because the optional resolver failed. Emit diagnostics and use
  any embedder-supplied validation-miss refs; otherwise no generic dependency
  backfill is available for that miss.
- deterministic resolver contract errors discovered at bootstrap or
  registration time may fail startup, because no proposal has been emitted yet.

If a required sidecar cannot be read locally, do not emit the proposal through
the low-latency sidecar path. Record `sidecarLocalArtifactUnavailable` and let
the bounded hold retry once. If the artifact is still unavailable after the
configured hold budget, send the proposal only through the explicit fallback
mode with backfill diagnostics or allow the pacemaker to move to the next view.

If required sidecars exceed caps, emit a structured diagnostic and either hold
the proposal or send it with request-by-id safety-net enabled, depending on
policy. The default for low-latency small proposals is to hold only within the
bounded sidecar budget, then fall back. Holding is a latency optimization, not a
liveness condition.

### Immediate Backfill Hook

Add a generic hook that embedders can call or that the runtime can invoke from
structured unavailable diagnostics:

```scala
trait HotStuffProposalDependencyBackfill[F[_]]:
  def requestMissingDependencies(
      sourcePeer: PeerIdentity,
      proposal: Proposal,
      dependencies: Vector[HotStuffProposalApplicationDependency],
      reason: String,
  ): F[HotStuffProposalDependencyBackfillResult]

  def resolveAndRequestMissingDependencies(
      sourcePeer: PeerIdentity,
      proposal: Proposal,
      reason: String,
  ): F[HotStuffProposalDependencyBackfillResult]
```

The implementation should:

- run the same dependency resolver on the receiver when dependency refs were not
  supplied by an embedder-specific validation diagnostic;
- group only `RequestByIdBackfillable` dependencies by `ExactKnownSetScope`;
- emit diagnostics for `ProactiveFanoutOnly` dependencies and do not request
  them through generic receiver backfill; required proactive-only dependencies
  are also surfaced in `requiredProactiveFanoutOnly` for structured vote
  deferral decisions;
- use bounded `RequestByIdExact`;
- apply existing request retry budgets;
- never block the current vote validation attempt;
- return enough structured diagnostics for embedders to wake application work
  or pacemaker retry after fetched artifacts are applied.

The generic hook is single-hop for the refs it receives. If a returned artifact
is an embedder-defined index that reveals additional dependency refs, the
embedder sink or validation provider must schedule the next resolver/backfill
pass after applying that index.

For compatibility, the first version is an optional runtime service that
prepares bounded control batches and diagnostics for embedders to deliver when
their validation provider returns `Unavailable`. A later API can enrich
`HotStuffProposalValidationProviderResult`
with dependency refs if that source-level change is accepted.
The generic service does not observe artifact sink application by itself; an
embedder integration must wire apply-after-backfill wake/retry using its
runtime hooks.

## Bloom And Ambiguous Policy

Bloom filters are peer-advertised dedup hints consumed by the producer, not a
correctness proof.

Classification rules:

1. exact known-set hit means `known`;
2. Bloom `mightContain=false` means `missing`, assuming a valid Bloom filter
   with no false negatives;
3. Bloom `mightContain=true` without an exact known-set hit means `ambiguous`.

False-positive probability is:

```text
p ~= (1 - exp(-k * n / m))^k
```

where `m` is bit count, `k` is hash count, and `n` is inserted IDs. Current
`TxBloomFilterWire` lacks `n`, so the producer cannot calculate this exactly.
Until the wire includes metadata, estimate from bit density:

```text
q = setBits / m
p ~= q^k
```

For `ambiguousCount`, the probability that at least one omitted ambiguous
artifact is actually missing is:

```text
1 - (1 - p)^ambiguousCount
```

Policy:

- low-latency small proposals send ambiguous dependencies;
- saturated filters send ambiguous dependencies;
- stale filters send ambiguous dependencies;
- large proposals may omit ambiguous dependencies only when the estimated
  aggregate false-positive risk is below the configured threshold and
  request-by-id fallback is enabled;
- proposal validation miss must still trigger immediate backfill.

## Protocol Compatibility

- No HotStuff proposal, vote, QC, block, or block-header wire change is required
  for sidecar scheduling.
- Existing application-topic wire encoding remains unchanged.
- `RequestByIdExact` and `SetKnownExact` control operations remain unchanged.
- Adding optional JSON fields such as `insertedCount`, `targetFpp`, or
  `createdAtMillis` to `TxBloomFilterWire` can be backward compatible if new
  readers default missing fields and old readers ignore unknown fields.
- The dependency resolver and sidecar scheduler must be opt-in. Default runtime
  behavior stays byte-for-byte compatible for embedders that do not register
  application dependencies.
- This plan does not add a remote peer-ack or quorum-ack API for application
  topic delivery. It improves ordering of artifacts that are already deliverable
  through the peer event stream. Embedders must not treat sidecar scheduling as
  proof that a peer durably applied an artifact unless a later acknowledgement
  contract is added.
- Mixed-version clusters are safe for correctness if the application topic
  contract is unchanged. Old producers simply do not sidecar; old consumers can
  already receive application-topic artifacts. Performance benefits require the
  producer side to be upgraded.
- If the validation result ADT is extended, keep an adapter or default case so
  existing providers compile with a deprecation window, or introduce the first
  implementation as a separate hook to avoid a source break.

## Security And DoS Considerations

- Bound sidecar artifacts per proposal and bytes per poll response.
- Never let a peer request artifacts outside its subscription or outside a
  topic's exact known-set contract.
- Retain existing `requestByIdLimit` and retry-budget enforcement.
- Validate every application artifact through its `GossipTopicContract` before
  delivery and after receipt.
- Do not let a large proposal monopolize the peer event stream. Large proposals
  must fall back to chunked request-by-id or a capped prefetch plan.
- Keep vote validation non-blocking with respect to network I/O.
- Rate-limit validation-miss-triggered backfill by `(peer, proposalId, scope)`.
- Record skipped sidecars and repeated misses as operator-visible diagnostics.
- Treat dependency resolver failure as proposal-delivery unavailable, not as
  permission to send an unvotable sidecar bundle on the low-latency path. On
  the producer path, resolver failure falls back to plain proposal delivery with
  diagnostics instead of self-stalling the leader.

## Downstream Extension Points (BBGO Example)

BBGO is a downstream consumer example for this plan, not a Sigilaris build-time
or test-time dependency. The Sigilaris repository must not import BBGO code,
fixtures, or artifact schemas to validate this feature. Sigilaris owns the
application-neutral dependency, sidecar, backfill, ordering, cap, and failure
contracts; BBGO owns its workload-specific artifact payloads and smoke tests.

For example, BBGO should consume the following Sigilaris surfaces:

- `HotStuffProposalApplicationDependencyResolver` to map proposal tx IDs to
  downstream admission and pipeline metadata topic artifacts, such as
  `bbgo.admission`;
- `HotStuffProposalSidecarPolicy` low-latency profile defaults;
- producer diagnostics for sidecar sent, sidecar skipped, and ambiguous Bloom
  decisions;
- `HotStuffProposalDependencyBackfill` to request downstream admission and
  pipeline metadata artifacts immediately when proposal validation sees
  unavailable tx/batch/stage metadata;
- optional Bloom metadata fields if BBGO wants to tune ambiguous policy for
  larger proposal workloads.

BBGO remains responsible for defining the artifact payloads, stable IDs, exact
scopes, retention, and sink behavior for its application topics.

## Pre-Implementation Measurement Gate

Before building the full sidecar/dependency-resolver machinery, run or reuse a
remote seed-all dependent-payment pipeline measurement and inspect the
`dependent-payment-latency-breakdown`. This gate answers whether application
artifact ordering or fanout spread is on the critical path after metadata is
already present on all validators.

If seed-all latency is still dominated by proposal/finality pacing, finality
drive, host CPU scheduling, or network topology, this plan remains valuable for
single-entrypoint correctness and reliability, but it must not be treated as the
owner of the 500 ms latency target. In that case, the 500 ms work should be
split to a Sigilaris pacemaker/finality-drive or deployment sizing plan before
large sidecar implementation work is used to justify latency ROI.

If seed-all breakdown shows application artifact delivery ordering or fanout
spread on the critical path, proceed with the sidecar scheduler as both a
correctness and latency improvement.

## Phase 0 Lock Record

Locked on 2026-06-26 for the first implementation phase:

- Measurement gate reuses the BBGO remote seed-all run
  `target/remote-cluster-pipeline/remote-pipeline-redeploy-seed-all-20260625T143341Z`.
  It finalized correctly with `t1SubmittedToT2FinalizedMillis = 2082 ms`.
  The same remote deployment's single-entrypoint run stalled for 180 seconds
  with T2 metadata only on the submitting validator. Therefore sidecar work has
  correctness and reliability ROI for single-entrypoint operation, but it does
  not currently own the full remote 500 ms target. Remaining seed-all latency
  is classified under proposal/finality pacing, host scheduling, and deployment
  topology until a newer breakdown proves artifact ordering is dominant.
- The dependency resolver surface is JVM HotStuff runtime API first. Shared
  `node-common` changes are limited to protocol-neutral helpers such as topic
  delivery ordering and later known-state classification.
- The dependency reference model is
  `proposalTxId`, `topic`, `artifactId`, `exactScope`, `estimatedBytes`,
  `relation`, `criticality`, and `recoveryMode`.
- Initial `criticality` values are `RequiredForVote` and
  `HelpfulForMaterialization`.
- Initial `recoveryMode` values are `RequestByIdBackfillable` and
  `ProactiveFanoutOnly`.
- Generic receiver backfill requests only `RequestByIdBackfillable`
  dependencies. `ProactiveFanoutOnly` dependencies are diagnostics on the
  receiver path and remain eligible for producer sidecar scheduling.
- Resolver outcomes are locked as: `Right` uses dependencies; producer-side
  `Left` or failed effects emit diagnostics and fall back to plain proposal
  delivery; receiver-side `Left` or failed effects emit diagnostics and do not
  reject the proposal solely because the optional resolver failed.
- Low-latency defaults are locked to small-proposal sidecar always for up to 16
  proposal tx IDs, 128 sidecar artifacts, 1 MiB per peer poll response, one
  bounded hold attempt, and a 50 ms maximum required-sidecar hold.
- Bloom wire metadata is deferred from Phase 1. Phase 1 records local
  `setFilter` receipt time for stale classification and uses bit-density
  estimation when a risk estimate is needed.
- Validation miss backfill starts as a separate optional hook/service rather
  than a source-breaking validation-result ADT extension.
- Effective topic ordering is higher numeric `deliveryPriority` first, with
  unset/unknown topics treated as `0`. This is now guarded by a shared
  producer-session ordering helper and unit test.
- The existing HotStuff default topic priorities are interpreted according to
  that observed runtime contract: proposal `2`, vote `1`, timeout-vote `3`, and
  new-view `4`. Phase 0 does not change those values; later application-topic
  tests must compare against effective contract values instead of relying on a
  stale lower-is-higher comment. A repository search found no remaining
  lower-is-higher production comment after updating `HotStuffTopicPolicy`.

## Change Areas

### Runtime And API

- Add dependency resolver types in the HotStuff JVM/runtime API boundary.
- Add sidecar policy to HotStuff low-latency profile configuration.
- Add a delivery planner in the peer gossip producer path before ordinary topic
  polling emits a proposal.
- Add proposal-topic gating so ordinary polling cannot advance a peer's
  proposal cursor past a proposal whose required sidecars are still pending.
- Add optional validation miss backfill service.
- Add status and metrics for sidecar decisions.

### Gossip Protocol

- Keep control operations unchanged.
- Optionally extend Bloom filter JSON metadata.
- Confirm and document effective `deliveryPriority` semantics as higher value
  first, including a regression guard against stale lower-is-higher comments.

### Tests

- Add unit tests for dependency classification.
- Add scheduler tests proving required application artifacts appear before the
  proposal in a single poll batch.
- Add a topic-priority ordering test proving higher numeric
  `deliveryPriority` sorts before lower values and unset topics default to `0`.
- Add ambiguous Bloom false-positive policy tests.
- Add cap and DoS tests.
- Add mixed-version compatibility tests with the resolver disabled.
- Add loopback tests with an application topic and HotStuff proposal topic.

### Docs

- Update application gossip topic handoff docs with sidecar registration and
  ordering behavior.
- Update low-latency profile docs with sidecar policy defaults.
- Add release notes for the new extension points.

## Implementation Phases

### Phase 0: Measurement And Contract Lock

- Run the remote seed-all measurement gate and record whether artifact ordering
  is a measured critical-path contributor or whether latency ownership should
  move to pacemaker/finality/deployment work.
- Decide whether the dependency resolver is JVM-only or shared in
  `node-common`.
- Lock dependency reference fields, criticality values, and `recoveryMode`
  semantics.
- Lock that receiver generic backfill requests only
  `RequestByIdBackfillable` dependencies and treats `ProactiveFanoutOnly` as a
  diagnostic/proactive sidecar-only condition.
- Lock resolver `Right`, `Left`, and failed-effect behavior on producer and
  receiver paths.
- Lock low-latency sidecar caps.
- Lock bounded hold duration, hold attempts, and fallback behavior.
- Decide whether Bloom metadata is part of this release or a follow-up.
- Decide whether validation miss backfill is a separate hook or an ADT
  extension.

### Phase 1: Resolver And Classification

- Add the resolver API with a default empty resolver.
- Ensure the resolver can run on producer and receiver paths.
- Validate that required receiver-side dependencies are marked with an explicit
  recovery mode and that generic backfill ignores `ProactiveFanoutOnly`.
- Add peer known-state classification using exact known sets and Bloom filters.
- Add unit tests for known, missing, ambiguous, local-receipt-time stale, and
  saturated states.

### Phase 2: Producer Sidecar Scheduling

- Add an ordered delivery planner for proposal dependencies.
- Add proposal-topic cursor gating for pending required sidecars.
- Ensure selected application artifacts are emitted before the proposal in the
  same event poll response.
- Add cap handling and diagnostics.
- Add loopback coverage with `HotStuffPeerArtifact.Application` and
  `HotStuffPeerArtifact.Consensus` in one batch.

### Phase 3: Immediate Backfill Safety Net

- Add the optional backfill service.
- Group requests by exact scope and emit bounded `RequestByIdExact`.
- Add retry-budget and duplicate suppression tests.
- Document that apply-after-backfill wake/retry remains an embedder integration
  contract until a runtime-owned sink callback is added.

### Phase 4: Bloom Metadata And Documentation

- Add optional Bloom metadata if Phase 0 accepts it.
- Document bit-density fallback when metadata is absent.
- Update handoff, low-latency, and release docs.

### Phase 5: Downstream Adoption Evidence

- Optionally publish a local or snapshot Sigilaris artifact for downstream
  integration testing.
- Verify in the downstream repository that admission and pipeline metadata
  dependencies can be registered against that non-final artifact.
- Keep this evidence outside the Sigilaris build and test graph. A stable
  Sigilaris release is gated by the generic contract tests, release build
  checks, and documentation accuracy, not by BBGO-specific fixtures.
- If Sigilaris release notes make a BBGO-specific correctness claim, record the
  matching downstream local four-node and remote two-host single-entrypoint
  results as release evidence. Otherwise, record downstream results as
  follow-up adoption notes.
- Rerun the same downstream gates after downstream pins the stable release
  coordinate.

## Test Plan

- `HotStuffProposalDependencyClassificationSuite`: exact known-set hit,
  Bloom false missing, Bloom true ambiguous, no Bloom ambiguous, saturated
  Bloom ambiguous, and local-receipt-time stale Bloom ambiguous.
- `HotStuffProposalDependencyResolverContractSuite`: a `RequiredForVote`
  dependency with `artifactId != proposalTxId` and no currently derivable
  `artifactId`/`exactScope` is represented as `ProactiveFanoutOnly`, and generic
  receiver backfill excludes it while emitting diagnostics.
- `HotStuffProposalDependencyResolverContractSuite`: a later resolver pass can
  turn the same application requirement into `RequestByIdBackfillable` after an
  embedder-owned index artifact has been applied locally.
- `HotStuffProposalSidecarPolicySuite`: small proposal always sends ambiguous,
  large proposal risk threshold, artifact count cap, byte cap, hold-vs-send
  policy.
- `HotStuffPeerArtifactSidecarSchedulerSuite`: sidecar application event appears
  before the proposal event in the same poll batch.
- `HotStuffPeerArtifactSidecarSchedulerSuite`: a `RequiredForVote` dependency
  with `recoveryMode = ProactiveFanoutOnly` is still emitted as a producer
  sidecar before the proposal; `recoveryMode` filtering applies to receiver
  backfill, not producer sidecar scheduling.
- `HotStuffPeerArtifactSidecarSchedulerSuite`: after
  `maxRequiredSidecarHold` or `maxRequiredSidecarHoldAttempts`, the gated
  proposal either emits through the explicit fallback path with diagnostics or
  yields to the pacemaker/view-change path; it must not remain gated.
- `HotStuffPeerArtifactSidecarSchedulerSuite`: producer-side resolver failure
  emits a diagnostic and falls back to plain proposal delivery instead of
  blocking the leader.
- `TxGossipArmeriaAdapterSuite`: binary event stream still decodes mixed
  application and consensus frames.
- `HotStuffGossipLoopbackSuite`: receiver can validate a proposal whose
  required application artifact was unknown before the poll.
- Compatibility test: resolver disabled keeps the existing event order and
  control behavior.
- DoS test: oversized dependency set does not exceed caps and emits structured
  diagnostics.
- Downstream integration, outside the Sigilaris repository: a workload such as
  BBGO can verify that single-entrypoint pipeline submit no longer leaves stage
  1 metadata only on the submitting validator. This is adoption evidence, not a
  Sigilaris unit or integration test dependency.

## Rollout Plan

1. Ship resolver and sidecar scheduler disabled by default.
2. Do not enable in a low-latency profile until the seed-all measurement gate
   has classified the expected latency value. Correctness rollout may continue
   independently, but the 500 ms latency claim requires measured artifact
   ordering or fanout-spread contribution.
3. Enable in Sigilaris low-latency profile for small proposals only.
4. Optionally publish a snapshot or local artifact for downstream integration
   testing. This is the cycle breaker between "a downstream must test the API"
   and "a downstream final merge must pin a stable Sigilaris coordinate".
5. Run downstream local four-node single-entrypoint and seed-all comparison
   against the snapshot/local artifact when a downstream claim is needed.
6. Run downstream remote two-host single-entrypoint and seed-all comparison
   against the snapshot/local artifact when a remote downstream claim is
   needed.
7. Promote the Sigilaris release based on the generic contract tests, release
   build checks, and accurate public docs. Do not make BBGO-specific fixtures a
   Sigilaris release dependency.
8. Update downstreams to the stable Sigilaris release coordinate and rerun
   their own gates before their final merge.
9. Keep request-by-id validation miss backfill enabled after rollout as a
   safety net.

## Risks And Mitigations

- Risk: sidecar delivery increases bandwidth.
  Mitigation: exact known-set omission, small-proposal caps, byte caps, and
  large-proposal fallback.
- Risk: ambiguous Bloom omissions still cause vote misses.
  Mitigation: send ambiguous dependencies in low-latency small proposals and
  keep immediate backfill.
- Risk: scheduler couples consensus and application topic internals too tightly.
  Mitigation: use application-neutral dependency refs and existing
  `GossipArtifactSource.readByIds`.
- Risk: proposal is held because the local application source cannot serve an
  artifact.
  Mitigation: hold only within the configured duration/attempt budget, then
  fall back to send-with-safety-net or let the pacemaker view change proceed.
- Risk: proposal-topic gating blocks later proposals for the same peer.
  Mitigation: keep holds short, emit diagnostics, and require fallback so cursor
  monotonicity does not become an unbounded liveness hazard.
- Risk: mixed clusters show inconsistent latency.
  Mitigation: correctness remains unchanged; require homogeneous upgraded
  producers for latency measurements.

## Acceptance Criteria

1. An embedder can register a proposal dependency resolver without changing
   HotStuff proposal wire bytes.
2. For a small proposal with missing or ambiguous required dependencies, the
   peer event response sends the application artifacts before the proposal.
3. Bloom ambiguous policy is documented and tested.
4. Proposal-topic cursor gating is bounded and has a tested fallback path.
5. Proposal-validation unavailable diagnostics can trigger bounded
   `RequestByIdExact` backfill without blocking validation.
6. The resolver runs on receiver-side backfill or the validation diagnostic
   supplies equivalent dependency refs.
7. Required dependencies carry an explicit recovery mode, and generic receiver
   backfill requests only `RequestByIdBackfillable` dependencies.
8. Producer-side resolver failure falls back to plain proposal delivery with a
   diagnostic.
9. Sidecar caps and DoS protections are covered by tests.
10. Downstream embedders have documented extension points for admission and
   pipeline metadata artifacts without introducing a Sigilaris-to-downstream
   dependency.
11. A seed-all remote latency breakdown has been recorded before low-latency
   rollout, and the plan states whether sidecar work owns latency ROI or only
   correctness/reliability.

## Checklist

### Phase 0: Measurement And Contract Lock
- [x] Run or reuse remote seed-all leg breakdown before sidecar implementation.
- [x] Record whether artifact ordering/fanout spread is on the critical path.
- [x] Lock dependency reference model.
- [x] Lock dependency recovery mode semantics.
- [x] Lock generic backfill filtering for `ProactiveFanoutOnly`.
- [x] Lock resolver outcome handling for `Right`, `Left`, and failed effects.
- [x] Lock low-latency sidecar defaults.
- [x] Lock bounded hold and fallback behavior.
- [x] Decide Bloom metadata scope.
- [x] Decide validation miss hook shape.
- [x] Add Phase 0 priority-ordering scaffold and regression test.

### Phase 1: Resolver And Classification
- [x] Add empty default resolver.
- [x] Support producer and receiver resolver use.
- [x] Validate explicit recovery-mode handling for required dependencies.
- [x] Add known/missing/ambiguous classifier.
- [x] Add classifier tests.

### Phase 2: Producer Sidecar Scheduling
- [x] Add delivery planner.
- [x] Add proposal-topic cursor gating.
- [x] Add same-poll ordering tests.
- [x] Add hold-expiry and resolver-failure fallback tests.
- [x] Add cap diagnostics.

### Phase 3: Immediate Backfill Safety Net
- [x] Add backfill hook.
- [x] Resolve receiver-side dependencies or consume validation-supplied refs.
- [x] Add scoped `RequestByIdExact` grouping.
- [x] Enforce per-topic `requestByIdLimit` when chunking generic backfill
  requests.
- [x] Add retry and duplicate suppression tests.
- [ ] Wire apply-after-backfill wake/retry into runtime or embedder integration.

### Phase 4: Bloom Metadata And Documentation
- [x] Add optional Bloom metadata or document deferral.
- [x] Update handoff and low-latency docs.
- [x] Update draft release docs.

### Phase 5: Downstream Adoption Evidence
- [ ] Optionally publish local Sigilaris artifact for downstream testing.
- [ ] Optionally verify BBGO local four-node single-entrypoint correctness in
  the BBGO repository.
- [ ] Optionally verify BBGO remote two-host single-entrypoint correctness in
  the BBGO repository.
- [ ] Rerun downstream gates against the stable coordinate when a downstream
  release or merge needs that evidence.

## Follow-Ups

- Promote the dependency/sidecar policy to an ADR if the API becomes a stable
  embedder contract or if Bloom wire metadata changes ship in a public release.
- Add richer per-peer status for proposal sidecar decisions.
- Evaluate whether finality-drive and pacemaker low-latency profiles need
  separate remote two-host tuning after BBGO metadata fanout is fixed.
