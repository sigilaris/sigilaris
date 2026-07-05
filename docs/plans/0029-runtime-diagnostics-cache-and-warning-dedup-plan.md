# 0029 - Runtime Diagnostics Cache And Warning Dedup Plan

## Status
Complete - Maven Central release held before deploy

## Created
2026-06-27

## Last Updated
2026-06-28

## Progress

- 2026-06-28: Plan document prepared on top of completed plan 0028 projection
  API work. Maven Central release remains intentionally held before deploy
  while BBGO's tx pipeline proposal-selection invariant is investigated
  separately.
- 2026-06-28: Phase 0 locked the utility package boundary, `Temporal[F]`
  effect constraint, background single-flight refresh semantics, cancellation
  behavior, LRU warning-dedup semantics, defaults, and HotStuff integration
  ownership above the existing `Sync`-bounded runtime.
- 2026-06-28: Phase 1 added the application-neutral
  `RuntimeDiagnosticsCache` utility, cache policy validation, and deterministic
  cats-effect tests for TTL freshness, single-flight refresh, refresh failure,
  canceled waiters, refresh-fiber cancellation retry, and legitimate empty
  values.
- 2026-06-28: Phase 2 added the application-neutral
  `DiagnosticsWarningDedup` utility, warning key-cap policy validation,
  deterministic LRU recency refresh, caller-owned overflow warning values, and
  `TestControl` coverage for overflow suppression and re-arm behavior.
- 2026-06-28: Phase 3 added `HotStuffProjectedGossipDiagnostics` as a
  `Temporal[F]` wrapper above `HotStuffNodeRuntime`, with cached projected
  reads, stable HotStuff warning keys, and caller-owned warning emission dedup
  that leaves status result warnings unchanged.
- 2026-06-28: Phase 4 verified the utility and HotStuff diagnostics suites,
  updated the HotStuff retention runbook with cache/dedup usage guidance, and
  completed plan 0029 before Maven Central deployment. Release and BBGO
  integration remain intentionally held while BBGO tx pipeline proposal
  selection is investigated separately.
- 2026-06-28: Post-review follow-up documented that warning dedup has no
  per-key time-based re-arm; repeated keys stay suppressed until helper restart
  or LRU eviction, and periodic reminders remain caller-owned.

## Background

Runtime diagnostics are often read through polling status endpoints, operator
scripts, or repeated local health checks. Some diagnostics are cheap, but others
may require reading multiple runtime components, assembling bounded snapshots,
or measuring encoded size. Under tight polling, even bounded diagnostics can
become unnecessary load.

Diagnostics also produce warnings when reads fail, projection drops entries, or
size guards suppress large details. Emitting the same warning on every poll can
flood logs and hide the first useful signal. A bounded warning deduplication
utility is useful beyond one HotStuff diagnostics surface.

The cache and warning-dedup behavior should be Sigilaris runtime infrastructure,
not a downstream application concern. It must not depend on any application
repository, HTTP framework, API model, reducer, or transaction type.

## Goal

Provide reusable runtime diagnostics utilities:

1. a small TTL cache with single-flight refresh and cancellation-safe waiter
   handling;
2. a bounded warning deduplication helper with recency refresh and overflow
   warning support;
3. integration guidance for HotStuff projected diagnostics from plan 0028;
4. tests that prove polling does not trigger duplicate refreshes or repeated
   warning floods.

## Scope

- Add a generic diagnostics cache abstraction in a runtime diagnostics package.
- Add a generic warning deduplication abstraction that can work with any warning
  type that supplies a stable key.
- Add HotStuff diagnostics integration that uses the cache/dedup utilities for
  projected gossip diagnostics.
- Add tests for cache TTL, single-flight behavior, refresh failure,
  cancellation, warning dedup, LRU/recency behavior, and overflow warning
  re-arming.
- Add docs explaining when runtime diagnostics should use these utilities.

## Non-Goals

