# 0028 - HotStuff Gossip Diagnostics Projection Snapshot Plan

## Status
Complete - Maven Central release held before deploy

## Created
2026-06-27

## Last Updated
2026-06-28

## Progress

- 2026-06-28: Phase 0 contract locked against the current
  `InMemoryHotStuffSourceDiagnostics`, `InMemoryHotStuffSinkDiagnostics`,
  `HotStuffSinkRetainedCounts`, `HotStuffSinkPrunedCounts`,
  `HotStuffSinkRetentionWatermarks`, `HotStuffArtifactSinkRetention`, and
  `HotStuffWindow` definitions.
- 2026-06-28: Phase 0 Claude review findings resolved by locking strict
  projected decimal encoding as well as decoding, `ChainTopic` decomposition,
  and retention-window watermark chain-id provenance.
- 2026-06-28: Phase 0 Claude re-review findings resolved by separating
  fixed-scalar invariant failures from droppable map/list entries and by
  matching `ChainTopic` and window-watermark ordering to their current final
  case-class/map shapes.
- 2026-06-28: Phase 0 Claude re-review findings resolved by citing the source
  retention constructor invariant and locking warning invariants for failed or
  suppressed child snapshots.
- 2026-06-28: Phase 1 added application-neutral snapshot models and
  projection-local JSON codecs with strict `BigNat` decimal-string
  encoding/decoding, including a 4096-digit defensive decode cap.
- 2026-06-28: Phase 1 review follow-up clarified that the model layer remains
  data-only; Phase 2 projection construction enforces emergency suppression,
  warning, and overflow invariants when producing snapshots.
- 2026-06-28: Phase 1 review follow-up added an 8192-character defensive decode
  cap for projection-local free-form string fields.
- 2026-06-28: Phase 1 review follow-up locked a Phase 2 task to enforce
  producer-side numeric/string bounds before snapshot JSON encoding.
- 2026-06-28: Phase 1 review follow-up added a 4096-entry defensive decode cap
  for projection-local vector fields.
- 2026-06-28: Phase 1 review follow-up added projection-local optional field
  codecs so omitted/null optional diagnostics use local bounded value decoders
  without relying on ambient core `Option` codecs.
- 2026-06-28: Phase 1 review follow-up added projection-local boolean codecs
  for `emergencySuppressed` instead of relying on ambient core codecs.
- 2026-06-28: Phase 1 review follow-up added asymmetric source/sink child
  availability round-trip coverage and locked Phase 2 required-string
  validation for fields such as `policyMode`.
- 2026-06-28: Phase 1 review follow-up split JSON object assembly into
  required and optional fields so only absent optional fields are omitted, and
  added direct `BigNat.Zero` plus oversized value-type string codec coverage.
- 2026-06-28: Phase 1 review follow-up switched tests to public
  `ValidatorSetHash.fromHex` construction and removed direct `BigNat.One`
  assumptions from the snapshot suite.
- 2026-06-28: Phase 2 added the pure HotStuff gossip diagnostics projection
  engine, projection policy defaults, fail-soft source/sink read and component
  projection boundaries, deterministic sorting, drop aggregation/capping,
  warning/emergency size guards, and producer-side numeric/string/vector bounds.
- 2026-06-28: Post-0029 review follow-up aligned projection-local decoder caps
  with producer policy configurable maxima by switching free-form string decode
  guards to UTF-8 byte length and raising the vector decode cap to the 65536
  producer maximum. A second follow-up kept the external JSON ingress envelope
  bounded by applying the producer 64 MiB estimate product limit as a per-array
  string UTF-8 byte budget in the decoder.
- 2026-06-28: Phase 2 regression coverage confirms source/sink projection,
  legitimate parent absence, one-sided read failure, component projection
  failure, drop overflow metadata, emergency child suppression, and
  contradictory watermark drops. This phase remains diagnostics-only and does
  not touch tx pipeline proposal selection.
- 2026-06-28: Phase 2 review follow-up unified child entry/vector emergency
  limits, added parent emergency suppression and default-policy tests, and made
  projection drop overflow-count aggregation `BigInt`-backed.
- 2026-06-28: Phase 2 review follow-up renamed the child suppression reason
  local, added policy invariant tests, and covered dual child emergency
  suppression with retained projection-drop observability.
- 2026-06-28: Phase 2 review follow-up combined simultaneous child emergency
  suppression reasons, split entry-count and byte-size warning reason codes,
  switched size estimates to UTF-8 bytes, and applied numeric digit caps to
  projected window watermark height/view fields.
- 2026-06-28: Phase 2 review follow-up aligned producer-side free-form string
  bounds with UTF-8 byte estimates and added byte-triggered child/parent
  emergency tests.
- 2026-06-28: Phase 2 review follow-up made UTF-8 warning truncation
  prefix-preserving, widened parent entry summation, and added one-sided
  absence plus numeric/string entry-bound drop coverage.
- 2026-06-28: Phase 2 review follow-up added warning-only entry and byte guard
  coverage and clarified that parent emergency metrics describe the emitted
  suppressed envelope.
- 2026-06-28: Phase 2 review follow-up made overflow group counting
  `BigInt`-backed, removed a redundant legitimate-absence guard, optimized
  UTF-8 truncation, and covered simultaneous source/sink read failures.
- 2026-06-28: Phase 2 review follow-up simplified overflow group counting,
  removed UTF-8 byte-array allocation from string size estimates, and added
  default-policy-present plus UTF-8 warning truncation coverage.
