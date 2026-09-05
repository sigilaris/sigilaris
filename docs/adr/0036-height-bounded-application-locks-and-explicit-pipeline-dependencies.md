# ADR-0036: Height-bounded application locks and explicit pipeline dependencies

## Status

Accepted as the v0.3.0-M1 target contract. The current implementation does not yet expose this wire profile.

## Date

2026-08-28

## Context

ADR-0035 introduced application execution lanes and described durable input locks plus recovery artifacts. Its proposed profile left lock lifetime
unbounded and required protocol recovery certificates, recovery frontiers, and cross-epoch handoff rules. That is a large surface for the first
certified-fast deployment.

ADR-0031 and ADR-0032 also make finalized or certified ancestor state the ordinary boundary for dependent transactions. That default is safe, but
an embedding application with a two-stage latency objective may not be able to wait for separate producer finalization before admitting its
consumer. The protocol therefore needs one narrow dependency mechanism without opening arbitrary speculative DAG execution or importing an
application's transaction families and value semantics into Sigilaris.

This ADR supersedes the lock lifetime/recovery and unconditional pre-final-consumption clauses of ADR-0035. It narrows, rather than expands, the
general execution model.

## Decision

### 1. Every lock-capable application transaction has a signed inclusion deadline

The application transaction signature commits to:

```text
lastInclusionHeight: BlockHeight
```

`InputLockVote`, `InputLockCertificate`, `FastEffectVote`, and `CertifiedEffects` commit to the same value directly or through a unique signed
transaction digest. The deadline must therefore be visible and identical at admission, voting, certification, proposal validation, recovery, and
replay.

The committed protocol configuration contains `maxLockLifetimeBlocks`. Admission checks:

```text
base.height < lastInclusionHeight
lastInclusionHeight <= base.height + maxLockLifetimeBlocks
```

An execution may first be applied only in a candidate whose application height is at most `lastInclusionHeight`. If a valid candidate includes the
execution by that height, the containing block may finalize later. The field is a last-inclusion height, not a finality deadline.

### 2. Finalized canonical progress is the only expiry clock

A live lock is released as `expiredUnapplied` only when both conditions hold:

1. finalized canonical height is greater than `lastInclusionHeight`; and
2. the authenticated application `AppliedExecutionIndex` proves that the execution was not applied by its deadline.

If finality stalls, expiry also stalls. Wall-clock TTLs, local timers, PAC-based early release, timeout certificates, and leader assertions are not
authoritative. A validator must not unlock an input merely because it has not observed a proposal for some duration.

An expired execution cannot be revived. The client creates a new transaction id by signing new bytes with a new deadline. Retransmission before
expiry uses the exact same bytes and is idempotent.

### 3. Epoch and protocol changes drain the lock domain to empty

M1 does not carry or hand off locks and proposal reservations across epochs/configuration versions. Maintenance activation performs this sequence:

1. stop new lock votes, certificates, and proposal reservations;
2. continue finalization past the greatest admitted deadline, bounded by `maxLockLifetimeBlocks`;
3. resolve every entry as applied or finalized-height `expiredUnapplied`;
4. require zero live application locks and zero live proposal reservations;
5. rotate epoch/configuration and reopen admission.

A finality incident blocks this maintenance operation rather than authorizing unsafe unlock. Implementations may retain recovery evidence for audit,
but M1 safety does not depend on PAC, recovery-frontier, or cross-epoch carry artifacts.

### 4. Pre-final dependencies are denied by default

An ordinary transaction may consume only state available at its declared certified/finalized ancestor according to ADR-0031 and ADR-0032. It may
not discover a freshly created output in a proposal and bind to it dynamically. `Latest` references, implicit same-block lookup, arbitrary DAGs,
and cross-pipeline consumption remain invalid.

### 5. An application-registered profile may declare one exact pipeline dependency

An embedding application's activated protocol manifest may register a versioned two-stage dependency profile. Sigilaris treats the application
family ids, reference bytes, and result bytes as opaque values interpreted by the registered application codec and reducer. Sigilaris itself does
not contain a list of application transaction types or depend on the embedding application's protocol documents.

The whole pipeline is submitted once. Its signed plan commits to a stable `pipelineId`, dependency profile id/version, ordered stage transaction
ids, and the exact producer output name/reference. Every stage transaction also signs the same `lastInclusionHeight`; a missing or mismatched value
rejects the whole pipeline at admission. The exact output reference must be deterministic from signed producer bytes under the registered profile,
and the consumer signs that reference before submission. No validator may replace it with the producer's latest output.

