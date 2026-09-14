# ADR-0037: Versioned Application Execution Upgrade And Empty Blocks

## Status

Accepted — contract frozen by [plan 0033 Phase 0](../conformance/p0-gate.md) on 2026-09-11; implementation and candidate conformance completed in [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md). Public release and operational activation require their separate gates.
Supplement to ADR-0035 and ADR-0036 for a release after `0.3.0-M2`.
This document does not activate a protocol or change the published M1/M2 contracts.

## Date

2026-09-08

## Context

ADR-0035 already requires independent full-input, lock-subset and footprint commitments, consensus admission with no lock certificate when the
derived lock subset is empty, and durable vote recording before a validator returns a vote. ADR-0036 already permits a registered consumer in any
proven descendant within its inclusion deadline. Completing those requirements is implementation conformance work, tracked in plan 0033.

The published M2 substrate still needs that work and V2 runtime integration. Its automatic proposal input constructs a V1 header, and its
execution-plan validator rejects both an empty plan and an empty wave. A quiescent application therefore cannot supply a valid empty V2 descendant
through that path. Empty descendants are needed for ordinary finalization and for maintenance that must advance finalized height beyond admitted
deadlines without accepting new application work.

The corrections also affect already published input commitments, reservations, manifests and recovery records. Historical bytes cannot acquire
different meanings merely because a newer binary implements more of the target ADR. Two decisions need a lasting contract: the meaning of an empty
V2 block, and the compatibility and activation boundary for the corrected substrate.

## Relationship To Existing Decisions

- ADR-0035 remains authoritative for lanes, Exact inputs, complete footprints, wave execution, historical-set certification and reciprocal interlock.
- ADR-0036 remains authoritative for signed deadlines, finalized-height expiry, drain-to-empty and the single exact dependency edge.
- An empty lock subset remains `ConsensusAdmission` with `inputLockCertificateId = None`. Certified-fast admission still requires a non-empty lock
  subset. This ADR introduces neither an empty-vector lock certificate nor lock-free certified-fast execution.
- This ADR adds an explicitly activated empty-plan rule and a post-M2 upgrade policy. It does not retroactively relax M2 plan validation or rewrite
  its signatures, commitments, block identities or journal entries.
- Concrete API names, numeric versions, tags and canonical vectors are frozen in plan 0033 before implementation of the affected formats.

## Decision

### 1. An ordinary empty V2 block has one canonical execution plan

The new execution-plan profile permits `waves = []` exactly when the block has no executable application members. This is the sole empty-plan
representation. A plan containing any wave still requires every wave to contain at least one entry; empty ordered or conflict-free waves remain
invalid. Existing membership, uniqueness, ordering and result-correspondence checks continue to apply to non-empty plans.

The header carries `Some(executionPlanRoot)` computed from the normal canonical encoding of the explicitly selected new plan version and its empty
vector. An absent root, a zero-hash sentinel, an invented execution id or a synthetic transaction used only to pad the plan is not an alternative.
The complete versioned plan preimage must be available and verified before a proposal vote.

For an ordinary empty block:

- the body contains no application execution/result/effect/receipt records, and `bodyRoot` commits the canonical empty application body;
- the block performs no application transition, so its application `stateRoot` equals its authenticated parent's application state root;
- parent, height, timestamp and consensus certificates may advance normally;
- canonical finality observations and validator safety-journal expiry may advance without inventing an application execution or changing that
  block's application state root.

A checkpoint, scheduled action, configuration-opening transition or migration that changes application state is an execution. It must use an
explicit application transaction under an authenticated family manifest and a corresponding body result in a non-empty plan. Existing
`ConsensusTransaction` and `CertifiedFastExecution` sources retain their normal eligibility rules; this ADR adds no system source kind or implicit
transaction. Pure recovery/materialization of a previously committed result does not create a new transition in the empty block.

For the application opening transition in this upgrade profile, use a signed application transaction as a `ConsensusTransaction` source with a
`Compatibility(reasonDigest)` declaration in the block's sole `CompatibilitySingleton` wave. The application manifest defines its transaction
codec, maintenance authorization, deterministic classification reason and conversion rules. Phase 0 fixes those integration hooks and the neutral
conformance fixture before implementation; activation configuration alone does not authorize an unsigned state conversion.