- 2026-06-28: Phase 2 review follow-up widened metrics byte arithmetic,
  streamed UTF-8 truncation, documented exact digit cap behavior, and added
  source projection failure plus entry-count policy invariant coverage.
- 2026-06-28: Phase 2 review follow-up renamed the producer string policy to
  `maxStringUtf8Bytes`, added pre-projection vector capping with overflow drops,
  and clarified `maxVectorEntries` as a per-vector producer-side cap.
- 2026-06-28: Phase 2 review follow-up covered watermark `BigNat` digit-cap
  drops, combined entry/byte child emergency reasons, and documented one-drop
  semantics for rejection rows with invalid free-form reason strings.
- 2026-06-28: Phase 2 review follow-up added drop-group policy invariant
  coverage, made bounded `BigNat` conversion rely on the established
  non-negative precondition, and made warning-message UTF-8 truncation
  single-pass.
- 2026-06-28: Phase 2 review follow-up made projection-drop ordering
  distinguish absent and present-empty contexts, validated test policies through
  the companion constructor, widened parent entry metrics arithmetic, and
  covered warning-only parent snapshots.
- 2026-06-28: Phase 2 review follow-up covered sink numeric projection
  failures, removed dead drop-cap defense after policy validation, avoided a
  temporary topic-count allocation, and included overflow counter fields in the
  parent byte estimate.
- 2026-06-28: Phase 2 review follow-up bounded sorted map projection before
  vector emission, capped projection-drop contexts by UTF-8 byte policy, and
  covered zero-value policy rejections.
- 2026-06-28: Phase 2 review follow-up enforced decimal digit caps on computed
  metrics, covered topic-reason negative-count drops, and switched bounded
  prefix selection to an immutable `TreeSet` top-K implementation.
- 2026-06-28: Phase 2 review follow-up enforced decimal digit caps on
  projection drop counts and overflow counters, covered retained-count numeric
  projection failures, and documented one-drop semantics for watermark rows.
- 2026-06-28: Phase 2 review follow-up made bounded `TreeSet` candidate
  ordering total, normalized projection-drop count failure paths, and covered
  overflow-count digit-cap failures separately from overflow-group failures.
- 2026-06-28: Phase 2 review follow-up clarified emitted-envelope metrics for
  emergency-suppressed children, widened overflow counter byte estimates,
  documented `projectAvailable`'s no-read-error contract, and covered two-sided
  component projection failures plus first-invalid watermark row drops.
- 2026-06-28: Phase 2 review follow-up added producer policy upper bounds for
  estimate safety, clarified deterministic lexicographically-smallest vector
  cap semantics, and moved drop-context truncation after aggregation to avoid
  context collisions.
- 2026-06-28: Phase 2 review follow-up moved legitimately-absent parent
  short-circuiting before drop aggregation, documented the current unique-key
  sort precondition, and covered exact drop-group cap boundary overflow
  counters.
- 2026-06-28: Phase 2 review follow-up widened bounded vector overflow
  counting to `Long`, documented result-level warning consumption, and covered
  watermark `view` digit-cap drops.
- 2026-06-28: Phase 2 review follow-up made drop byte estimates context-aware,
  validated `maxDecimalDigits` against vector count width, and covered
  retention-policy plus chain-height malformed numeric paths.
- 2026-06-28: Phase 2 review follow-up made warning byte estimates message-cap
  aware, asserted result/snapshot warning mirroring, and reordered policy
  validation for clearer `maxVectorEntries` diagnostics.
- 2026-06-28: Phase 2 review follow-up optimized UTF-8 prefix reconstruction,
  covered simultaneous child and parent emergency suppression, and documented
  fixed-structure entry accounting in sink byte estimates.
- 2026-06-28: Phase 2 review follow-up documented `projectAvailable` as a
  no-read-error helper, made over-length blank strings report the size bound
  first, and added an explicit estimate-envelope policy guard.
- 2026-06-28: Phase 2 review follow-up replaced ordinal tie-breaking with
  deterministic per-entry tie keys, made overflow-counter byte estimates use
  policy-capped width, and covered over-length blank required strings.
- 2026-06-28: Phase 2 review follow-up removed a redundant absence guard,
  corrected bounded-sort comments, and covered direct `projectAvailable`
  absence semantics.
- 2026-06-28: Phase 2 review follow-up included drop count digit width in
  parent byte estimates and made malformed retention fixtures assert their
  constructed field values.
- 2026-06-28: Phase 2 review follow-up documented bounded-sort uniqueness
  preconditions, covered estimate product guard validation, and covered blank
  rejection reason drops.
- 2026-06-28: Phase 2 review follow-up covered kept projection-drop count
  digit-cap failures and asserted warning mirroring when both component reads
  fail.
- 2026-06-28: Phase 2 review follow-up separated kept-drop and overflow-count
  digit-cap policies, added source-absent/sink-present coverage, and made the
  bounded-sort helper account for duplicated future iterable candidates without
  `TreeSet` coalescing.
- 2026-06-28: Phase 2 review follow-up raised fixed warning/drop byte-estimate
  overheads, reordered policy cap diagnostics, widened variable-entry
  accumulation, and added relay-rejection tie-key plus new-view watermark/hash
  coverage.
- 2026-06-28: Phase 2 review follow-up avoided validator-set hash string
  allocation in byte estimates, documented estimate overflow bounds, and
  removed positional malformed-retention fixture construction.
- 2026-06-28: Phase 2 review follow-up raised parent wrapper byte-estimate
  headroom, documented intentional string/drop ordering behavior, and covered
  finalized/certified chain-height watermark digit-cap drops.