- Do not add a status endpoint, HTTP middleware, OpenAPI schema, or application
  route.
- Do not depend on downstream application API models or logging frameworks.
- Do not persist cached diagnostics or warning-dedup state across process
  restart.
- Do not make every runtime diagnostic surface use the cache. Each surface must
  still decide whether caching is appropriate.
- Do not define the HotStuff projection snapshot itself. That belongs to plan
  0028.
- Do not hide warnings permanently; dedup suppresses repeats while keeping the
  first occurrence and bounded overflow signal visible.

## Related ADRs And Docs

- [ADR-0022: HotStuff Pacemaker And View-Change Baseline](../adr/0022-hotstuff-pacemaker-and-view-change-baseline.md)
- [ADR-0030: HotStuff Transitive Artifact Relay](../adr/0030-hotstuff-transitive-artifact-relay.md)
- [0027 - HotStuff Gossip Sink Retention And Memory Pressure Plan](0027-hotstuff-gossip-sink-retention-and-memory-pressure-plan.md)
- [0028 - HotStuff Gossip Diagnostics Projection Snapshot Plan](0028-hotstuff-gossip-diagnostics-projection-snapshot-plan.md)
- [HotStuff Gossip Sink Retention Runbook](../dev/hotstuff-gossip-sink-retention-runbook.md)

## Decisions To Lock Before Implementation

1. **Generic utility boundary**
   - The cache should be parameterized by value type.
   - Warning dedup should be parameterized by warning type and key extraction.
   - Neither utility should know about HotStuff, HTTP, JSON, or application
     status models.

2. **Effect type**
   - The cache cannot be implemented with `Sync` alone. Single-flight refresh,
     `Deferred` waiters, cancellation-safe waiter completion, and monotonic TTL
     require at least `Concurrent[F]` plus `Clock[F]`, or `Temporal[F]` if the
     implementation uses the combined cats-effect capability.
   - The warning dedup helper can be implemented with a weaker effect in
     isolation, but using the same `Temporal[F]` policy is acceptable if that
     keeps the utility API simpler.
   - Phase 0 must decide whether HotStuff diagnostics integration raises only
     the diagnostics assembly path to `Temporal[F]` or raises a broader runtime
     service construction constraint. Do not raise the in-memory artifact source
     or sink from `Sync` unless integration proves that is necessary.
   - The cache instance is stateful and must be constructed in an effect. It
     should live in runtime assembly or embedder-owned diagnostics wiring above
     `HotStuffNodeRuntime`, not inside the `Sync`-only runtime case class. The
     existing `HotStuffNodeRuntime.currentProjectedGossipDiagnostics` method
     remains uncached and `Sync`-bounded; cached HotStuff diagnostics use a
     separate wrapper/helper that can require `Temporal[F]`.

3. **Cache semantics**
   - Cache stores successful refresh results, including legitimate `None` or
     empty diagnostics values when the value type permits them.
   - Refresh failures do not poison the cache permanently.
   - Concurrent cache misses share one in-flight refresh.
   - If a caller waiting on a refresh is canceled, only that caller observes
     cancellation. Non-canceled waiters must not be force-canceled merely
     because another poller canceled.
   - If the refresh owner is canceled before completing the shared result, the
     cache returns to empty and non-canceled waiters retry or elect a new
     refresh so a canceled poller does not permanently fail unrelated pollers.
   - TTL is measured with monotonic time.

4. **Warning dedup semantics**
   - First occurrence of a warning key is emitted.
   - Repeated occurrences of the same key are suppressed.
   - Seeing an existing key refreshes its recency.
   - The key set is bounded.
   - When the key cap is exceeded, the least-recently-used key is evicted.
   - Evicting keys can emit a bounded overflow warning, re-armed after a
     configured interval.
   - Warning emission remains caller-owned; the utility does not choose a
     logging framework or severity.