The opening envelope must sign `lastInclusionHeight` even when its lock subset is empty. Admission binds an authenticated base and checks
`base.height < lastInclusionHeight <= base.height + maxLockLifetimeBlocks` with checked arithmetic; every opening reservation commits that same
deadline. Phase 0 freezes the base proof: normally an authenticated finalized base, or an explicitly authorized initial anchor `G` for a lock-free
bootstrap opening. `G` is not a finalized proof and cannot relax the ordinary finalized-base/certificate rules for a non-empty lock subset.
The opening in `G`'s first ordinary descendant must derive an empty lock subset and use `inputLockCertificateId = None`; lock-bearing bootstrap
opening is unsupported. Existing-chain handover openings retain the ordinary finalized-base and lock-certificate requirements.
Application is allowed at the inclusive deadline. Expiry requires actual finalized canonical height in that same domain strictly above it and
authenticated nonapplication evidence. Rejection, abandonment, timeout or activation rollback alone never releases the reservation. If required
opening execution cannot complete and no boundary-compatible progress/recovery path is verified, retain the claim and stop activation; empty
blocks cannot silently skip a mandatory first opening to force its expiry.

The opening transaction obeys ADR-0035's ordinary input, lock-certificate, complete actual `AccessLog` and durable reservation rules. A migration
that discovers existing mutations through a scan must prove that their resolved authority is consensus-only. Every lock-eligible existing
mutation must instead be an explicit Exact input covered by its pre-proposal lock certificate; compatibility execution cannot invent an exemption.
An empty derived lock subset uses `inputLockCertificateId = None`. No other application entry may share the opening block, and its state change
must be reproduced by the signed transaction's result before proposal voting. Preparing conversion output offline does not make it canonical.

The new empty-plan validity rule requires a new execution-plan version. Historical plan version 1 retains its published rejection behavior.
Block-header V2 may retain its byte layout and hash domain if neither changes: the committed root and authenticated protocol schedule distinguish
the plan profiles. A header-format change, if independently required, must receive its own version.

### 2. Published formats retain their historical meaning

Artifact milestone names and protocol/schema versions are different identities. A new Maven release alone does not authorize a new interpretation
of an existing protocol version.

Changes to canonical preimages, field roles, lock eligibility, footprint/reservation semantics, signed subjects or persistent recovery semantics
must have explicit new protocol/schema identities wherever the affected contract differs. The activation manifest commits the compatible tuple of
plan, input, lock, effect, exact-pipeline and journal formats plus application verifier manifests. Unchanged formats may retain their versions only
with byte and semantic equivalence evidence; fields must not be silently repurposed.

Historical verification selects decoders and validators by the artifact's recorded version and authenticated historical configuration. It preserves
old signatures, ids and digests. Unsupported versions, missing required preimages and inconsistent version combinations fail closed. New work uses
only the activated profile; readable history is not permission to submit new work under a retired profile.

Original M1/M2 journal and terminal pipeline artifacts remain immutable evidence. New derived indexes may be reconstructed from verified historical
records, but migration must not invent a missing full footprint, pre-sign vote, certificate or application result. Generic pipeline records keep
their historical semantics and do not become exact work by inference. Newly detected invalid historical data requires an explicit diagnostic and
recovery decision rather than normalization into a supposedly valid record.

### 3. Activation is an authenticated range of protocol configurations

An activation binds its source checkpoint and applicable drain evidence, the authenticated continuation parent selected for the first ordinary
block under the new profile, that block's height, source and new manifest digests, and the required format tuple. Every validator must derive the
same rule selection from the authenticated deployment/canonical configuration. A node-local boolean, the newest installed decoder or the mere
presence of a V2 root cannot select the protocol.

The schedule must cover both historical V1-to-V2 activation and a later transition between two application profiles that both use header V2.
Proposal construction, proposal validation, block application, historical replay and snapshot/bootstrap verification use that same schedule. A
single header-version threshold is insufficient for the latter transition.

Classify the selected source lineage separately from every other recorded consensus domain:

- HotStuff history in the lineage being continued requires the consensus-continuity handover below.
- A source with no prior HotStuff ancestry and no other prior domain can use initial bootstrap.
- A source with no prior HotStuff ancestry may also use initial bootstrap when older HotStuff domains are proven retired non-ancestors and satisfy
  the preservation and fencing requirements below. This is a distinct eligibility case, not a suffix-free reset of those older domains.

Uncertain source provenance, ancestry or domain retirement makes automated activation unavailable until verified recovery establishes the facts.

#### Continuing HotStuff ancestry: preserve consensus continuity

Application drain does not remove HotStuff's certified but unfinalized suffix. Finalizing height `h` normally requires evidence from descendants
at `h + 1` and `h + 2`; choosing the next finalized height as the new boundary would retroactively change the rules of already signed blocks.
The transition must preserve the selected certified branch, highQC/lockedQC state, pending proposal/QC evidence and voter watermarks. Their
application profile remains the one authenticated when they were issued, including when an old block's finality proof spans the transition.

Before validators can sign new-profile work, an authenticated handover must fix a future boundary compatible with the retained old suffix and
fence old-profile voting at or beyond that boundary. The handover must prove that no conflicting old-profile quorum can still form there; it
cannot rely only on one node's observed tip or empty application store. It must also provide a valid continuation/justify path from the retained
consensus lock into the new range. If those facts cannot be established, activation is unavailable until a separately specified compatible
handover is verified. Merely deleting speculative blocks, resetting consensus state or waiting for a suffix-free finalized tip is not a handover.

The finalized checkpoint `F` proves drain/expiry progress; the continuation parent `P` supplies the first new block's execution base. They need not
be the same block, and `P` can still be certified rather than finalized. Verify and replay the retained old suffix from `F` to `P` under its
historical profiles, including any old system/checkpoint transitions, to obtain `P`'s authenticated application state root. Migration or opening
execution starts from that root, not an unreconciled snapshot at `F`. A parent change invalidates its prepared opening output and requires fresh
verification; failure to establish the parent state stops activation rather than omitting suffix writes.

#### Eligible initial bootstrap: authenticate a new chain's inherited state

This upgrade profile supports initial V2 bootstrap from a source lineage with no HotStuff ancestry, including one selected after an authenticated
rollback that retired a different, non-ancestor HotStuff domain. Before first initialization, the target consensus and validator-artifact signing
domains must be unused; subsequent recovery resumes the already bound initialization decision. Plan 0033 must implement and verify this path.
Existing bootstrap APIs, an empty directory or a newly chosen chain id alone do not establish eligibility.

When retired domains exist, retain the prior rollback/source-selection decision and verifiable source provenance showing that their blocks are
not ancestors of the selected canonical single-node lineage. Preserve all available data roots, blocks/QCs, consensus locks, signer watermarks
and fence records read-only with an authenticated inventory that distinguishes present content from the narrowly permitted prior absence below.
Enforce continuing fences on their signing/write authority, preserve all remaining anti-equivocation history and prohibit reactivating or importing
archived roots as current consensus state. The new bootstrap must not invent
a rollback decision or reclassify the source's continuing canonical history as discarded evidence. A related or unclassified domain requires the
continuity/recovery route. Decision 4 separately governs every relevant application-safety domain; retirement is not application-claim expiry.

Raw archives of a retired non-ancestor domain may have become unavailable during an earlier rollback/reset, before this transition's evidence
baseline. That case is supported only when independent recorded rollback/source-selection evidence and deployment/key/data-root inventories
establish source provenance and non-ancestry, application voting in every affected missing-history domain is proven never-enabled, all surviving
evidence is preserved, and no-resume signing/write fences remain enforceable. The source snapshot and its reachable state must still be complete;
neither continuing-chain data loss nor unknown application-voting history qualifies through this route.

The locally selected bootstrap authority authenticates a scoped archive-absence attestation. It identifies the affected domains, missing data and
known absence circumstances/timing, recorded unknowns, surviving evidence and the independent eligibility evidence. Bind it to the source snapshot
and fresh target domain through the initial genesis bundle and durable activation decision before any new signature. A present archive has a
verified content digest; an absent archive has an attestation digest with a distinct evidence kind. The attestation does not supply missing raw
hashes, ancestry proofs, signer watermarks or never-enabled/drain evidence, and a bare assertion of absence is insufficient.