- 2026-06-28: Phase 2 review follow-up raised structured snapshot byte-estimate
  bases, removed the production-scope unvalidated retention helper, asserted
  emergency warning mirroring, and kept full relay-rejection reason tie keys for
  deterministic capped selection.
- 2026-06-28: Phase 2 review follow-up covered new-view watermark chain-id
  mismatch drops and clarified estimate-overflow safety under the current hard
  policy caps.
- 2026-06-28: Phase 2 review follow-up changed byte-estimate accumulation to
  `BigInt` and covered new-view watermark height/view digit-cap drops.
- 2026-06-28: Phase 2 review follow-up added warning-mirror and source failure
  message assertions, and capped `projectionDroppedEntryGroupLimit` at 4096.
- 2026-06-28: Phase 2 review follow-up documented relay-rejection drop context
  depth, clarified watermark entry-level mismatch drops, and restored
  retention-policy negative-field coverage through a test-only named fixture.
- 2026-06-28: Phase 2 review follow-up documented bounded-sort unique-key
  preconditions without throwing through fail-soft boundaries, documented
  drop-context and watermark-drop guard assumptions, and clarified UTF-8
  truncation plus string-drop test expectations.
- 2026-06-28: Phase 2 review follow-up covered parent-emergency secondary
  metric fail-soft behavior, validation ordering comments, deterministic
  code-unit tie-key ordering, and sink estimate minimum regression coverage.
- 2026-06-28: Phase 3 added `HotStuffNodeRuntime.currentProjectedGossipDiagnostics`
  as a runtime-owned helper that reads the existing in-memory source/sink
  diagnostics accessors, preserves independent read-failure warnings, and
  returns the Phase 2 projection result without changing raw diagnostics
  accessors or tx proposal selection behavior. This remains diagnostics-only
  while BBGO's 500ms gate proposal-selection invariant is investigated
  separately before Maven Central release.
- 2026-06-28: Phase 3 review follow-up added runtime-boundary coverage for
  read-failure isolation through the helper wiring and pinned healthy
  projected snapshot clean-state invariants for suppression and projection-drop
  overflow metadata.
- 2026-06-28: Phase 3 re-review follow-up added sink read-failure symmetry
  coverage and a public `currentProjectedGossipDiagnostics` failure-path test
  using a test-only failing in-memory source handle.
- 2026-06-28: Phase 3 re-review follow-up made runtime read-failure warnings
  generic so projected status snapshots do not expose raw exception
  type/message details, hardened test-only failing handle construction with
  constructor-shape checks, and added public sink read-failure coverage.
- 2026-06-28: Phase 3 re-review follow-up documented the read-failure cause
  boundary: projected snapshots intentionally carry only a generic
  diagnostics-read-failed marker, while raw cause logging and warning
  deduplication remain outside this projection boundary for plan 0029.
- 2026-06-28: Phase 4 updated the HotStuff gossip retention runbook with the
  projected status snapshot contract, reran diagnostics/runtime/retention
  suites, and verified the projection/runtime integration code has no
  downstream BBGO/OpenAPI/DTO/reducer package references. Maven Central release
  remains intentionally held while BBGO's tx pipeline 500ms gate proposal
  selection invariant is investigated separately.
- 2026-06-28: Phase 4 review follow-up clarified that child-level
  `source = None` or `sink = None` in a present projected snapshot must be
  correlated with warnings and emergency suppression metadata before operators
  treat it as legitimate absence or zero retained artifacts.
- 2026-06-28: Phase 4 re-review follow-up documented the default warning
  envelope and emergency guard interpretation so operators can distinguish a
  large-but-emitted child from an emergency-suppressed child.
- 2026-06-28: Phase 4 re-review follow-up documented projection-drop list cap
  and overflow counter semantics so a truncated drop list is not read as
  complete or healthy.

## Background

HotStuff in-memory gossip runtimes now expose source and sink diagnostics that
are useful for memory-pressure, retention, and liveness investigations. The raw
diagnostic types are runtime-owned and contain implementation details such as
retention counters, pruning counters, watermark maps, relay counters, and
rejection reasons.

Embedders that want to show these diagnostics in a status endpoint currently
need to solve the same projection problems themselves:

- source and sink diagnostics can be independently absent or fail to read;
- diagnostic counts and watermarks can exceed JavaScript-safe integer ranges;
- raw maps must be converted into deterministic list shapes for stable JSON;
- malformed or future diagnostic values should not make core runtime status
  unavailable;
- very large diagnostic payloads need size guards before they can be included in
  status responses;
- projection drops must be visible so operators do not read omitted data as a
  healthy zero-count state.

These concerns are HotStuff runtime concerns, not application-domain concerns.
They should be handled in Sigilaris without importing downstream application
models, HTTP models, or reducer-specific types.

The main projected-size risk is cardinality, not artifact body size. Source
event bodies are already bounded by source retention policy, while diagnostic
maps can still grow with chain count, topic count, relay rejection reasons, and
future counters. The projection therefore needs both byte guards and entry
guards.

## Goal

Provide an application-neutral HotStuff gossip diagnostics projection layer that
turns in-memory source/sink diagnostics into a bounded, deterministic,
status-friendly snapshot.

The target behavior is:

1. embedders can ask Sigilaris for a projected diagnostics snapshot without
   depending on raw in-memory internals;
2. source and sink failures are isolated so one failed component does not hide
   the other component;
3. the projected snapshot contains no gossip artifact bodies, signatures,
   certificates, proposals, votes, or application payloads;