5. **Configuration defaults**
   - Initial cache TTL should be short, for example one second, so status
     freshness remains useful while tight polling is coalesced.
   - Initial warning-key cap can be 1024 keys.
   - Initial overflow warning interval can be one minute.
   - Defaults should be overrideable in tests and runtime assembly.

## Change Areas

### Code

- Add a reusable diagnostics cache, for example:
  `RuntimeDiagnosticsCache[F, A]` with a minimum typeclass of
  `Concurrent[F]` plus `Clock[F]`, or `Temporal[F]`.
- Add a reusable warning dedup helper, for example:
  `DiagnosticsWarningDedup[F, W]`.
- Add small support types if needed:
  - cache state model;
  - warning key type;
  - overflow warning factory;
  - policy/config case classes.
- Integrate the utilities with HotStuff projected diagnostics only after plan
  0028 exposes a projection API.
  Plan 0028 returns projection warnings as data on
  `HotStuffGossipDiagnosticsProjectionResult.warnings`; it does not provide a
  warning emission callback. Plan 0029 therefore introduces the caller-owned
  emission/logging adapter that consumes `result.warnings` and applies warning
  dedup there.

### Tests

- Cache tests:
  - cache hit before TTL expiry;
  - refresh after TTL expiry;
  - single-flight concurrent miss;
  - failed refresh resets state;
  - canceled waiter cleanup and refresh-owner cancellation retry/election;
  - legitimate empty value is cached.
- Warning dedup tests:
  - first warning emits;
  - repeated warning is suppressed;
  - repeated warning refreshes recency;
  - key cap evicts the least-recently-used key deterministically;
  - overflow warning emits once per interval;
  - overflow warning re-arms after interval.
- Integration tests:
  - tight diagnostics polling does not repeatedly call projection reads inside
    the TTL;
  - repeated projection result warnings are deduplicated at the caller-owned
    emission adapter.

### Docs

- Document utility semantics in the runtime diagnostics or HotStuff runbook.
- Document recommended defaults and when not to cache diagnostics.
- Document that callers own warning emission and severity.

## Implementation Phases

Plan 0028 must define the projected HotStuff gossip diagnostics API before this
plan's Phase 3 starts. Phases 0-2 can proceed independently because the generic
cache and warning-dedup utilities do not depend on the projection model.

### Phase 0: Utility Contract Lock

- Lock cache success/failure/cancellation semantics.
- Lock warning dedup key and overflow semantics.
- Decide effect-type generality.
- Decide default TTL, key cap, and overflow interval.

## Phase 0 Contract Lock Results

### Package Boundary

The generic utilities will live under
`org.sigilaris.node.jvm.runtime.diagnostics`. This keeps them outside the
HotStuff package while still placing the cats-effect runtime support in the JVM
node runtime module where the current diagnostics readers are assembled. The
package must not import downstream application, HTTP, OpenAPI, reducer,
transaction, or status DTO modules.

HotStuff-specific wiring can live under
`org.sigilaris.node.jvm.runtime.consensus.hotstuff`, but it may only depend on
the generic runtime-diagnostics utilities and the plan 0028 projected gossip
diagnostics API.

### Effect Constraint

The generic cache and warning dedup utilities use `Temporal[F]`.
`Temporal[F]` is intentionally stronger than the raw in-memory source/sink
`Sync` requirement because the cache needs `Ref`, `Deferred`, fiber start, and
monotonic time for TTL decisions. Keeping a single `Temporal[F]` constraint for
both utilities keeps policy construction and deterministic `IO` tests simple.

This stronger constraint must not be pushed down into
`HotStuffNodeRuntime[F[_]: Sync]`, `InMemoryHotStuffArtifactSource`, or
`InMemoryHotStuffArtifactSink`. Cached HotStuff diagnostics are built by a
separate wrapper/helper in runtime assembly or embedder diagnostics wiring above
the existing runtime. `HotStuffNodeRuntime.currentProjectedGossipDiagnostics`
remains the uncached, `Sync`-bounded source of truth.