This exception does not authorize deleting data for this upgrade. Freeze the archive evidence baseline before preparation: later loss of content
recorded as present, or loss of the bound attestation/evidence/fences, remains a failure. A replacement attestation, new baseline or restarted
bootstrap cannot reclassify such loss as historical absence. Missing old signer state is never reconstructed as unused or resumed in its old domain.

Select a locally trusted, authenticated genesis bundle following ADR-0023's trust-root precedence. It binds the source deployment/chain identity,
canonical source snapshot/checkpoint and its application state root, source schema and snapshot data/provenance digest, new chain identity,
initial ordered validator set, genesis/initial-window parameters and activated protocol/format/application manifests. The source's canonical status is verified under its
own historical rules; it is not represented as a HotStuff finalized proof. Peer suggestions and a validator-set trust root alone cannot authorize
an arbitrary inherited state root. A public source-snapshot/provenance verifier and initial-state installation boundary must enforce that binding,
including hash verification and complete reachable state-data closure under the source schema.

The new chain uses a fresh chain identity and domain separation for validator-signed consensus messages and application artifacts, including
lock/effect votes and certificates. Historical validator artifacts cannot authorize new work. The embedder owns user/application-transaction
signature domains and replay policy; this rule does not itself require changing a client `networkId` or re-signing its transactions. The activated
application manifest and replay records determine their admissibility, and the opening payload still signs its authorization and activation binding.
Preserve source history, application replay records and the source-to-genesis linkage for audit and historical replay. Source checkpoint height
and new consensus height are distinct coordinates; new-chain finality cannot expire old-domain claims. Fence the old write authority before the
source snapshot is selected, and keep it fenced throughout bootstrap so that the inherited state cannot diverge from a still-writing source.

The authenticated initial anchor `G` installs the verified source application root unchanged as the new chain's initial condition. This is explicit
chain birth, not an ordinary empty descendant or an uncommitted conversion of application state. Any application conversion still executes through
Decision 1's signed compatibility-singleton opening transaction in the first ordinary V2 block, whose continuation parent is `G`. Freeze the
genesis/header/height/initial-justify and QC rules in Phase 0; do not fabricate child/grandchild proofs, silently weaken QC verification or treat a
single-node commit as HotStuff finality. Durably bind the selected bundle, verified installed state and initialized consensus/safety state before
issuing a bootstrap-QC signature, proposal or vote. Restart resumes that same decision and preserves all bootstrap signatures, fences and subsequent
consensus safety history under Decision 5; it cannot choose a different initial root after publishing a promise.

For both paths, the activation marker and any application opening transaction defined in Decision 1 bind the known parent/height boundary and input
manifest. They must not require the resulting block's own id inside a state or plan preimage that contributes to that id. The resulting block id
and roots can be verified and recorded after construction.

At and after the selected boundary, missing plan preimages, wrong roots, inactive plan versions and incompatible manifests reject the candidate
before voting or application. In an existing-chain handover, blocks below the boundary use their historical profile. In initial bootstrap, `G`
instead uses the authenticated bundle's genesis profile; it is not a source-history block relabeled by height. Imported history keeps its original
chain/configuration context, and its heights are not a pre-activation range of the new chain. Mixed-profile admission or partial per-namespace
activation is invalid.

### 4. Upgrade requires a provably empty old safety domain

Inventory source, target and retired domains separately. A non-empty read-only retired consensus archive is not a live application reservation
and does not fail the zero-live barrier merely by existing. Every relevant application-safety domain still requires its own drain or proven
never-enabled evidence. Archival classification, a domain fence or new-chain finality cannot mark an unresolved old lock/reservation/exact claim
expired. If a stopped domain cannot satisfy its original rules, activation requires a separately verified recovery contract or remains unavailable.

Maintenance closes old-profile application admission and vote issuance while retaining ordinary consensus finalization. Existing applicable work
may finish through its historical valid paths; unapplied bounded work expires only through the authenticated finalized-height rule. No old live
lock or reservation is carried into the new profile.

For a profile that durably records all votes before returning them, the authoritative greatest admitted deadline and zero live locks, reservations
and nonterminal exact pipelines provide the usual drain evidence after reconciliation.