4. all public numeric diagnostic values use `BigNat`-backed canonical decimal
   strings or a dedicated equivalent wrapper with the same wire contract;
5. projection drops, child omissions, size warnings, and emergency suppression
   are explicit in the snapshot;
6. the projection API is independent of any downstream repository, API route,
   reducer, transaction, or application status model.

## Scope

- Add a projected HotStuff gossip diagnostics snapshot model under the HotStuff
  runtime package or a nearby diagnostics package.
- Add a projection service/helper from attempted source/sink diagnostic reads to
  the projected snapshot.
- Add projection policy values for entry/byte warning and emergency guards.
- Add projection warnings and metrics as runtime-owned values.
- Preserve deterministic list sorting and deterministic projection-drop capping.
- Add tests for projection success, partial failure, malformed/future values,
  size guards, drop aggregation, and deterministic ordering.
- Update Sigilaris runtime/operator docs to describe the projected snapshot.

## Non-Goals

- Do not add or change an HTTP API, OpenAPI schema, or downstream status model.
- Do not import downstream application repositories, DTOs, transaction models,
  reducers, or API modules.
- Do not change HotStuff source/sink retention behavior. Retention and pruning
  remain covered by plan 0027.
- Do not expose gossip event bodies, artifact bodies, signatures, certificates,
  votes, proposals, or application payloads.
- Do not turn projection warnings into a logging policy. Warning emission and
  deduplication are covered by plan 0029.
- Do not require embedders to use a particular JSON library.

## Related ADRs And Docs

- [ADR-0016: Multiplexed Gossip Session Sync](../adr/0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](../adr/0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0030: HotStuff Transitive Artifact Relay](../adr/0030-hotstuff-transitive-artifact-relay.md)
- [0027 - HotStuff Gossip Sink Retention And Memory Pressure Plan](0027-hotstuff-gossip-sink-retention-and-memory-pressure-plan.md)
- [HotStuff Gossip Sink Retention Runbook](../dev/hotstuff-gossip-sink-retention-runbook.md)
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/InMemoryHotStuffGossipBridge.scala`
- `modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffNodeRuntime.scala`

## Decisions To Lock Before Implementation

1. **Projection owner**
   - The projection belongs to Sigilaris HotStuff runtime code because the input
     diagnostics are Sigilaris runtime diagnostics.
   - The projection must not depend on any downstream status or API module.

2. **Snapshot shape**
   - Use explicit list entries instead of JSON object keys derived from case
     classes:
     - source retention scalar: `retainedEventsLimitPerTopic`;
     - source topic counters: `chainId`, `topic`, `count`;
     - sink policy metadata: `policyMode`;
     - sink retained/pruned count objects;
     - sink relay counters: `topic`, `count`;
     - sink duplicate-suppressed counters: `topic`, `count`;
     - rejection counters: `topic`, `reason`, `count`;
     - retention watermarks: explicit chain/window fields;
     - projection drops: `component`, `field`, `reason`, optional context, and
       `count`.
   - Phase 0 must inventory every field in
     `InMemoryHotStuffSourceDiagnostics` and
     `InMemoryHotStuffSinkDiagnostics`. Each field must be projected or
     explicitly excluded with a documented reason; silent omission is not
     allowed.
   - Phase 0 must also inventory `HotStuffWindow` and decide how each window
     watermark decomposes into explicit numeric fields. Do not project a window
     watermark through opaque `toString` output.
   - Keep source and sink independently optional in the parent snapshot.
   - Parent absence means both source and sink were legitimately absent and no
     projection metadata is present.

3. **Numeric contract**
   - Reuse `BigNat` as the default non-negative numeric wrapper for projected
     diagnostic numbers.
   - All projected numeric diagnostic fields use the same public string
     convention for consistency, including current `Int`, `Long`, and `BigInt`
     source fields. This includes source retention limits, source/sink counters,
     retention policy values, retained/pruned counts, heights, views, projection
     drop counts, and overflow counts.
   - Public encoding must be a canonical non-negative decimal string.
   - The canonical form rejects signs, blank strings, non-ASCII digits, and
     unnecessary leading zeros except the value `"0"`.
   - Phase 0 must confirm whether the existing `BigNat` JSON codec is sufficient
     for the projected snapshot's decoder strictness. Add a thin
     projection-local codec around `BigNat` only if the existing codec accepts
     input forms the snapshot contract rejects.
   - Runtime-owned typed values can remain typed internally; the projected
     status-friendly snapshot must not require consumers to rely on JavaScript
     safe-integer behavior.

4. **Failure policy**
   - A source read failure makes the source child unavailable and emits a source
     read-failure warning; it must not hide a healthy sink child.
   - A sink read failure follows the same rule independently.
   - Projection failure inside one component makes only that component
     unavailable where possible.
   - Parent assembly failure omits the parent snapshot and emits a parent
     projection-failure warning.

5. **Size policy**
   - The projection should have a release-supported envelope and an emergency
     suppression guard.
   - Initial defaults can mirror the current operational envelope:
     - warning envelope: 256 KiB or 512 variable entries per child;
     - emergency guard: 1 MiB or 4096 variable entries per child or parent.
   - `projectionDroppedEntries` is capped at 128 groups.
   - These values should live in a policy object so future runtimes can tune
     them without changing snapshot semantics.

6. **Drop observability**
   - Invalid or unsupported entries are dropped at the smallest practical unit.
   - Projection drops are aggregated and capped deterministically.
   - The parent snapshot exposes overflow group count and overflow count sum so
     a capped drop list is not mistaken for a complete list.

7. **Compatibility**
   - New snapshot fields should be additive and optional where possible.
   - Existing raw in-memory diagnostics accessors remain available.
   - Future extensions to fixed objects should not make old projected snapshots
     undecodable by consumers that ignore unknown fields.

## Phase 0 Contract Lock Results

The projected snapshot is a runtime-owned data model in
`org.sigilaris.node.jvm.runtime.consensus.hotstuff`. It is not an HTTP DTO and
does not import transport, reducer, transaction, or downstream status modules.
Runtime gossip identifier value types such as `ChainId` and `GossipTopic` are
explicitly in scope because they are the diagnostic keys being projected; gossip
transport machinery and wire DTOs remain out of scope. These identifiers live
in the shared `node-common` gossip model rather than the JVM transport adapter
package.

### Parent Snapshot Shape

- `HotStuffGossipDiagnosticsSnapshot`
  - `source: Option[HotStuffGossipSourceDiagnosticsSnapshot]`
  - `sink: Option[HotStuffGossipSinkDiagnosticsSnapshot]`
  - `warnings: Vector[HotStuffGossipDiagnosticsProjectionWarning]`
  - `metrics: HotStuffGossipDiagnosticsProjectionMetrics`
  - `projectionDroppedEntries: Vector[HotStuffGossipProjectionDroppedEntry]`
  - `projectionDroppedEntryOverflowGroups: BigNat`
  - `projectionDroppedEntryOverflowCount: BigNat`
  - `emergencySuppressed: Boolean`
  - `emergencySuppressionReason: Option[String]`

Parent absence means both source and sink diagnostics are legitimately absent
and no projection metadata is available. When either child exists or any read
failed, the parent snapshot exists with child availability represented
independently. The plan's "parent snapshot model" is this top-level
`HotStuffGossipDiagnosticsSnapshot` envelope; no second parent-only case class
is introduced.

### Source Diagnostics Inventory

Every current `InMemoryHotStuffSourceDiagnostics` field is projected:

- `retainedEventsPerTopic: Int` projects as
  `retainedEventsLimitPerTopic: BigNat`.
- `retainedEventsByTopic: Map[ChainTopic, Int]` projects as deterministic
  `retainedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.