### Cache Semantics

The cache stores only successful refresh results. Success includes legitimate
empty values such as `None`, empty vectors, and projection results whose
snapshot is absent with empty warnings. Refresh failures fail the current call,
complete current waiters with the same failure, clear in-flight state, and do
not replace the last successful cached value.

The cache does not serve stale values after a failed refresh in the initial
implementation. A caller that wants stale-on-error behavior must build that
policy outside this utility so the basic cache contract remains simple and
testable.

Concurrent cache misses share one in-flight refresh. The first miss creates an
in-flight `Deferred` and starts the refresh in a cache-owned background fiber so
the refresh is not canceled merely because one polling caller is canceled.
Canceled waiters stop waiting without canceling the shared refresh. When the
refresh succeeds, the cache records the value with a monotonic expiry deadline
and completes the in-flight waiters. When the refresh fails or the refresh fiber
itself is canceled, the cache clears the in-flight state so a later or
non-canceled waiter can retry and elect a new refresh.

TTL is measured with `Clock[F].monotonic`. A value is fresh when the current
monotonic time is strictly before its expiry deadline. A value at or after its
deadline is stale and triggers the single-flight refresh path.

Default cache TTL is one second. Policies reject non-positive TTL values.

### Warning Dedup Semantics

Warning dedup is generic over warning type `W` and caller-provided stable key
type `K`. The utility does not know about HotStuff warning fields, logging
severity, or status response shape.

The first occurrence of a key is emitted. A repeated occurrence is suppressed
and refreshes that key's recency. When the configured key cap is exceeded, the
least-recently-used key is evicted deterministically. After eviction, a later
warning with the evicted key is treated as a first occurrence again.

Overflow warnings are caller-owned values produced by a caller-provided
factory. Eviction can emit one overflow warning when no overflow warning has
been emitted in the configured interval. Additional evictions inside the
interval are counted internally but do not repeatedly emit overflow warnings.
The overflow warning re-arms after the monotonic interval elapses.

Default warning key cap is 1024 keys. Default overflow warning interval is one
minute. Policies reject non-positive caps and negative intervals. A zero
overflow interval is allowed only if tests or callers explicitly want every
overflow event to be visible.

### HotStuff Integration Boundary

Plan 0028 returns warnings as data on
`HotStuffGossipDiagnosticsProjectionResult.warnings`; it does not emit logs.
Plan 0029 does not mutate the projected snapshot's embedded warning fields for
status responses. Instead, HotStuff integration provides a cached projection
reader and a caller-owned emission adapter that consumes
`result.warnings`, applies warning dedup, and returns the warnings that should
be emitted by the caller's chosen logging or alerting system.

The default HotStuff warning key is a stable signature derived from
`HotStuffGossipDiagnosticsProjectionWarning`, not the raw warning tuple in all
cases:

- read failures key on `(component, reason)`, where `reason` is
  `read-failure`; the runtime path's stable generic
  `diagnostics-read-failed` marker lives in `message` and is intentionally
  excluded from the key;
- size warnings key on `(component, reason)` because the message contains
  volatile live counts such as `variableEntries` or `estimatedBytes`;
- emergency suppression and projection-failure warnings key on
  `(component, reason, message)` so stable failure/suppression details remain
  distinguishable.

The key extractor must normalize volatile diagnostic measurements before they
enter the dedup key. The warning value emitted by the caller remains unchanged;
only the dedup key is normalized.

The HotStuff cache value is the complete
`HotStuffGossipDiagnosticsProjectionResult`, including legitimate absence,
warnings, projected snapshots, projection drops, and emergency metadata. Tight
polling within the TTL reuses that result rather than repeatedly reading
source/sink diagnostics or re-running projection size checks.

### Test Strategy

Use cats-effect `TestControl` for `IO` TTL, background refresh, and
cancellation tests. Use any injected monotonic clock seam only for small pure
state-machine helpers if that keeps them independent from `IO`.