M2's vote-validation APIs do not durably record the vote subject before returning a validation result to the caller. In particular, signing after
generic pre-certificate `validateLockVote` can leave both the subject and its deadline absent from the journal. Generic `validateEffectVote` instead
requires a recorded live lock with the same deadline: its missing vote-subject record does not by itself imply an unrecorded expiry horizon.

Exact transport/registration journals the verified pipeline descriptor and deadline watermark. `ExactPipelineAdmissionService` alone writes the
admission store; `ExactPipelineTransportService` and runtime registration supply the safety-journal boundary, and startup reconciliation closes
admission-store/journal interruptions. Exact effect validation binds the execution, dependency-plan digest and deadline to that journaled plan.
The generic lock-vote API does not enforce the corresponding exact-plan binding, so an "exact-only" label alone is insufficient evidence.

A deployment may use the reconciled journal's greatest deadline without an additional inferred horizon if retained integration and deployment
evidence proves that every relevant old signed subject was bounded by durable records before issuance, including exact lock execution, domain and
deadline binding, with no unrecorded bypass or lost history. Close and fence issuance across the relevant historical signer/quorum scope, reconcile
admission and journal state, and finalize strictly beyond that recorded horizon before verifying zero live state. This establishes deadline
coverage only; it does not repair missing vote-subject history or satisfy the new profile's durable pre-sign anti-equivocation requirement.

Where complete deadline coverage cannot be proved, including generic pre-certificate lock issuance, an empty physical store or a `Ready` flag alone
is insufficient. The upgrade must additionally:

1. establish and enforce an authenticated closure boundary for old-profile application vote issuance;
2. prove an admission-base upper bound `B_stop` sufficient to bound every old-profile subject that can still obtain a valid quorum after closure;
3. derive `D_stop = B_stop + oldMaxLockLifetimeBlocks` using checked arithmetic, and account for every greater authenticated recorded deadline;
4. advance finalized canonical height strictly beyond that bound as well as the journal's greatest admitted deadline, then reconcile and verify
   zero live entries before activation.

`B_stop` is not an arbitrary local height or wall-clock timestamp. The old profile's base-validation and signer-fencing evidence must justify it.
The fence must stop enough historically authorized honest signers from issuing new old-profile votes that no subject beyond the proven deadline
envelope can obtain a valid quorum. Pre-closure votes may still combine into a certificate after closure; that certificate retains its old signed
deadline and historical validation rules, and late assembly never extends its inclusion window. In particular, two old honest votes plus a later
Byzantine vote can complete a 3-of-4 certificate without violating the fence. Account for that case through the conservative expiry horizon rather
than requiring every certificate to have been assembled before closure. Node readiness responses or local flags alone do not prove the fence or
the bound. The consensus handover in Decision 3 separately preserves ordinary HotStuff safety and progress while application voting is closed.
If that evidence cannot be established, the deployment is not eligible for automated in-place activation. Unknown votes are not repaired by
fabricating journal history or assuming they never existed. A deployment that never enabled old application voting can instead prove that fact
across its complete historical authorized signer/deployment scope and, where admission/safety stores survive, also prove their reconciled empty
state. If no such store was ever created or its absence is accepted under Decision 3, independently authenticated deployment evidence must cover
every deployed binary, effective configuration and signing-key use throughout the domain's lifetime, showing that application lock/effect vote
issuance paths were unavailable or continuously disabled. Account for custom builds, configuration changes and alternate signing integrations;
uncovered intervals or unknown issuance paths do not qualify. A release label, an empty or missing store, or an archive-absence attestation alone
cannot establish never-enabled voting, and missing state is not reconstructed as an empty safety history.

Finality advancement during drain must use blocks valid under the old active profile. The new empty-plan grammar cannot be used before its own
activation to make the old domain drain. Where the old runtime cannot supply that progress, a separately verified bridge preserving old validity
rules or another explicitly planned recovery path is required; otherwise activation remains unavailable. A finality stall never permits an early
unlock, forced rotation or an administrative declaration that expiry occurred.

### 5. Storage and protocol activation share a recoverable boundary

Back up the complete stopped-node consistency group: application state, block/consensus data, safety journal, exact admission/idempotency and generic
pipeline namespaces, together with their active configuration. Preflight verifies old journal/physical equivalence, drain evidence, retained
historical decoders and all deterministic migration outputs before any new profile accepts writes.