- `appendedEventsByTopic: Map[ChainTopic, Long]` projects as deterministic
  `appendedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.
- `prunedEventsByTopic: Map[ChainTopic, Long]` projects as deterministic
  `prunedEventsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.
- `readByIdMissesByTopic: Map[ChainTopic, Long]` projects as deterministic
  `readByIdMissesByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.
- `invalidCursorRejectionsByTopic: Map[ChainTopic, Long]` projects as
  deterministic
  `invalidCursorRejectionsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.
- `staleCursorRejectionsByTopic: Map[ChainTopic, Long]` projects as
  deterministic
  `staleCursorRejectionsByTopic: Vector[HotStuffGossipChainTopicCountSnapshot]`.

`HotStuffGossipChainTopicCountSnapshot` fields are `chainId: ChainId`,
`topic: GossipTopic`, and `count: BigNat`. Entries are sorted by
`chainId.value`, then `topic.value`. `ChainTopic` is decomposed by projecting
its final case-class `chainId: ChainId` field into `chainId` and its final
case-class `topic: GossipTopic` field into `topic`.

No source event bodies, event IDs, artifact bodies, signatures, proposals,
votes, certificates, or payloads are projected.

### Sink Diagnostics Inventory

Every current `InMemoryHotStuffSinkDiagnostics` field is projected:

- `policyMode: String` projects as `policyMode: String`.
- `retentionPolicy: HotStuffArtifactSinkRetention` projects as
  `retentionPolicy: HotStuffSinkRetentionPolicySnapshot`.
- `retainedCounts: HotStuffSinkRetainedCounts` projects as
  `retainedCounts: HotStuffSinkRetentionCountsSnapshot`.
- `prunedCounts: HotStuffSinkPrunedCounts` projects as
  `prunedCounts: HotStuffSinkRetentionCountsSnapshot`.
- `retentionWatermarks: HotStuffSinkRetentionWatermarks` projects as
  `retentionWatermarks: HotStuffSinkRetentionWatermarksSnapshot`.
- `relayedValidatedArtifactsByTopic: Map[GossipTopic, Long]` projects as
  deterministic `relayedValidatedArtifactsByTopic:
  Vector[HotStuffGossipTopicCountSnapshot]`.
- `duplicateArtifactsSuppressedByTopic: Map[GossipTopic, Long]` projects as
  deterministic `duplicateArtifactsSuppressedByTopic:
  Vector[HotStuffGossipTopicCountSnapshot]`.
- `rejectedArtifactsByTopicAndReason:
  Map[InMemoryHotStuffRelayRejectionKey, Long]` projects as deterministic
  `rejectedArtifactsByTopicAndReason:
  Vector[HotStuffGossipTopicReasonCountSnapshot]`.

`HotStuffSinkRetentionPolicySnapshot` fields are `finalizedHeightLag`,
`certifiedHeightLag`, `retainedTimeoutWindows`, `retainedNewViewWindows`,
`retainedDuplicateEvents`, `retainedRejectedEventSamples`, and
`pruneEveryAcceptedEvents`; all numeric fields use `BigNat`.

`HotStuffSinkRetentionCountsSnapshot` fields are `proposals`, `votes`,
`voteAccumulatorVotes`, `voteAccumulatorEquivocationKeys`, `timeoutVotes`,
`timeoutAccumulatorVotes`, `timeoutAccumulatorEquivocationKeys`,
`timeoutCertificates`, `newViews`, `newViewsBySenderWindow`, `qcs`,
`safetyFaults`, and `duplicateSamples`; all numeric fields use `BigNat`.