Cache tests must cover fresh hits, stale refreshes, single-flight concurrent
misses, failure reset, canceled waiters, refresh-fiber cancellation/retry, and
legitimate empty values. Warning dedup tests must cover first emission,
repeated suppression, recency refresh, deterministic LRU eviction, overflow
interval suppression, and overflow re-arm.

HotStuff integration tests must prove tight polling within the TTL does not
repeat underlying projection reads, and that repeated projection result
warnings are deduplicated only for caller-owned emission while the cached
status result still contains its original warnings.

### Phase 1: Generic Cache

- Implement the cache state machine.
- Add deterministic TTL tests using `TestControl` and any injected clock seam
  the generic state machine requires.
- Prove single-flight and cancellation behavior.

### Phase 2: Generic Warning Dedup

- Implement bounded key tracking with recency refresh.
- Implement overflow warning factory/callback support.
- Add deterministic overflow-interval tests using `TestControl` or an injected
  monotonic clock.

## Phase 1 Implementation Notes

Phase 1 added `RuntimeDiagnosticsCachePolicy` and
`RuntimeDiagnosticsCache[F, A]` under
`org.sigilaris.node.jvm.runtime.diagnostics`. The utility is generic over the
cached value type, requires `Temporal[F]`, and keeps the cache state in an
effect-constructed `Ref`.

The cache stores successful refresh values, including `None` or empty values
when the caller's value type permits them. A fresh value is returned only while
the current monotonic time is strictly before the stored expiry deadline. At the
deadline or after it, callers enter the refresh path.

Concurrent misses share one in-flight refresh. The refresh runs in a
cache-owned background fiber so a canceled waiting caller does not cancel the
shared refresh. Refresh failures fail current waiters, clear in-flight state,
and leave any previous stale success unavailable until a later successful
refresh. If the refresh fiber itself is canceled, non-canceled waiters receive a
retry signal and can elect a new refresh.

Phase 1 deliberately does not impose a hard refresh timeout or stale-on-slow
fallback inside the generic cache. Callers that need a bounded diagnostics read
must wrap their refresh effect with their own timeout policy so the generic
utility does not silently change source-specific latency or stale-data
semantics. A refresh effect that deterministically cancels can also cause
waiters to keep retrying; callers own making refresh cancellation a transient
condition rather than a permanent loop.

The Phase 1 focused test command is:

`sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.diagnostics.RuntimeDiagnosticsCacheSuite'`

## Phase 2 Implementation Notes

Phase 2 added `DiagnosticsWarningDedupPolicy`,
`DiagnosticsWarningDedupOverflow[K]`, and
`DiagnosticsWarningDedup[F, W, K]` under
`org.sigilaris.node.jvm.runtime.diagnostics`. The utility is generic over the
warning value and stable key types, requires `Temporal[F]`, and keeps its
bounded key state in an effect-constructed `Ref`.

`observe` returns the warning values the caller should emit. First occurrences
emit the original warning, repeated keys are suppressed, and repeated keys
refresh their recency. `observeAll` preserves input order while applying the
same state machine to a batch of warnings in a single state transition.

When the key cap is full, the least-recently-used key is evicted
deterministically before the new key is recorded. Eviction can append a
caller-owned overflow warning value produced from
`DiagnosticsWarningDedupOverflow[K]`. Overflow emission is re-armed by
monotonic time; evictions inside the interval are counted and reported on the
next emitted overflow warning. An explicit zero overflow interval emits on every
eviction.

The dedup state clamps observed monotonic time to a non-decreasing value before
making overflow re-arm decisions, so concurrent callers that read time before
serializing through the `Ref` cannot move the overflow clock backward.

The Phase 2 focused test command is:

`sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.diagnostics.DiagnosticsWarningDedupSuite'`

## Phase 3 Implementation Notes