Only the declared consumer in the same pipeline may consume this pre-final output. A transaction outside the plan cannot observe or consume it,
and the output cannot be reused by a second pipeline. The M1 registered profile shape has exactly one `Producer -> Consumer` edge.

Deployments expose one of the activated execution modes:

- `OrderedAtomic`: execute the two transactions consecutively in one ordered batch and include or reject the pipeline atomically.
- `CertifiedAncestor`: certify/include the producer and allow the exact consumer in any descendant block that proves the producer ancestor under
  ADR-0031's branch rules. Adjacent placement is the preferred latency profile, not a validity condition.

The consumer's finalization is the pipeline's finalized boundary. Proposed, lock-certified, effect-certified, or block-included states are progress
states only; the embedding application owns any additional business-success interpretation. Retries use the same signed pipeline bytes. After
deadline expiry the client must construct and sign a new pipeline.

Adding another pair, more stages, branching, or a cross-lane dependency requires a new activated profile and conformance proof. M1 does not expose a
general programmable dependency graph.

### 6. Proposal reservations use the same deadline domain

A proposal reservation derived from a bounded pipeline cannot outlive the pipeline by local policy. Once created, it is released only on canonical
application or finalized-height expiry after the `AppliedExecutionIndex` check. A locally rejected, abandoned, or rolled-back proposal is not an
authoritative early-release signal; its reservation remains bounded by the common signed deadline. Admission rejection creates no reservation.
The drain-to-empty rule covers both application input locks and these reservations.

## Safety argument

Two honest validators cannot derive different expiry results from elapsed wall time because elapsed time is not an input. They use the same signed
deadline, finalized canonical height, and authenticated applied index. A transaction included before the deadline remains identifiable as applied
even if finality arrives later, so expiry cannot authorize a conflicting replay.

The pipeline exception does not allow dynamic dependency discovery. Producer bytes determine the exact output under an application-registered
profile, the consumer signs it, the complete ordered plan is available before execution, and scope is limited to that declared pipeline.
`OrderedAtomic` never exposes a partial committed pipeline; `CertifiedAncestor` uses the existing certified-ancestor proof boundary.

## Consequences

- Lock recovery is substantially smaller and has one consensus clock.
- Lock availability depends on finality; a finality halt can keep inputs unavailable.
- Epoch/configuration activation may require bounded write downtime.
- A registered two-stage application pipeline can avoid two independent finalization waits without admitting arbitrary speculative chaining.
- Wire schemas, signature domains, hashes, golden vectors, and persistent records must include the deadline and pipeline profile fields before
  activation.

## Rejected alternatives

### Wall-clock lock TTL

Rejected because validators can observe different elapsed times and release conflicting locks.

### PAC/recovery-frontier based early unlock

Rejected for M1 because it adds another certificate and state machine when finalized height plus an applied index is sufficient.

### Unbounded locks with cross-epoch handoff

Rejected for M1 because it makes configuration changes and recovery depend on durable artifact transfer. Maintenance drain-to-empty is simpler.

### Require producer finalization before every consumer

Rejected for a registered two-stage low-latency profile because it defeats that profile's latency objective. It remains the default for
unregistered dependencies.

### General same-block transaction DAG

Rejected because it introduces scheduling, authorization, rollback, and denial-of-service questions beyond the single required flow.

## Activation requirements

Before activation, conformance tests must cover at least:

- deadline field canonical encoding and signature/hash binding in every vote/certificate form;
- an activation manifest with the deployment's initial `maxLockLifetimeBlocks`, plus a drain-to-empty runbook for every later change;
- rejection above `lastInclusionHeight` and acceptance at exactly the bound;
- inclusion by the bound followed by later finalization without erroneous expiry;
- no expiry while finality is stalled;
- applied-index-guarded `expiredUnapplied` release and same-bytes idempotent retry;
- drain-to-empty epoch/configuration rotation;
- profile-registry isolation, exact producer-output derivation, a common deadline across every stage, same-pipeline scope, ordered-atomic rollback,
  and certified-ancestor mode including non-adjacent proven descendants;
- rejection of outside consumers, implicit `Latest`, undeclared chains, and expired pipeline revival.

## Related ADRs

- [ADR-0031: Certified-Ancestor Dependent Transaction Pipelining](0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](0032-stage-based-transaction-pipeline-api.md)
- [ADR-0035: Application Execution Lanes, Ordered Waves, and Certified-Fast Admission](0035-application-execution-lanes-ordered-waves-and-certified-fast-admission.md)
- [Implementation Plan 0031: v0.3.0-M1 Height-Bounded Locks And Exact Pipeline Release](../plans/0031-height-bounded-application-locks-and-exact-pipeline-dependencies-plan.md)
