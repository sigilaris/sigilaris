# ADR-0030: HotStuff Transitive Artifact Relay

## Status
Accepted

## Context
- ADR-0016 defines the transport-neutral gossip/session substrate.
- ADR-0017 defines the HotStuff proposal, vote, QC, timeout-vote, and new-view
  artifact family.
- ADR-0018 defines the static peer topology and initial deployment baseline:
  validators are placed in a low-latency same-DC core, and audit nodes are
  read-only participants that can receive, validate, store, and retransmit
  replicated artifacts without voting.
- The previous role-derived relay default let audit nodes relay validated
  HotStuff artifacts, but validators did not relay received artifacts by
  default.
- That default made sparse audit reachability depend on direct validator to
  audit fanout. An audit node connected to only one validator could miss
  proposal chains from non-neighbor validators unless the topology also gave it
  direct sessions to every producer or a separate backfill path caught it up.
- Proposal finalization and finalized tx range handoff are local observations.
  An audit node can only expose those observations for artifacts it has
  received and validated.

## Decision
1. **Validated HotStuff consensus artifacts relay transitively by default.**
   - `HotStuffRelayPolicy.default` enables relay.
   - `HotStuffRelayPolicy.forRole` returns the same production default for
     validators and audit nodes.
   - `HotStuffRelayPolicy.testLocalOnly` names the explicit local-only profile
     for tests and narrow harnesses.

2. **The relay switch covers only HotStuff consensus artifact topics.**
   - Covered topics are consensus proposal, vote, timeout-vote, and new-view.
   - Application gossip topics remain out of scope. They keep their existing
     topic-specific source/sink semantics unless a separate application-topic
     relay policy is added.

3. **Relay is validation-gated and duplicate-gated.**
   - A node only republishes an artifact after the sink accepts it as a newly
     applied, validated artifact.
   - Exact duplicates and same-window conflicts are not republished.
   - Rejected artifacts remain local diagnostics and are not inserted into the
     relay source.

4. **In-memory relay sources are bounded per chain/topic.**
   - The in-memory HotStuff source keeps a positive `retainedEventsPerTopic`
     cap.
   - When retention prunes older events, stale cursors return `cursorPruned`
     instead of implying infinite history.
   - A node that is offline past retention still needs bootstrap discovery,
     snapshot sync, forward catch-up, or historical backfill.

5. **Relay behavior is observable without payload bodies.**
   - Source diagnostics expose retention, read-by-id misses, stale cursor
     rejections, appended counts, and pruned counts by chain/topic.
   - Sink diagnostics expose policy mode, successful relay counts by topic,
     duplicate suppression counts by topic, and rejected non-relay counts by
     topic and reason.
   - Diagnostics do not expose application payload bodies by default.

6. **ADR-0018 remains the production topology baseline.**
   - Validator relay is additive audit reachability; it does not replace the
     same-DC validator core baseline.
   - Sparse validator chains are useful test harnesses for transitive behavior,
     not a recommended production topology.
   - Audit observation latency is expected to scale with relay hop count and
     peer polling cadence.

## Consequences
- Audit nodes no longer require direct sessions to every validator to observe
  live consensus artifacts while artifacts remain retained and at least one
  live path connects the audit node to the validator component.
- Direct audit fanout remains valid, but it is no longer the only live
  propagation mechanism.
- Operators must size retention and catch-up/backfill paths for offline or
  lagging audit nodes. Transitive relay is a live propagation contract, not an
  unbounded history service.
- Duplicate cycles converge because each sink relays only newly applied
  artifacts.
- Relay through additional hops can increase time to audit finalization and
  finalized tx range observations.

## Rejected Alternatives
1. **Keep validator relay disabled by default**
   - This preserves old direct-audit-fanout assumptions but leaves sparse audit
     reachability dependent on every producer having a direct audit session.

2. **Relay every received artifact before validation**
   - This would spread malformed or conflicting artifacts and bypass the sink
     checks that own HotStuff safety semantics.

3. **Make application gossip topics transitively relayed by the same switch**
   - Application topics have application-owned payload, retention, admission,
     and catch-up semantics. They need a separate policy decision.

4. **Treat in-memory relay as infinite history**
   - Unbounded source vectors are not an acceptable production default. Stale
     nodes must use bootstrap/catch-up/backfill after retention is exceeded.

## Follow-Up
- Add an application-topic relay policy only if embedders need transitive
  behavior for non-consensus gossip topics.
- Consider persistent relay diagnostics if operators need post-restart relay
  accounting.
- Consider validator-neighbor-aware relay suppression if production diagnostics
  show redundant bandwidth is material.

## References
- [ADR-0016: Multiplexed Gossip Session Sync Substrate](0016-multiplexed-gossip-session-sync.md)
- [ADR-0017: HotStuff Consensus Without Threshold Signatures](0017-hotstuff-consensus-without-threshold-signatures.md)
- [ADR-0018: Static Peer Topology And Initial HotStuff Deployment Baseline](0018-static-peer-topology-and-initial-hotstuff-deployment-baseline.md)
- [ADR-0028: HotStuff Finalization Observability And Embedder Failure Semantics](0028-hotstuff-finalization-observability-and-embedder-failure-semantics.md)
- [0022 - HotStuff Transitive Artifact Relay Plan](../plans/0022-hotstuff-transitive-artifact-relay-plan.md)