Available retired-domain roots remain read-only outside the active migration namespaces. Include their verified content digests, any accepted
historical-absence attestations and supporting evidence, and every retirement/signing fence in backup and the durable activation/recovery decision.
Do not delete, normalize or load those roots as active state to pass a startup guard. Loss/tampering of expected-present archives or bound
attestations/evidence, and lost fencing, stop recovery before signing; an authenticated pre-baseline absence accepted under Decision 3 does not.
The absence route cannot excuse missing source-state data, new initialization records or any promises issued during this transition.

Prepare migrated state and indexes without exposing them as active. One durable activation/recovery decision binds their digests to the new
manifest and canonical boundary. Repeating recovery after a crash must select the complete old state or complete committed new state; it must not
combine an old journal with new canonical application state or activate only some namespaces. Startup reconciliation precedes public listeners and
new application votes. The implementation must define the public storage/application hooks needed for this composition; separate default stores
do not by themselves provide a cross-store transaction.

Failed preparation may discard inactive migration output; it must preserve the authoritative old group and all subsequent safety history. At
every stage, including before the local durable switch, abort/recovery retains handover signatures, published fences, highQC/lockedQC, voter
watermarks and any old-consensus progress since backup. A local abort cannot unilaterally withdraw a handover another validator can already use.
Continuing the old profile must honor the authenticated future boundary and fences, or use a separately verified cancellation/reverse transition.

After the switch, a maintenance rollback may restore a complete pre-upgrade group only while admission, listeners and signers remain fenced and
the group retains all transition safety evidence. It must also be proven that no subsequent externally valid vote, proposal or certificate,
safety-relevant voting write or canonical application write would be lost. An earlier backup missing a published fence or handover signature is
ineligible even when no new-profile block exists. Absence of new finalized blocks alone is not sufficient.

If no eligible whole-group restore can preserve that evidence and those writes, recovery proceeds forward, or a separately planned reverse
transition first drains and fences the new domain. A whole-group disaster restore must define its history-loss boundary and preserve
anti-equivocation records or establish a proven key/epoch/domain fence before signing resumes. Operator authorization to lose application history
does not authorize reuse of rolled-back signer state. Downgrading one binary or restoring individual namespaces is not rollback.

## Consequences

- Idle V2 chains can produce real consensus descendants without application placeholder transactions. A drain driver must keep an explicit
  finalized-height target until it passes the required deadline, then return to the configured idle policy. Empty-block validity does not promise
  progress when consensus cannot reach quorum.
- Input/reservation corrections need a format and compatibility inventory even though their intended safety semantics are already in ADR-0035.
- Historical data and new writes require separate version dispatch, including V2-to-V2 transitions.
- Some M2 deployments may require a compatible bridge or operator recovery before they can upgrade; automatic store conversion cannot establish
  missing safety evidence.
- Embedders retain ownership of application transitions, system/checkpoint transactions, object history and whole-group operational composition.

## Rejected Alternatives

- **Allow empty plans under the published version without activation:** changes the accepted consensus language for old validators and history.
- **Use a dummy application transaction or omit the plan root:** changes application membership or bypasses V2 commitment validation to obtain progress.
- **Treat empty lock subsets as empty certificates:** contradicts ADR-0035's consensus admission contract and expands certified-fast eligibility.
- **Re-encode old journals into the new format in place:** changes audit authority and cannot recover commitments or votes that were never recorded.
- **Activate when physical live counts reach zero:** misses unjournaled old votes and unresolved cross-namespace state.

## Required Evidence

- JVM/Scala.js canonical empty-plan bytes/root and negative vectors for empty waves, non-empty bodies, hidden state changes and historical version 1.
- A signed opening transaction as the sole compatibility consensus entry, with deterministic conversion from the authenticated continuation
  parent, complete actual access/reservation coverage and normal lock-subset rules. Reject missing/wrong authorization, a new or unsigned system
  source, another entry in its block, uncovered lock-eligible mutations and conversion writes absent from the committed body result.
  Cover mandatory signed opening deadlines with no lock certificate, base authentication, the lock-free initial-anchor rule, reservation deadline
  equality, inclusive application, real finalized-height/nonapplication expiry and retention on abandoned proposals or blocked opening progress.
  Initial bootstrap requires an empty derived subset and `None`; reject non-empty subsets regardless of certificate presence and reject omitted
  lock-eligible mutations. Handover openings retain their ordinary certificate/finalized-base conformance cases.