`HotStuffGossipTopicCountSnapshot` fields are `topic: GossipTopic` and
`count: BigNat`. Entries are sorted by `topic.value`.

`HotStuffGossipTopicReasonCountSnapshot` fields are `topic: GossipTopic`,
`reason: String`, and `count: BigNat`. Entries are sorted by `topic.value`,
then `reason`.

No sink proposal, vote, QC, timeout certificate, new-view, duplicate event, or
payload body is projected.

### Watermark Decomposition

`HotStuffSinkRetentionWatermarksSnapshot` has four list fields:

- `finalizedRetainFromHeightByChain:
  Vector[HotStuffGossipChainHeightWatermarkSnapshot]`
- `certifiedRetainFromHeightByChain:
  Vector[HotStuffGossipChainHeightWatermarkSnapshot]`
- `retainedTimeoutWindowFloorByChain:
  Vector[HotStuffGossipWindowWatermarkSnapshot]`
- `retainedNewViewWindowFloorByChain:
  Vector[HotStuffGossipWindowWatermarkSnapshot]`

`HotStuffGossipChainHeightWatermarkSnapshot` fields are `chainId: ChainId` and
`height: BigNat`, sorted by `chainId.value`.

`HotStuffGossipWindowWatermarkSnapshot` decomposes `HotStuffWindow` explicitly
as `chainId: ChainId`, `height: BigNat`, `view: BigNat`, and
`validatorSetHash: ValidatorSetHash`; it never uses `HotStuffWindow.toString`.
For `Map[ChainId, HotStuffWindow]` watermarks, the map key is the projected
`chainId` source of truth. If the map key and `HotStuffWindow.chainId` differ,
the entry is dropped with a sink watermark `projectionDroppedEntries` entry
at the map field path instead of projecting a contradictory watermark. Because
each watermark map has at most one entry per chain, entries are sorted by
`chainId.value`.
If a watermark row has multiple invalid projected sub-fields, the row is
dropped once at the first invalid sub-field encountered in deterministic field
order. The drop identifies the field path, for example
`retentionWatermarks.retainedTimeoutWindowFloorByChain.height`.

### Numeric And Codec Contract

All public projected numeric diagnostic values use `BigNat`, including current
`Int`, `Long`, and `BigInt` raw values. Current fixed scalar fields are
runtime-invariant non-negative values: source retention is positive by
`HotStuffArtifactSourceRetention.fromRetainedEventsPerTopic`, sink retention
policy constructors reject invalid values, retained counts come from collection
sizes, and pruned counts come from non-negative deltas. If a future or synthetic
fixed scalar violates those invariants, the affected component projection fails
and the child is omitted with a component projection-failure warning. Map/list
entries remain the smallest droppable unit: negative or otherwise malformed
entry values, including synthetic negative retention watermark heights, are
dropped and recorded in `projectionDroppedEntries`.

The public JSON contract for projected diagnostics is canonical non-negative
decimal strings: `"0"` or an ASCII digit sequence not beginning with `0`.
Signs, blank strings, non-ASCII digits, numeric JSON values, and unnecessary
leading zeroes are rejected by the projected snapshot decoder. The
projection-local decoder also rejects decimal strings longer than 4096 digits
to avoid allocating unbounded `BigInt` values from malformed status input. The
encoder remains a total `BigNat` string projection; Phase 2 projection assembly
is responsible for keeping emitted diagnostic values within the supported
snapshot envelope before encoding.

The existing `BigNat` JSON encoder is sufficient only when the projected
diagnostics codec fixes `writeBigIntAsString = true`; it is not sufficient as a
fully generic configurable encoder because non-default config can emit JSON
numbers. The existing `BigNat` JSON decoder is not strict enough for this
snapshot because it accepts JSON numbers and delegates string parsing through
`BigInt`. Phase 1 therefore adds a projection-local strict decimal codec around
`BigNat` for projected diagnostics JSON encoding and decoding. That codec always
emits canonical decimal strings and only decodes canonical decimal strings,
independent of ambient `JsonConfig`.

Projection-local free-form string fields such as warning reasons, messages,
drop fields, drop reasons, drop contexts, and policy mode are decoded with an
8192-character maximum to keep malformed status input bounded. Runtime
identifier strings continue to use their own parser validation.

Producer-side projection applies the corresponding free-form string policy as a
UTF-8 byte bound so byte-estimate warning and emergency guards cannot be
underestimated by multibyte text. Snapshot metrics describe the emitted
snapshot envelope; a child suppressed by an emergency guard contributes zero
emitted child entries/bytes and carries the suppression reason plus warnings for
operator triage. Computed metric values are also checked against the producer
decimal digit cap; if the emitted parent metrics cannot satisfy the cap, parent
assembly fails soft with a parent projection-failure warning rather than
returning an out-of-policy snapshot.

Current raw source/sink diagnostic maps emit at most one drop per
`component`/`field`/`reason`/`context` group. The aggregation count and
`BigInt`-backed overflow count remain in the projection engine for future raw
diagnostic surfaces that may emit repeated drops for the same group.
For projected rejection rows, an invalid free-form `reason` string drops the
whole row once with count `1`; the row's numeric count is not projected and does
not emit a second independent count drop.

Projection-local vector fields are decoded with a 4096-entry maximum. Phase 2
producer-side projection policy still owns normal warning/emergency envelope
enforcement before snapshots are encoded. Producer-side vector caps keep the
lexicographically smallest entries by the documented deterministic sort key and
record omitted larger keys through vector overflow drops.