Phase 3 added `HotStuffProjectedGossipDiagnosticsPolicies`,
`HotStuffGossipDiagnosticsWarningKey`,
`HotStuffProjectedGossipDiagnosticsRead`, and
`HotStuffProjectedGossipDiagnostics` under
`org.sigilaris.node.jvm.runtime.consensus.hotstuff`. This keeps the generic
cache and warning dedup utilities application-neutral while providing
HotStuff-specific wiring above the existing `Sync`-bounded runtime.

`HotStuffProjectedGossipDiagnostics.current` caches the complete
`HotStuffGossipDiagnosticsProjectionResult`, including legitimate absence,
warnings, projected snapshots, dropped-entry metadata, and emergency
suppression metadata. The wrapper calls the existing
`HotStuffNodeRuntime.currentProjectedGossipDiagnostics` source of truth and does
not change `HotStuffNodeRuntime`'s typeclass constraint.

`warningsToEmit` is the caller-owned emission adapter. It consumes
`HotStuffGossipDiagnosticsProjectionResult.warnings`, applies the generic
dedup utility, and returns only the warnings a caller should log or alert on.
It does not mutate `result.warnings` or `snapshot.warnings`, so status
responses keep the original projection warning data.

The default HotStuff warning key matches the Phase 0 contract:

- read failures key on `(component, reason)`;
- size warnings key on `(component, reason)`;
- emergency suppression, projection failure, and other warnings key on
  `(component, reason, message)`.

The Phase 3 focused test command is:

`sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.consensus.hotstuff.HotStuffRuntimeServiceSuite'`

## Phase 4 Verification And Docs

Phase 4 updated the HotStuff gossip sink retention runbook with guidance for
`HotStuffProjectedGossipDiagnostics`, the default cache TTL, caller-owned
warning emission, warning key normalization, and the boundary that keeps
HTTP/OpenAPI/query DTO ownership outside Sigilaris.

Verification covered:

- `nodeJvm / Test / compile`;
- runtime diagnostics cache and warning dedup suites;
- HotStuff runtime service projected diagnostics integration;
- HotStuff projected diagnostics projection and snapshot suites;
- `nodeJvm / evicted`;
- `git diff --check`;
- application-neutral boundary search for downstream imports.

No Maven Central publish, release tag, or BBGO integration step is part of this
phase; those remain paused by the current pre-deploy scope.

### Phase 3: HotStuff Diagnostics Integration

- Start only after plan 0028 exposes the projected HotStuff gossip diagnostics
  API.
- Wire cache/dedup into projected HotStuff gossip diagnostics from plan 0028.
- Apply warning dedup to `HotStuffGossipDiagnosticsProjectionResult.warnings`
  at a caller-owned emission adapter so runtime assembly can choose logging
  behavior.
- Ensure integration introduces no dependency on downstream application modules.

### Phase 4: Verification And Docs

- Run utility and HotStuff diagnostics tests.
- Update runtime/operator docs.
- Review module dependencies and package visibility.

## Test Plan

- Use cats-effect `TestControl` for deterministic `IO` TTL, sleep, and
  cancellation tests.
- Use an injected monotonic clock only for generic state-machine tests where it
  keeps the utility independent from `IO`.
- Use controlled `Deferred` values to test single-flight and cancellation.
- Use counters to assert refresh call counts under concurrent polling.
- Use synthetic warning keys to assert recency and overflow behavior.
- Run:
  - the module-specific diagnostics utility suites selected by the Phase 0
    package/module decision, for example
    `sbt --error 'nodeJvm / testOnly org.sigilaris.node.jvm.runtime.*Diagnostics*'`
    if the utilities stay under `nodeJvm`;
  - focused HotStuff diagnostics projection/integration tests from plan 0028;
  - existing HotStuff runtime service tests that read in-memory diagnostics.

## Risks And Mitigations

- Risk: cache hides fresh health changes for too long.
  - Mitigation: short default TTL, monotonic-time tests, and caller-configurable
    policy.