- Automatic V2 proposal, validation and application tests, including wrong/missing roots, missing preimages and V1-to-V2 plus V2-to-V2 boundaries.
- Handover with a certified/unfinalized old suffix, delayed old votes/QCs, retained highQC/lockedQC and voter watermarks, restart, and finality proofs
  spanning the boundary; replay from the finalized drain checkpoint to a distinct certified continuation parent, including old suffix writes and
  parent changes. An unsafe boundary or an unproven old-quorum fence must reject activation.
- Initial bootstrap from a non-empty inherited source state without HotStuff ancestry, both with no prior domain and with a preserved/fenced retired
  non-ancestor domain: identical authenticated
  genesis/roots, first signed opening, ordinary empty descendants and real finality. Reject wrong source provenance/root, untrusted peer bundles,
  validator/manifest mismatch, historical validator-artifact replay, unsupported genesis/QC rules and related or unclassified consensus history.
  Reject invented rollback/source-selection evidence, loss/tampering of expected-present archives or bound evidence, unfenced retired writers, reused target domains and signer-state
  resets. User-transaction signature-domain compatibility is tested against the embedder's explicit manifest/replay policy.
  Inject interruption during state installation and first voting; retry must preserve source fencing and every issued bootstrap/consensus promise.
- A retired non-ancestor domain whose raw archive was wholly or partly absent before preparation can bootstrap with independently verified
  rollback/inventory/non-ancestry and never-enabled evidence plus an authenticated absence attestation. Reject unsupported absence assertions,
  content/attestation digest confusion, missing source data, post-baseline loss disguised by replacement records, and loss of surviving evidence
  or no-resume fences. Recovery must retain the same evidence classification and all current-transition signing history.
- Idle finalization and deadline-targeted drain on independent validator runtimes, with stalled-quorum and retry/restart cases.
- Legacy unrecorded-vote expiry coverage, justified base-height bounds, fencing failures and refusal when old-profile progress or evidence is absent.
- Distinguish journal-covered deadlines, unrecorded-deadline closure bounds and proven never-enabled voting. Cover exact admission/journal crashes,
  exact lock-subject binding or bypass, lost journal history and generic effect subjects whose deadline is already covered by their recorded lock.
  A deadline watermark cannot substitute for a durable vote-subject record, and an unsupported exact-only claim cannot bypass the inferred horizon.
  Cover never-enabled domains with retained empty stores and with no retained store but complete authenticated binary/configuration/key-use
  inventories; reject gaps, unaccounted custom builds or signing paths, contradictory surviving state and store-absence-only assertions.
- Late assembly of a bounded old certificate from pre-closure honest votes and a post-closure Byzantine vote, with unchanged deadline validation
  and no new application after the conservative finalized-height horizon.
- Fault injection around prepare, durable activation and reopening, including a published pre-switch handover/fence and a returned vote before any
  new finalized block. Neither may be lost on abort/restore; historical signature/id/root equivalence and complete backup/restore remain required.
- Public, application-neutral conformance consumers and version/compatibility documentation that survive source export and run against released
  artifacts without private source dependencies.

## References

- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [ADR-0035: Application Execution Lanes, Ordered Waves, And Certified Fast Admission](0035-application-execution-lanes-ordered-waves-and-certified-fast-admission.md)
- [ADR-0036: Height-Bounded Application Locks And Explicit Pipeline Dependencies](0036-height-bounded-application-locks-and-explicit-pipeline-dependencies.md)
- [Plan 0033: Application Execution Conformance And V2 Activation](../plans/0033-application-execution-conformance-and-v2-activation-plan.md)
- [M2 release notes](../releases/v0.3.0-M2-release-notes.md)
- [M2 integration guide](../releases/v0.3.0-M2-integration-guide.md)
- [Published proposal-input surface](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ProposalInput.scala)
- [Published execution-plan validation](../../modules/core/shared/src/main/scala/org/sigilaris/core/application/protocol/ExecutionPlan.scala)