Projection-drop contexts are grouped using the raw runtime context and bounded
to the producer UTF-8 byte cap only when emitted in
`projectionDroppedEntries`, so context truncation does not merge distinct drop
groups before aggregation.

Projection-local optional fields encode `None` as `null` before object assembly
omits the field, and decode both omitted and explicit `null` values as absent.
Present optional values are decoded through the same local bounded value codecs
as required fields.

Projection-local booleans are encoded and decoded as JSON booleans.

### Failure And Absence Semantics

- No in-memory source and no in-memory sink: parent snapshot is `None`.
- Source absent with sink present: parent snapshot is present with
  `source = None` and the sink child projected.
- Sink absent with source present: parent snapshot is present with
  `sink = None` and the source child projected.
- Source read failure: source child is unavailable, a source read-failure
  warning is emitted, and sink projection continues.
- Sink read failure: sink child is unavailable, a sink read-failure warning is
  emitted, and source projection continues.
- Component projection failure: only the failed component is omitted where
  possible, with a component projection-failure warning.
- Parent assembly failure: the parent snapshot is omitted and a parent
  projection-failure warning is returned through the effect boundary when
  available to the caller.
- A child set to `None` for any reason other than legitimate absence always
  carries a corresponding warning or emergency suppression marker. Consumers can
  distinguish legitimate absence from failed, omitted, or suppressed diagnostics
  by checking `warnings`, `emergencySuppressed`, and
  `emergencySuppressionReason`.
- `emergencySuppressed` is set only for size/emergency guard breaches; read and
  projection failures use warnings without setting the emergency marker.

### Size And Drop Policy

`HotStuffGossipDiagnosticsProjectionPolicy.default` locks these values:

- child warning envelope: 256 KiB estimated projection size or 512 variable
  entries;
- child emergency guard: 1 MiB estimated projection size or 4096 variable
  entries;
- parent emergency guard: 1 MiB estimated projection size or 4096 variable
  entries;
- `projectionDroppedEntries` group cap: 128.
- producer policy hard caps: `maxDecimalDigits <= 4096`,
  `projectionDroppedEntryGroupLimit <= 4096`,
  `maxStringUtf8Bytes <= 1048576`, and `maxVectorEntries <= 65536`.
  The policy also rejects configurations whose
  `maxVectorEntries * maxStringUtf8Bytes` product exceeds the supported
  estimate envelope. Overflow groups can still span multiple capped vectors;
  if their counters exceed the decimal digit cap, parent assembly fails soft
  with a parent projection-failure warning.

Warning envelope breaches keep the projected child and add warnings. Emergency
guard breaches suppress the smallest practical child or parent snapshot and add
an explicit suppression reason.

Projection drops are aggregated by `component`, `field`, `reason`, and optional
`context`, sorted deterministically, and capped at 128 groups. Overflow metadata
is exposed as `projectionDroppedEntryOverflowGroups` and
`projectionDroppedEntryOverflowCount`, both `BigNat`.

## Change Areas

### Code

- Add projected snapshot models, for example:
  - `HotStuffGossipDiagnosticsSnapshot`;
  - `HotStuffGossipSourceDiagnosticsSnapshot`;
  - `HotStuffGossipSinkDiagnosticsSnapshot`;
  - `HotStuffSinkRetentionPolicySnapshot`;
  - `HotStuffSinkRetentionCountsSnapshot`;
  - `HotStuffSinkRetentionWatermarksSnapshot`;
  - `HotStuffGossipTopicCountSnapshot`;
  - `HotStuffGossipTopicReasonCountSnapshot`;
  - `HotStuffGossipProjectionDroppedEntry`;
  - `HotStuffGossipDiagnosticsProjectionWarning`;
  - `HotStuffGossipDiagnosticsProjectionMetrics`.
- Add a projection helper, for example:
  `HotStuffGossipDiagnosticsProjection.project(sourceRead, sinkRead, policy)`.
- Reuse `BigNat` for projected non-negative diagnostic numbers unless Phase 0
  proves a projection-local strict codec is required.
- Keep JSON codecs, if added, Sigilaris-owned and independent of any embedder
  HTTP shape.

### Tests

- Add projection tests in the HotStuff runtime test package.
- Cover source-only, sink-only, both-present, both-absent, source-read-failure,
  sink-read-failure, and parent projection-failure cases.
- Cover deterministic list ordering for all public list fields.
- Cover complete projection of source retention limit, source counters,
  `policyMode`, retention policy, retained/pruned counts, watermarks, relay
  counters, duplicate-suppressed counters, rejection counters, and projection
  drops.
- Cover canonical decimal-string rendering and rejection of malformed future
  values if decoding helpers are included.
- Cover drop aggregation, 128-group cap behavior, overflow counts, and
  deterministic tie-breaking.
- Cover warning and emergency size guards at exact boundary values.

### Docs

- Update the HotStuff gossip sink retention runbook with the projected snapshot
  interpretation.
- Document that missing diagnostics means unavailable/absent, not zero retained
  artifacts.
- Document the release-supported envelope and emergency suppression markers.

## Implementation Phases

### Phase 0: Contract Lock

- Lock snapshot field names and list-entry shapes.
- Lock the source/sink diagnostic field inventory and complete projection or
  explicit exclusion policy.
- Lock `HotStuffWindow` watermark decomposition into explicit numeric fields.
- Lock `BigNat` reuse and numeric canonicalization policy.
- Lock that all projected numeric fields, including current `Int` fields, use
  the same decimal-string public convention.