- Risk: cache utility typeclass requirements accidentally force all in-memory
  HotStuff source/sink code from `Sync` to `Temporal`.
  - Mitigation: keep the utility generic boundary explicit and decide in Phase
    0 whether only diagnostics assembly/integration needs the stronger
    constraint.
- Risk: failed refresh poisons diagnostics until restart.
  - Mitigation: failed refresh resets cache state and lets the next request
    retry.
- Risk: canceled waiters or refresh-fiber cancellation leave diagnostics
  waiters stuck.
  - Mitigation: canceled waiters detach without canceling the shared refresh;
    refresh-fiber cancellation resets in-flight state so non-canceled waiters
    can retry or elect a new refresh.
- Risk: warning dedup hides important changing failures.
  - Mitigation: warning keys must include meaningful dimensions, and first
    occurrence after key eviction or changed key still emits.
- Risk: warning-key map grows without bound.
  - Mitigation: fixed key cap plus overflow warning.
- Risk: utilities accidentally depend on downstream modules.
  - Mitigation: place utilities in Sigilaris runtime packages and add dependency
    review to acceptance criteria.

## Acceptance Criteria

1. A reusable diagnostics cache exists with TTL, single-flight refresh, failure
   reset, and cancellation-safe waiter handling.
2. A reusable warning dedup helper exists with bounded recency tracking and
   overflow warning support.
3. The cache's minimum cats-effect constraints are explicit, and HotStuff
   integration does not accidentally broaden `Sync` constraints outside the
   diagnostics assembly path.
4. HotStuff projected diagnostics can use these utilities without depending on
   downstream application modules.
5. Utility tests cover cache success/failure/cancellation and warning dedup
   recency/overflow behavior.
6. Runtime docs describe semantics and defaults.
7. Package and module dependency review confirms no reverse dependency on
   downstream application code.

## Checklist

### Phase 0: Utility Contract Lock

- [x] Lock cache success/failure/cancellation semantics.
- [x] Lock warning dedup key, cap, and overflow semantics.
- [x] Decide whether the cache uses `Concurrent[F]` plus `Clock[F]` or
      `Temporal[F]`.
- [x] Decide how HotStuff diagnostics integration meets the stronger cache
      constraint without unnecessarily raising source/sink constraints.
- [x] Lock default TTL/key-cap/overflow-interval values.
- [x] Confirm package location is application-neutral.

### Phase 1: Generic Cache

- [x] Implement diagnostics cache state machine.
- [x] Add deterministic TTL tests with `TestControl` or injected monotonic time.
- [x] Add single-flight concurrent miss tests.
- [x] Add refresh failure reset tests.
- [x] Add canceled-waiter and refresh-owner cancellation retry/election tests.

### Phase 2: Generic Warning Dedup

- [x] Implement bounded recency key tracking.
- [x] Add first-emit and repeated-suppression tests.
- [x] Add recency refresh and deterministic LRU eviction tests.
- [x] Add overflow warning interval and re-arm tests.

### Phase 3: HotStuff Diagnostics Integration

- [x] Confirm plan 0028 projected HotStuff diagnostics API exists.
- [x] Integrate cache with projected HotStuff diagnostics.
- [x] Integrate warning dedup at a caller-owned adapter over projection result
      warnings.
- [x] Keep logging/emission policy caller-owned.
- [x] Verify no downstream module imports were introduced.

### Phase 4: Verification And Docs

- [x] Run utility tests.
- [x] Run HotStuff diagnostics integration tests.
- [x] Update runtime/operator docs.
- [x] Review dependency graph for reverse dependency leaks.

## Follow-Ups

- Consider moving the cache/dedup utilities to a broader runtime-support module
  if multiple non-HotStuff diagnostics surfaces adopt them.
- Consider adding metrics around cache hit/miss and warning suppression counts
  after the basic semantics are stable.
- Consider an ADR if the utilities become a required convention for all
  embedders exposing runtime diagnostics.
