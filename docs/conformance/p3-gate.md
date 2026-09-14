# Plan 0033 Phase 3 gate

Implemented on `2026-09-11` after Phase 2 commit `16ba041`. This is source-build evidence for the unpublished
`0.3.0-M3-SNAPSHOT` candidate. Independent Maven artifact execution remains Phase 6; deployment activation remains separate.

## Implementation

The additive exactV3 models retain immutable signed two-stage work, acyclic execution identity, common deadline, stage/output ownership and
canonical idempotency aliases in the same authoritative journal as safety claims. Both OrderedAtomic and CertifiedAncestor execute consensus
sources for eligible lock masks 00/01/10/11 without an effect quorum. Actual lock certificates remain mandatory precisely for nonempty subsets.
The separate fast-source path retains its full certificate requirements.

Private candidate verification authenticates the actual signed work, source/declaration/artifact bytes, ordered working-state accesses and
profile results. Deterministic rejected reducers retain complete bounded reservations and grant no signing readiness. Multiple independent
pipelines in one proposal must each validate successfully before its ordinary durable consensus vote can be signed; recovery clears readiness.

Historical ancestry verifies real QCs, retained/backfilled parent linkage, historical profiles, actual producer source/output and current local
parent approval. Canonical materialization independently verifies actual finality, original payload/plan and exact profile outcomes. Its journal
decision atomically updates exact lifecycle with canonical state, results, applied index and owner termination. Same-domain nonapplication
strictly above the deadline also expires admitted ownerless stages, retaining evidence that keeps late certificates terminal.

Public integration and executable boundaries are documented in [exact integration](v2-exact-integration.md),
[exact evidence](p3-exact-evidence.md), [exact schema](v2-exact-schema.md) and [application storage](v2-application-storage.md).

## Review iterations

The root implementation review and independent core/ancestry, public authentication and safety/application reviews corrected the following
findings before their final re-review:

1. Exact candidate preparation was separated from actual-finality application preparation. Original source ownership is checked against the
   complete recovering state, avoiding both a recursive safety-gate callback and a stripped-state bypass. Generic voting cannot omit a
   registered source's exact binding, even after its original execution is terminal.
2. Idempotency aliases are retained as authenticated historical operations, including aliases before subsequent lifecycle updates. Admission
   and recovery reject private authentication capabilities returned for different signed work. Signed lifetime and literal fields are checked
   independently of lock presence; invalid artifact hints cannot be accepted merely because a repository returns a valid certificate.
3. Readiness is tracked for each exact execution in the full consensus intent. A successful independent pipeline cannot authorize another
   failed pipeline in the same proposal, and neither retained intents nor reserved lifecycle tags grant readiness after restart.
4. Canonical application now runs the same captured exact profile as admission and candidate validation. Genuine three-chain finality cannot
   promote a deterministic failed producer or consumer result into canonical exact state. A prewrite authentication failure conservatively
   fences the store until successful recovery of the unchanged durable state.
5. An ownerless expired stage's terminal resolution is resolved from its actual committed expiry operation. A later valid lock certificate
   can be archived only as terminal, preventing resurrection even with a stale publication-height callback. Equivalent quorum signer sets
   preserve the original certificate reference while still requiring equality of the complete signed subject and deadline.

After these corrections, the independent public and safety reviews reported **No finding**; the root review of the integrated candidate,
ownership, recovery and application paths also reported **No finding**. The core/ancestry implementation was independently reviewed and its
additional finalized-tip regression passed. These findings and fixes are included in this phase commit.

## Validation

The shared `ExactV3ModelSuite` passed **12 JVM and 12 Scala.js cases** at 15:26:36 and 15:27:23 KST. It checks exact schema/codec identity,
literal canonical bytes, signing/digest vectors, ownership, optional certificate presence and explicit lifecycle transitions.

At **15:50:50 KST**, the following source gate passed **81 tests, zero failures**:

```text
sbt -J-Xmx4G scalafmtAll scalafmtSbt \
  'nodeJvm/testOnly *V2PublicExact*Suite *V2ExactApplicationConformanceSuite *V2CanonicalAncestorSuite *ExactOwnershipBindingSuite *ExactSafetyJournalReductionSuite *V2RecoverableApplicationSuite *V2PublicVotingConformanceSuite *V2PublicRequestConformanceSuite' \
  scalafmtCheckAll scalafmtSbtCheck
```

The total comprises public exact candidates/boundaries/mixed proposals **18**, public canonical exact application/expiry/fault recovery **6**,
ancestry **10**, exact journal reduction **12**, ownership **3**, existing recoverable application **21**, voting **10**, and request validation **1**.
The ancestry gate explicitly verifies a producer at height 6 after an actual three-chain proof finalizes height 7, using archive backfill and
restarted voters. The public application suite includes real file-journal Prepared/Committed interruptions and ownerless late-certificate expiry.
The independent four-key ancestry fixture is not the four-runtime transport gate required in Phase 4.

The existing ordered read/write voting regression also passed **1 case** at **15:52:01 KST** using
`sbt -J-Xmx4G 'nodeJvm/testOnly *V2PublicOrderedVotingSuite'`. Formatting, build formatting and whitespace checks passed.
Phase 2's full node suite already passed 776 cases; the final five-target full source run remains Phase 6.

## Lessons applied to remaining phases

- P4 must route both explicit and automatic HotStuff signing through durable voting and every exact execution's readiness gate. Header assembly
  alone cannot activate this protection. Empty proposals still need durable consensus anti-equivocation intents.
- P4 finalization/replay must use the same original source, complete normalized result and captured exact profile as voting. Keep certified,
  finalized and canonically materialized states distinct, including nodes that never voted and historical producers below the finalized tip.
- P4/P5 evidence/preimage storage shares the signing failure fence. A successful raw blob write is not a substitute for the runtime's durable
  publication boundary, and recovery callbacks must use the original supplied journal without recursively acquiring its live gate.
- P5 migration inventories include all alias history, permanent stage/output ownership, ownerless terminal resolutions and the original
  application evidence. Neither missing live owners nor a latest-record lifecycle snapshot permits deleting these records or reusing sources.
- P6 must run the exported exact, mixed-pipeline and canonical application consumers from staged artifacts; module test wrappers alone are
  insufficient. Record actual transport and source/artifact evidence separately and retain historical M1/M2 identities.