- Lock source/sink absence, read-failure, projection-failure, and size-omission
  semantics.
- Lock projection policy defaults and decide whether they need configuration.
- Lock `projectionDroppedEntries` at 128 groups.

### Phase 1: Snapshot Models

- Add application-neutral snapshot case classes.
- Add projection warning and metrics types.
- Add `BigNat`-backed numeric fields and projection-local strict codecs only if
  Phase 0 requires them.
- Add JSON codecs only if they can remain Sigilaris-owned and generic.

### Phase 2: Projection Engine

- Implement source projection.
- Implement sink projection.
- Implement drop aggregation, deterministic sorting, and cap overflow metadata.
- Implement size envelope and emergency suppression behavior.
- Add fail-soft boundaries for component and parent projection.

### Phase 3: Runtime Integration

- Add runtime helper methods or service wiring that reads current source/sink
  diagnostics and returns a projected snapshot.
- Keep raw diagnostics accessors intact.
- Ensure integration introduces no downstream module dependency.

### Phase 4: Verification And Docs

- Run focused HotStuff runtime projection tests.
- Run existing in-memory source/sink retention suites.
- Update runtime/operator docs.
- Review public package boundaries for downstream dependency leaks.

## Test Plan

- Unit-test projection from synthetic source and sink diagnostics.
- Unit-test malformed/future raw values using synthetic fixtures where current
  runtime types cannot produce malformed values directly.
- Unit-test byte and entry size boundaries.
- Unit-test parent assembly failure through a deliberately failing test hook or
  equivalent controlled fixture.
- Run:
  - `sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.*Diagnostics*'`
  - `sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.InMemoryHotStuffSinkRetentionSuite'`
  - any existing HotStuff runtime service suite that reads in-memory diagnostics.

## Risks And Mitigations

- Risk: projection model becomes a downstream API clone.
  - Mitigation: keep the model in Sigilaris terms only and prohibit downstream
    DTO imports in tests and implementation.
- Risk: large diagnostics still overload status polling.
  - Mitigation: enforce entry and byte guards before returning a full child or
    parent snapshot.
- Risk: malformed future values make all diagnostics unavailable.
  - Mitigation: drop malformed entries at the smallest practical unit and expose
    projection drops.
- Risk: operators read omitted data as zero.
  - Mitigation: explicit omission reasons, size-warning markers, and docs.
- Risk: projection semantics drift from retention implementation.
  - Mitigation: keep projection tests close to source/sink diagnostics fixtures
    and run retention suites together with projection suites.

## Acceptance Criteria

1. Sigilaris exposes an application-neutral projected HotStuff gossip
   diagnostics snapshot.
2. The projection has no dependency on downstream application modules, API
   routes, reducers, transactions, or status DTOs.
3. Source and sink read/projection failures are independent.
4. Projected numeric values are `BigNat`-backed and encode as canonical decimal
   strings, or Phase 0 documents why a strict projection-local codec is needed.
5. Large diagnostics are bounded by explicit warning and emergency guards.
6. Projection drops and overflow metadata are visible in the snapshot.
7. The projection covers every current source/sink diagnostics field or
   explicitly documents an exclusion.
8. Focused projection tests and existing HotStuff retention/runtime diagnostics
   tests pass.

## Checklist

### Phase 0: Contract Lock

- [x] Lock snapshot field names and list-entry shapes.
- [x] Inventory source/sink diagnostics fields and require complete projection
      or explicit exclusion.
- [x] Lock `HotStuffWindow` watermark field decomposition.
- [x] Lock `BigNat` reuse and canonical decimal-string policy.
- [x] Lock whether current `Int` fields also encode as decimal strings.
- [x] Lock source/sink absence and failure semantics.
- [x] Lock projection size policy defaults.
- [x] Lock `projectionDroppedEntries` at 128 groups.
- [x] Confirm no downstream dependency is needed.

### Phase 1: Snapshot Models

- [x] Add source/sink/parent snapshot models.
- [x] Add retention policy/count/watermark snapshot models.
- [x] Add projection drop, warning, and metrics models.
- [x] Add `BigNat`-backed projected numeric fields.
- [x] Add projection-local strict decimal codec only if Phase 0 requires it.
- [x] Add model/codecs tests.

### Phase 2: Projection Engine

- [x] Implement source diagnostics projection.
- [x] Implement sink diagnostics projection.
- [x] Implement deterministic sorting.
- [x] Implement projection drop aggregation and capping.
- [x] Implement release-envelope and emergency suppression behavior.
- [x] Enforce producer-side numeric/string/vector bounds before JSON encoding.
- [x] Reject or drop blank required strings such as sink `policyMode` during
      producer-side projection assembly.
- [x] Add component and parent fail-soft boundaries.

### Phase 3: Runtime Integration

- [x] Add runtime helper/service wiring for projected diagnostics.
- [x] Preserve raw diagnostics accessors.
- [x] Verify module dependency graph remains application-neutral.

### Phase 4: Verification And Docs

- [x] Add projection regression tests.
- [x] Run HotStuff diagnostics and sink retention suites.
- [x] Update runtime/operator docs.
- [x] Review package boundaries for reverse dependency leaks.

## Follow-Ups

- Consider an ADR if the projected snapshot becomes the long-term stable
  embedder-facing diagnostics contract.
- Consider adding optional metrics exporters after the snapshot contract is
  stable.
- Consider sharing `BigNat`-backed projected numeric conventions with other
  runtime diagnostics surfaces.
