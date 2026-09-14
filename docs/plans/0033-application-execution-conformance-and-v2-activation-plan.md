# 0033 - Application Execution Conformance And V2 Activation Plan

## Status

Complete — Phases 0–6 implemented, reviewed and committed with passing source, public export and candidate artifact evidence. Public publication and deployment remain separately scoped gates.

## Created

2026-09-08

## Last Updated

2026-09-12

## Background

The published `0.3.0-M2` artifacts provide height-bounded application locks, exact dependency profiles, historical validator-set verification,
ordered execution helpers, durable safety stores, and drain/rotation machinery. Artifact milestone M2 still uses `ProtocolVersion.M1`; the Maven
milestone, application protocol, execution-plan format, block-header format, and storage schema are separate version axes.

An integration audit found gaps between these public surfaces and the accepted ADR-0035/0036 target contract:

| Surface | M2 behavior or limitation | Required result |
| --- | --- | --- |
| Input descriptors | Both `MutableRead` and `MutableWrite` require `stableConflictId`; every such id enters both the lock subset and footprint, without an authority-eligibility distinction. Their identity sets coincide even though their encodings and commitment domains differ. | Independently verified full resolved-input, authority-eligible mutation lock-subset, and complete footprint commitments under an explicitly new derivation contract. |
| Execution-plan entries | Waves commit execution-id vectors without the ADR-0035 source, declaration, and classification-statement structure. | Versioned entries bind admission source, exact/compatibility declaration, certificate references, and applicable classification commitments. |
| Proposal reservations | Safety validation requires the reservation inputs to equal the lock inputs. | A complete read/write reservation may contain identities outside the lock subset. |
| Exact consensus execution | Both stages require live input locks and application effect certificates in the existing exact execution path. | Consensus sources use optional input-lock certificates and execute without a certified-fast effect certificate; HotStuff certification remains distinct. |
| Vote durability | Vote validation is read-only. Pre-certificate `validateLockVote` can leave deadlines unrecorded; effect validation requires a recorded same-deadline lock, and exact transport/registration journals the plan deadline without persisting individual vote subjects. | The public voting path atomically persists its complete safety claim before signing/returning a vote; upgrade checks distinguish deadline coverage from vote-subject durability. |
| Automatic HotStuff proposals | The default proposal input builds V1 headers; execution-plan validation rejects an empty plan. | Activated V2 proposals carry validated plan preimages and support canonical empty application blocks. |
| Finalized ancestry | Default branch context stops at the current finalized anchor, excluding older finalized producers. | A consumer can prove any eligible canonical producer ancestor, including one older than the current finalized tip. |
| Public conformance | Existing standalone smoke covers a small API sample; canonical fixtures and implementation suites are not all public. | Public, application-neutral JVM/Scala.js consumers reproduce the complete advertised protocol contract from Maven artifacts. |

These code-audit observations identify implementation gaps, not a reproducible activation or release-conformance result. Phase 0 must reproduce
the relevant cases in maintained fixtures and record their exact source/artifact versions and actual outcomes. Temporary local diagnostics are
not release evidence or an input required by this plan.

Plan 0031 remains the history of its completed implementation. This plan owns the additional conformance and V2 activation work. It does not
reclassify plan 0032's operational hardening as completed, or treat passing functional tests as resolution of the M2 dependency-security follow-up.

## Goal

Deliver a versioned, application-neutral implementation of the ADR-0035/0036 contract, with ADR-0037's empty-block and upgrade rules, such that:

1. execution inputs, distributed locks, and consensus reservations protect their distinct domains;
2. consensus-only and lock-bearing exact executions use the same deadline, reservation, replay, and recovery rules;
3. each honest validator cannot publish incompatible live votes across concurrency or restart;
4. the ordinary HotStuff runtime constructs and validates activated V2 blocks, including empty blocks needed for finality and maintenance;
5. eligible consumers can prove non-adjacent historical finalized producer ancestry;
6. supported deployments can drain and switch versions through a recoverable boundary while historical bytes retain their original meaning; and
7. a public consumer can verify these contracts without private source, private test fixtures, or application-specific packages.

## Scope

- Versioned core input manifests/descriptors, commitment domains, admission references, execution plans, validation failures, and golden vectors.
- Complete reservation footprints and durable pre-sign voting through public runtime APIs, with in-memory and persistent-store conformance.
- Lock-optional `ConsensusAdmission` for both activated exact pipeline execution modes.
- Historical ancestry proof lookup and validation for the exact consumer execution path.
- Automatic HotStuff proposal, preimage retrieval, validation, application, finalization, replay, and idle maintenance progress wiring.
- Explicit activation dispatch, schema migration/recovery, historical replay, drain prerequisites, and public operator/integration guidance.
- Authenticated initial V2 bootstrap from a source without HotStuff ancestry, including fenced retired non-ancestor domains with preserved
  archives or the explicitly verified historical-absence evidence allowed by ADR-0037.
- Public standalone artifact conformance and the evidence required for a subsequent release and deployment decision.

## Non-Goals

- No application transaction families, authority classes, object codecs, business-success rules, receipt schema, or application migration policy.
- No dependency on an embedding application's repository, documents, package coordinates, profile ids, or test data.
- No lock-free `CertifiedFastAdmission`, empty-vector `InputLockCertificate`, implicit `Latest`, arbitrary transaction DAG, cross-pipeline or
  cross-lane dependency chaining, or cross-epoch/configuration handoff of live locks and reservations.
- No wall-clock expiry, early unlock on proposal abandonment, or optimistic release when finality/proof data is unavailable.
- No rewriting published M1/M2 wire encodings, commitment domains, historical records, or immutable release artifacts.
- No automatic claim that every existing deployment supports a live upgrade; unsupported old-profile drain paths fail closed.
- No implementation of plan 0032's independent operational backlog or the separate crypto dependency remediation project.
- Creating this plan and its ADR does not implement a phase, select the next artifact milestone, merge code, tag, publish, or activate a deployment.
  Later release operations remain a separately scoped workflow using this plan's evidence.

## Related ADRs And Docs

- [ADR-0023: Validator-Set Rotation And Bootstrap Trust Roots](../adr/0023-validator-set-rotation-and-bootstrap-trust-roots.md)
- [ADR-0035: Application Execution Lanes, Ordered Waves, And Certified Fast Admission](../adr/0035-application-execution-lanes-ordered-waves-and-certified-fast-admission.md)
- [ADR-0036: Height-Bounded Application Locks And Explicit Pipeline Dependencies](../adr/0036-height-bounded-application-locks-and-explicit-pipeline-dependencies.md)
- [ADR-0037: Versioned Application Execution Upgrade And Empty Blocks](../adr/0037-versioned-application-execution-upgrade-and-empty-blocks.md)
- [ADR-0031: Certified-Ancestor Dependent Transaction Pipelining](../adr/0031-certified-ancestor-dependent-transaction-pipelining.md)
- [ADR-0032: Stage-Based Transaction Pipeline API](../adr/0032-stage-based-transaction-pipeline-api.md)
- [0031: Height-Bounded Locks And Exact Pipeline Release Plan](0031-height-bounded-application-locks-and-exact-pipeline-dependencies-plan.md)
- [0032: Application Safety And Exact Pipeline Operational Hardening Plan](0032-application-safety-and-exact-pipeline-operational-hardening-plan.md)
- [M2 Integration And Migration Guide](../releases/v0.3.0-M2-integration-guide.md)
- [M2 Release Notes And Dependency Security Follow-Up](../releases/v0.3.0-M2-release-notes.md)
- [Standalone Artifact Smoke](../../release-smoke/README.md)
- [Plans Guide](README.md)

## Decisions To Lock Before Implementation

1. **Keep the existing architectural decisions.** ADR-0035/0036 already require distinct input/lock/footprint commitments, reciprocal reservations,
   durable votes, optional consensus lock certificates, exact working-state execution, and proven non-adjacent descendants. Their implementation
   gaps do not authorize a different safety contract. ADR-0037 records the new empty-block and version-transition decisions.
2. **Freeze an explicit version matrix.** Inventory published M1/M2 encoders, decoders, validators, signature/subject/root domains, manifests,
   journal records, and replay entry points. Select new explicit versions for incompatible contracts and retain historical dispatch. Record the
   allowed combinations at each activation boundary, including V1 header to V2 header and an old V2 plan profile to a new V2 plan profile. One
   global `v2Enabled` boolean cannot express both transitions. Exact numeric tags, domain strings, public API signatures, and the next Maven
   milestone are Phase 0 deliverables, not numbers assumed by this document.
3. **Freeze the independent derivation APIs.** The application hook supplies authenticated, manifest-bound roles and authority eligibility;
   Sigilaris validates opaque stable identities and commitments. The full input vector retains read-only and consensus-only inputs. The lock
   vector contains only existing `Exact`/`Mutate` inputs with eligible authority. Declared footprints additionally cover all concrete reads,
   writes, and absent creation targets. Compatibility uses deterministic actual `AccessLog` coverage and cannot retroactively lock an undeclared
   existing mutation. Freeze versioned plan entries that commit the consensus/certified-fast source, signed transaction or certified subject,
   optional input-lock certificate, exact/compatibility declaration and its digest, and applicable classification-statement commitment. Preserve
   ADR-0035's witness canonicality, compatibility-singleton isolation, and cross-wave conflict rules. Specify membership, duplicates, canonical
   ordering, and typed failure behavior before codecs change. Apply ADR-0037's opening-source decision: a signed application transaction in a
   `ConsensusTransaction` compatibility-singleton entry, with an authenticated maintenance authority, deterministic conversion and complete actual
   access/reservation coverage. Freeze the application envelope and authorization hooks in P0.2; no new system source kind is introduced.
   The envelope must sign `lastInclusionHeight` even with an empty lock subset, and every opening reservation binds the same deadline and domain.
   Initial-bootstrap opening must have an empty derived lock subset and `None`; non-empty bootstrap opening is unsupported. Handover opening
   continues to use the normal finalized-base and lock-certificate rules.
4. **Freeze the durable voting and recovery boundary.** Define a public atomic operation that checks the reciprocal interlock and writes the
   complete transaction-scoped vote intent before invoking the signer or publishing the vote. Specify identical retry, signer failure,
   cancellation, ambiguous storage success, certificate import, and recovery behavior. Define how canonical state/result, applied index, and
   terminal lock/reservation updates participate in one recoverable application boundary, including the embedder's integration obligation.
   Reservations must represent multiple live owners and their read/write modes, transaction/execution and authenticated plan/proposal scope,
   independent deadlines, and terminal outcomes. Concurrent reads and consensus-ordered overlaps permitted by ADR-0035 must coexist. A reservation
   cannot become a global exclusive mutex merely because a cell is present in several valid footprints, and releasing one owner cannot erase
   another's claim. Authorized ordering never waives a conflicting external fast lock or the Exact freshness of an entry.
   The model fixes logical coverage and ownership, not duplication of the full footprint in every identity row. P0.2 must select the persistent
   witness/index representation and its resource limits; a footprint digest alone cannot prove non-conflict.
5. **Apply ADR-0037's empty-block contract.** The activated empty application plan has `waves = []`, empty application-body membership, and the
   version's canonical empty plan/body roots. Every present wave remains non-empty. An empty block's application state root equals its parent's;
   a root-changing system/checkpoint action requires an explicit application transaction under an authenticated manifest and a corresponding body
   result in a non-empty plan. An opening transaction is the sole compatibility consensus entry under Decision 3 and uses the normal lock rules.
   Finality/journal progress outside that application state root may continue. Freeze availability, validation, identity, and historical rejection
   vectors, including the new plan version and unchanged historical version 1 rejection behavior.
6. **Freeze ancestry and progress contracts.** Specify the authenticated historical ancestor proof/lookup hook, required retention or backfill,
   and typed unavailable/invalid outcomes. Define an idle drain target sufficient to finalize above every relevant signed deadline, bounded
   progress attempts, and stalled-finality diagnostics. Select the evidenced drain route: complete journal deadline coverage, an inferred horizon
   for unrecorded deadlines, or proven never-enabled voting. Exact transport/registration can establish deadline coverage only with retained
   descriptor/journal reconciliation and proof that every relevant signed subject, including lock execution/domain/deadline, was bounded before
   issuance with no bypass or lost history. A deadline watermark does not establish durable vote-subject safety. For deadlines without that coverage,
   prove the authenticated vote fence and admission-base bound `B_stop` covering every still-certifiable old subject; require finality above
   `D_stop = B_stop + oldMaxLockLifetimeBlocks` and every greater recorded deadline. Neither a local height/time nor empty physical stores prove
   that bound. Prove a supported old-profile drain or bridge route before activation; new empty-plan validity cannot be used before its activation
   to create that proof. Missing evidence makes in-place activation unsupported rather than authorizing invented vote history. Separately freeze
   the HotStuff handover: retain the certified/unfinalized suffix,
   highQC/lockedQC and voter watermarks, choose an authenticated future profile boundary, prove the old-quorum fence and valid continuation, and
   validate mixed-range finality proofs. A finalized-height-plus-one switch or discarded speculative suffix cannot substitute for that proof.
   The finalized drain checkpoint and certified continuation parent may differ: replay the retained old suffix to the parent's state before
   preparing opening execution, and invalidate prepared output if that parent changes. Pre-closure votes may assemble a bounded certificate after
   closure; the fence prevents new subjects beyond the proven deadline envelope rather than pretending all old signatures have disappeared.
7. **Freeze public evidence and API migration.** Choose exported golden/compatibility document and executable fixture paths, define old/new API
   mappings, and establish a reproducible M2 baseline from all five public coordinates. No required guide or fixture may exist only under
   `docs/dev`, `src/test`, or a temporary local path. Coordinate shared storage API changes with plan 0032 without absorbing its backlog.
8. **Separate consensus application from fast certification.** A `ConsensusAdmission` source is independently executed and validated by HotStuff
   proposal voters without an application-level certified-fast effect quorum. Its input-lock certificate remains required exactly when its lock
   subset is non-empty. A `CertifiedFastAdmission` source still requires its full lock/execution certificates and artifact verification. Define
   separate public result/application paths and lifecycle projections; a certified-ancestor producer's block QC and ancestry proof are not a fast
   effect certificate. Removing a lock check from an otherwise certificate-required method does not implement the consensus path.
9. **Classify consensus ancestry and retirement before activation.** Continue a source lineage with HotStuff ancestry through handover. A source
   without that ancestry can bootstrap either with no prior domain or with proven retired non-ancestor domains whose available evidence is
   preserved read-only and whose signing/write authority remains fenced.
   The latter requires the prior rollback/source-selection decision, source provenance/non-ancestry proof and archived root/QC/signer/fence
   inventory. For archives already absent before this transition, use ADR-0037's narrow authenticated absence route with independent
   rollback/deployment/key/root inventory and proven never-enabled application voting in each affected domain. A typed attestation records prior
   absence; it cannot replace eligibility evidence or excuse loss after the preparation baseline. Existing directories alone neither authorize
   nor prohibit bootstrap. Unknown provenance, related history or an unenforced retirement fence
   requires verified recovery, not automatic bootstrap. Bind source provenance/schema/root, a fresh chain and validator-artifact signing domain,
   initial ordered validator set, genesis/initial-justify rules and activated manifests in locally trusted bootstrap material. The initial anchor retains the source root;
   opening conversion executes in its first ordinary V2 descendant. Freeze public import/verification/initialization hooks and durable startup
   identity in P0.2. Existing trust-root types authorize validator sets, and the current coordinator expects a real finalized-anchor proof; neither
   alone supplies this state-import contract. New-chain height cannot expire old application claims; evaluate every relevant application-voting
   domain independently from consensus ancestry/retirement. Fresh-domain separation covers validator-signed consensus and application artifacts;
   the embedder retains ownership of user-transaction signature domains and replay policy, without an automatic client network/domain change.

## Change Areas

### Code

- Core protocol: [ApplicationInputs.scala](../../modules/core/shared/src/main/scala/org/sigilaris/core/application/protocol/ApplicationInputs.scala),
  [ApplicationSafety.scala](../../modules/core/shared/src/main/scala/org/sigilaris/core/application/protocol/ApplicationSafety.scala), and
  [ExecutionPlan.scala](../../modules/core/shared/src/main/scala/org/sigilaris/core/application/protocol/ExecutionPlan.scala), plus their versioned
  header, manifest, artifact, witness, and codec consumers.
- Safety runtime and store contracts: [ApplicationSafetyRuntime.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyRuntime.scala),
  [ApplicationSafetyStore.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyStore.scala),
  [ApplicationSafetyModels.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyModels.scala), and
  [SwayDbApplicationSafetyStore.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb/SwayDbApplicationSafetyStore.scala).
- Exact pipeline integration: [ExactPipelineAdmission.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineAdmission.scala),
  [ExactPipelineExecutionRuntime.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineExecutionRuntime.scala),
  persistent exact records, transports, and public failure/lifecycle projections.
- HotStuff integration: [ProposalInput.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ProposalInput.scala),
  [ApplicationProposalValidation.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ApplicationProposalValidation.scala),
  [Materialization.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Materialization.scala),
  [Finalization.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Finalization.scala), and
  [FinalityDrive.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/FinalityDrive.scala), plus historical
  backfill, recovery, bootstrap, and public assembly hooks.
- Initial-state import and consensus initialization: [Bootstrap.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Bootstrap.scala),
  [BootstrapCoordinator.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/BootstrapCoordinator.scala), and
  [SnapshotSync.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotSync.scala), plus the public
  source-provenance, inherited-state verification and recoverable initial-anchor installation adapters.
- Public conformance: extend `release-smoke` or add an equally standalone exported build with source only in its public main/example paths.

### Tests

- Core JVM/Scala.js canonical vectors, negative version/shape cases, and independent input/lock/footprint derivation.
- Safety-store transaction races, signer/storage faults, crash recovery, reciprocal reservation interlocks, and historical artifact replay.
- Exact `OrderedAtomic` and `CertifiedAncestor` execution with every supported lock-presence combination and historical producer ancestry.
- Automatic V2 proposal/preimage/validation/application/finalization and idle drain integration.
- Actual four-validator runtimes with separate identities and stores, real 3-of-4 votes, transport, proposal rounds, finalization, and restart.
- Public standalone JVM/Scala.js artifact consumers and old/new migration/recovery fixtures.

### Docs

- Public version/codec/domain compatibility matrix and canonical golden vectors.
- Public integration guide covering derivation hooks, atomic voting, optional consensus certificates, ancestor proofs, and V2 assembly.
- Public drain, backup, migration, recovery, unsupported-upgrade, and activation runbook.
- Release-readiness evidence with exact commands, source revision, resolved artifact checksums, fixture results, and explicit remaining work.
- ADR links and this plan's phase evidence, without rewriting the completed plan 0031 or historical M1/M2 release claims.

## Implementation Phases

Each phase has a reviewable deliverable and gate. Record commands and actual outcomes as implementation proceeds; all checkboxes remain unchecked
until the corresponding work and review are complete. Resolve findings and apply relevant lessons to later phases before crossing a gate.

### Execution Record

Implementation started on `2026-09-11` from source `efe4f4529a1c266e024ea8be74b3c7ad58a08847` on
`docs/application-execution-conformance-and-v2-activation`, with a clean working tree.
The selected development version is `0.3.0-M3-SNAPSHOT`, with `0.3.0-M3` as the candidate milestone; no release or deployment is authorized
by that selection. Phase commits include implementation, actual gate results, resolved reviews and lessons applied to the remaining work.

Phase 0's maintained contract is in [V2 contract](../conformance/v2-contract.md), with the
[core compatibility inventory](../conformance/core-compatibility-inventory.md),
[runtime compatibility inventory](../conformance/runtime-compatibility-inventory.md) and
[requirement-to-evidence map](../conformance/v2-evidence-map.md). The map links the actual public case families and their phase evidence; candidate staging is distinct from later public-Maven verification.

| Phase | Execution / gate | Review | Commit |
| --- | --- | --- | --- |
| 0 | [Contract/public baseline gate passed](../conformance/p0-gate.md) | Corrected core/exact, runtime/exact and fixture reviews: **No finding** | `0a7b48f` |
| 1 | [Core gate](../conformance/p1-core-gate.md), [public vectors](../conformance/v2-public-vectors.md), [historical artifacts](../conformance/historical-protocol-vectors.md) | Corrected input/read coverage, decoder limits and JS Low-S; independent re-review **No finding**; JVM 526 / JS 525 plus final shared fixtures and formatting passed | `b9fa3f0` |
| 2 | [Durable voting/application gate](../conformance/p2-gate.md), [public voting evidence](../conformance/p2-voting-evidence.md) | Corrected original-proof recovery, minority-certificate/application handling, owner closure and capacity; independent re-review **No finding**; node 776, shared core 15 per platform, final ordered and formatting passed | `16ba041` |
| 3 | [Exact execution/ancestry gate](../conformance/p3-gate.md), [public exact evidence](../conformance/p3-exact-evidence.md) | Corrected source ownership, alias recovery, per-execution readiness, canonical profile outcomes and ownerless terminal evidence; independent re-review **No finding**; shared 12 per platform, node 81 plus ordered 1 and formatting passed | `feab0f1` |
| 4 | [Ordinary runtime/application gate](../conformance/p4-gate.md), [four-runtime evidence](../conformance/p4-runtime-evidence.md) | Corrected shared signing fences, actual source/QC replay, same-store materialization permission and complete pending-finality closure; final self/independent reviews **No finding**; 116 regressions plus 25 final cases and formatting passed | `c37a97a` |
| 5 | [Activation/bootstrap/drain gate passed](../conformance/p5-gate.md), including actual four-node Gossip handover and all five bootstrap cases | Corrected source loss, exact key-use leases, initial recovery/finality, physical identity and partial retired scope coverage; final self/independent reviews **No finding** | `34cae52` |
| 6 | [Final source/public candidate/export gate passed](../conformance/p6-gate.md): 2,324 source tests; five Maven artifacts, 27 JVM groups, both JS outputs, 376 code/fixture identities and 273 valid public file references | Corrected private reflective diagnostics helper; complete 958-case node recheck passed. Final self/independent reviews **No finding** | `adf074c` |

The full implementation received a further review on `2026-09-12`, starting from `adf074c`. The
[post-implementation review](../conformance/post-plan-review.md) records corrections to malformed signature recovery, exported signing-boundary
coverage, staging artifact provenance and the private integration-test time limit. Final self/independent reviews report **No finding**; all **2,337 source tests**, **28 public JVM groups** and both Scala.js outputs passed with fresh validation identities. Prior phase results remain dated evidence for their original revisions.

The supplied [second full review](../conformance/full-review-2026-09-12.md) and [correction pass](../conformance/full-review-corrections-2026-09-12.md) supersede a blanket reading of the earlier No finding verdict. Selected corrections passed **2,342 source tests**, the public M1/M2 consumers and all **28** final V2 JVM groups plus both JS outputs. The original implementation gates remain complete; verified repair, lifetime checkpoints, durable proposal uniqueness, local-validator ownership and callback reentrancy remain open in [plan 0034](0034-v2-recovery-and-lifetime-cost-hardening-plan.md).

The [public input review corrections](../conformance/public-input-review-corrections-2026-09-12.md) close the later inventory coverage and JS probe gaps with independent input discovery, actual compiler-source ledgers, typed V2 rejection and one probe per execution. Historical test records remain scoped to their original inputs.

### Lessons Applied To Remaining Work

- **P1 historical dispatch:** M1 and M2 share protocol 1 but differ in family-id parsing and duplicate-identity validation. Preserve separate
  immutable fixtures and authenticated `LegacyM1`/`LegacyM2` provenance; do not infer artifact-level semantics from the protocol integer.
  Introduce additive V2 types instead of passing a new integer to old v1 normalization functions.
- **P1 plan/codecs:** retain existing block-body bytes/root, sort conflict-free entries by source id, and bind complete classification statements
  plus required proof availability. Freeze field order and primitive widths before implementation. Include manifest schema and transport
  discriminators in reviews alongside core encoders.
- **P2 voting/storage:** test durable subjects, deadline coverage and authenticated admission base as separate properties. Canonical footprint
  encoding includes metadata even for an empty footprint; chunk count follows encoded bytes, and all nonfinal chunks have the fixed full size.
  Record witness once with owner references in the conflict index; exercise recovery without duplicating full footprints per identity.
  Input authentication covers immutable/opaque signed fields as well as state proofs. Full reservations include precondition and authority
  validation reads even when execution performs no write; replay and recovery must retain this coverage. Structural derivation and a matching
  commitment are not authenticated input capabilities, so voting must call the configured verifier before durable acquisition.
  Decode protocol-bounded witnesses before allocating attacker-selected counts; do not invent extra certificate or proof-size validity limits
  outside the authenticated contract. Keep local capacity failures distinct from protocol rejection.
- **P2 review lessons for P3–P5:** same-execution owners have one signed deadline, and application resolves every such live owner together while
  preserving other executions. Canonical application included within the deadline remains materializable after later finality; new vote issuance
  has a different time boundary. Creation declarations require authenticated absence even when unused, and those observations remain reservation
  reads rather than invented creation events. Recovered subjects/certificates require actual historical authority verification, and original
  cross-proposal overlap requires its retained/backfilled ordering evidence. An opaque authorization or inventory digest cannot replace that proof.
  Reuse the [durable integration boundary](../conformance/v2-durable-integration.md) for exact and transition operations: immutable evidence blobs
  precede a referencing journal record, unknown storage outcomes fence the same signing gate, and startup must authenticate the relevant record
  classes before reopening. Retain the full original vote request/plan and rerun its verifier before accepting reconstructed owner coverage; checking only a
  consensus intent's plan root cannot detect a substituted witness. Extend and compose the recovery authenticators explicitly; their
  operation-scope filters are not substitutes for source or application proof verification.
- **P2 canonical application amendment:** a minority local lock cannot veto a different execution's actual finality. The unpublished runtime
  operation contract now permits `ApplicationPrepare` to atomically include every selected owner/witness together with its authenticated finality
  inventory. This grants canonical materialization only; ordinary proposal/lock/effect voting keeps its reciprocal interlocks. Preserve the
  minority claim and deadline through application and restart, then resolve it only using its own canonical outcome or nonapplication expiry.
  Do not persist a separate unexplained overlap before retaining its finality evidence. Use maps/sets for large witness index construction;
  per-identity linear scans become quadratic at the supported 100,000-identity limit.
- **P3 ancestry:** a maintained M2 fixture can expose the public runtime's reaction to supplied ancestry contexts; that does not prove default
  lookup correctness. The new gate must exercise real retained/backfilled proof lookup after finalized-tip advancement.
  Bind the explicit producer-output promise before admission and verify actual output at consumption without rebinding signed input.
  Keep durable stage/output ownership and exact registration/lifecycle transitions reconciled with the safety journal. Public verifiers must
  make private validated request types constructible through authenticated boundaries.
  Revalidate all signed literal fields and instrument authentication reads at every ordered pre-state, including an unsuccessful reducer result.
  Bound two-stage exact vectors before allocation and validate each lifecycle case explicitly; numeric tag ordering is not a semantic state machine.
  Separate a speculative exact candidate result from P2's finalized application preparation. Actual finality authorizes canonical materialization;
  an unfinalized producer QC authorizes ancestry proof only. Complete reservation can precede a fast effect quorum, so lifecycle presence rules
  must not invent a certificate to describe that reservation. Retain idempotency aliases in authoritative history, including aliases preceding
  later lifecycle updates; the latest-record projection alone cannot preserve their ownership. Verify original source closure directly from the
  recovering immutable state and retained evidence, avoiding a callback into the same store whose signing gate is held.
- **P3 review lessons for P4–P6:** route both automatic and explicit votes through the shared durable gate and require every exact execution in
  the full proposal to obtain readiness. Clear readiness on restart; neither another pipeline's success nor a retained Reserved tag grants it.
  Materialization must revalidate the original exact profile and normalized results independently of actual finality, including nonvoting nodes.
  Preserve alias history, permanent stage/output ownership and committed ownerless terminal resolutions through migration and replay. A late
  certificate archives the existing terminal outcome and cannot make an expired admitted execution live. Source authentication and recovery use
  the original immutable safety projection without recursively entering the live gate. Preimage writes and reads must share the same storage
  failure fence as signing; unknown raw backend outcomes cannot leave prior signing permission active. P6 exports and executes the public
  independent-pipeline and actual application/fault cases as well as the candidate matrix; source tests do not replace artifact evidence.
- **P4/P5 consensus domains:** preserve existing HotStuff signed layouts by using the fresh canonical ChainId as the validator signing-domain
  discriminator. Initial justify must be a real bundle-bound quorum certificate and use an explicit initial-only verification path.
  The core opening executor only prepares immutable working state. Runtime integration must bind the actual parent/base, required first block,
  sole compatibility entry, certificates and complete reservations before recoverable publication. Maintenance signatures use the family-bound
  application scheme; the validator artifact's fixed signature representation is not a universal user-transaction codec.
  Reuse the finalized application capability for nodes that did not vote for a finalized block; never relax pre-vote checks to achieve materialization.
  Empty blocks still need durable consensus anti-equivocation intents even though they have no application owners or deadlines.
- **P4 review lessons for P5–P6:** bind ordinary signing to the identical finalized-application safety store and hold its gate through actual
  verification, durable acquisition and signature. Recheck current consensus finality before signing; delayed lower observations cannot erase a
  higher pending target, and durable prepared batches survive reconstruction of the application bridge. Retain every pending authenticated
  height and verify that the entire selected catch-up path contains them before the first canonical preparation. Actual conflicting finality remains a
  fault across retries and must be reconstructed from retained consensus history on restart. A normal duplicate observer must not clear existing
  exact or voting readiness by unconditionally recovering the safety store.
  Retain full historical proposal identities: different valid QC signer subsets can produce different proposal wrappers for the same block.
  Resolve a child's parent by its actual QC subject, preserving every original wrapper needed by retained vote-request authentication. Exercise
  a continuous historical path to an installed anchor; neither a fabricated height gap nor a height-only skip establishes ancestry.
  Header 2 predates application protocol 2, so explicit M2 historical authentication remains distinct from activated V2 authentication. A legacy
  allow-all hook alone cannot authorize header-2 signing. Persist maintenance targets before issuing progress work, reconstruct them after restart,
  and derive actual expiry only from original-domain finalized evidence. The stopped-group transition must preserve pending materialization,
  complete original consensus evidence, maintenance intent and the same signing failure fence.
- **P5 upgrades:** deadline watermarks and empty stores do not establish pre-sign safety or a valid old-profile progress route. Keep upgrade
  support conditional on authenticated deployment evidence and successful old-valid progress fixtures; never fabricate missing subjects.
  Extend P2's single-context historical finality verifier with the authenticated transition ranges before claiming cross-boundary handover support.
  Local journal/HEAD consistency does not detect a coordinated rollback of both; backup and startup must additionally preserve externally visible
  fences, baseline evidence and consensus watermarks. Actual filesystem force/atomic-move guarantees are an explicit deployment prerequisite.
- **P5 review lessons for remaining activation and P6:** original signature authentication is timeless; fresh key use requires a separate current authorization. Bind the actual safety and finalized-application gates to the exact context, validator, preimage and immutable committed history. A generic readiness flag or unrelated gate holder is insufficient. Clear these ephemeral permissions on every exit, and reject a new signature once original-domain finality reaches its signed deadline.
  Apply the same held-gate permission to proposal/timeout/new-view key use, deriving their exact preimages from canonical typed requests and rejecting vote/application requests on that control path. Initial signatures require the actual sealed installer's complete-source, exact-request lease; a previously forced initial intent alone cannot bypass current source verification.
  Reopen the controller with an original-evidence-only authenticator before constructing the live safety/finalizer dependency graph; that recovery-only interpreter cannot authorize new key use. Bind installed capabilities to their actual journal/controller objects, not only identical domain/digest values.
  Keep old canonical F distinct from installed working P. Authenticate and commit original-profile F→P advances before any target application prepare, including recovery of a prepared target tail. A restored complete-group digest commits the original group record, not a generic inventory in another hash domain.
  Validate original journal acquisitions at every committed prefix, then authenticate transition completion against the complete recovered history before Ready. An earlier legitimate Bound/Prepared prefix must not be misclassified as a completed transition; it also cannot independently authorize live signing.
  Initial G is an authenticated traversal boundary, not a finalized block. Keep its internal branch stop separate from actual finalized metadata and transaction-uniqueness cache keys. Preserve both present and historically absent evidence for partly absent retired domains; independently authenticated scope inventories must be disjoint.
  A never-enabled fixture does not establish a live-state drain. Maintain an original before-key issuance adapter with birth-forced policy and original-domain expiry evidence for that positive route, alongside the independently proven never-enabled route.
  Keep cryptographic caches bounded and keyed by complete immutable artifacts and all actual validator members/keys. Always recheck profile, original source, application replay and live fences outside the cache, including source loss after a successful lookup.
  Replay retained original history once into each recovering sink and transport new artifacts normally. Preserve retries for pending finalization proofs; equal tracker state alone does not authorize skipping a failed/backfill observation. A neutral malformed-signature verifier must convert nonfatal curve-recovery exceptions to rejection, never a successful cache entry.
  Audit public execution separately from source tests: reference opening oracles do not replace production OpeningValidation negatives, codec golden vectors do not replace authority/inventory or historical schedule validation, and successful initialization does not establish reservation retention when a mandatory opening stalls. Carry each actual case into the exported runner.
- **P6 public evidence:** exporter rules remove every `src/test` tree. Keep all required vectors/consumers in public main paths and distinguish
  pinned public M2 resolution, candidate staging and later public-Maven verification.
  Compile the same exported consumer sources in module test targets to catch API drift before staging; this source gate does not replace the
  independent five-coordinate artifact gate. Historical M1/M2 consumers must also match immutable compiler-JAR hashes.
  Exercise real signing on both platforms: the first V2 run exposed missing Low-S normalization in the JavaScript signer despite successful
  JVM signatures and historical codec fixtures. Keep deterministic signature bytes/recovery checks alongside source and optimized consumers;
  this functional correction does not resolve the separately tracked cryptographic dependency-security review.
  Keep the final source and candidate artifact gates distinct: exact public fixture bodies and selected compiler JAR checksums must match independently of the private source wrappers. A standalone runner can discover duplicate suites through a source test classpath; public main execution avoids counting those duplicates as independent evidence. Recompute fixed transition literals with an independent encoder/signature implementation, compare both JSON and Scala values without rewriting them, and retain Keccak rate-boundary checks even when the selected vectors do not hit that edge.
  Constructor-shape changes also require updating reflective fault-injection helpers. Keep their original failure assertions and rerun the affected suite; a private test-only correction does not change already verified public artifact bytes. The complete final target is repeated without dropping cases, with independent fork groups where that avoids serializing unrelated temporary-store scenarios.

- **Post-implementation review:** test shape-valid malformed signatures through actual public authentication on JVM and Scala.js, and keep
  crypto recovery within its declared failure type. Preserve actual historical recovery behavior, including JVM recovery-byte aliases;
  documented input ranges alone do not justify tightening a shared primitive used for old replay. Export executable last-key-use, deadline and cancellation checks through public APIs without
  exposing private signing capabilities. Verify each candidate JAR's actual staging origin so Central fallback cannot masquerade as candidate
  evidence. Give full-history integration tests measured timing headroom without weakening their protocol assertions, and repeat a timed-out target after correcting its private wrapper limit. Future release gates must retain the prior dated inventories and record fresh source/artifact identities after corrections.

- **Compiler input evidence:** compare discovered source paths with the build tool's actual selected sources; inheriting an old list of passing checksums cannot establish coverage. Preserve profile-specific exception contracts and execute each platform probe once.

- **Independent full-review corrections:** test independently assembled initial QCs through actual nodes, validate an audit scope against the whole lifetime, and preserve exact proof identity/failure/pending-work invalidation when skipping duplicate observations. Failed or cancelled signing must also invalidate observer reuse before gate release, so a duplicate notification can recover a durable vote-write failure. Bound local request bookkeeping without evicting budgets, and use reachable commits plus dated manifests for reproducible historical evidence. Keep remaining repair/compaction/ownership design work explicit instead of declaring system-wide No finding.

### Phase 0: Contract, Version, And Public Baseline Freeze

- **P0.1 — Compatibility inventory:** classify every affected published API, codec, signature/hash domain, validator, and journal path; distinguish
  artifact/protocol/plan/header/schema versions and establish immutable historical fixtures.
  Record that M2's `MutableRead` and `MutableWrite` stable identities both enter `lockEntries` and `footprintEntries`: the v1 lock subset has the
  same identity set as the descriptor footprint. Replacing this with authority-eligible Exact mutations is a new derivation contract/domain,
  not an optional-field extension to `sigilaris.application.input.lock-subset.v1`; retain the old domain's historical bytes and semantics.
  Inventory pre-certificate lock validation, generic/exact effect validation, exact admission/transport/registration and startup reconciliation
  separately: identify which boundary records a subject, a deadline watermark, or neither, and which adapter must bind lock subjects to exact plans.
- **P0.2 — Contract freeze:** resolve the decisions above, review ADR-0037, and publish the selected version matrix, derivation/voting/ancestry APIs,
  failure contracts, and transition prerequisites, including legacy vote-fencing/base-bound evidence and conservative expiry horizons. Select the
  implementation milestone without altering any M1/M2 artifact.
  Freeze the signed opening-transaction envelope, application maintenance-authorization verifier, classification reason and deterministic conversion
  hook for its compatibility-singleton consensus source. Prove normal input/certificate/reservation validation at the boundary using a neutral
  migration fixture; dynamically discovered existing mutations must be consensus-only, and uncovered lock-eligible mutations must fail.
  Require signed `lastInclusionHeight` regardless of lock presence and bind every reservation to it. Freeze the authenticated admission base and
  checked lifetime bound, including an explicit initial-anchor `G` base only for lock-free bootstrap opening; preserve normal finalized-base/lock
  certificate rules for handover opening. Bootstrap requires an empty derived lock subset and `None`; reject non-empty bootstrap opening before
  voting rather than attempting to manufacture a finalized base or omit lock-eligible inputs. Inclusion is valid through the deadline; expiry requires same-domain finalized height strictly above it plus
  authenticated nonapplication evidence. Freeze a boundary-compatible failed-opening progress/recovery path or retain live claims and stop
  activation; abandonment or skipping a mandatory opening with empty blocks cannot release them.
  Select and freeze the opening reservation's physical representation. Evaluate one canonical complete read/write witness, referenced by its
  footprint commitment, with a reconstructible exact multi-owner conflict index; justify any alternative with equivalent coverage and recovery.
  Avoid storing a full large footprint in every identity row. Fix canonical witness/chunk codecs and authenticated limits for identity count,
  encoded bytes and chunk count, separately from node-local execution/memory/disk/recovery capacity checks. Oversized openings fail before voting;
  local capacity shortage holds activation without changing consensus validity. Chunked storage/transport retains one opening transaction and
  atomic application, and cannot bypass protocol limits by splitting the conversion into several canonical transactions.
  Freeze the initial-bootstrap bundle, source provenance/schema/complete state-data verification, fresh chain/signing-domain and validator-set
  binding, genesis height/initial-justify/QC rules, first opening parent and public import/initialization APIs. Define the durable startup record
  and the three-way ancestry/retirement evidence, read-only archive inventory and enforceable retired-domain fences. Distinguish never-used target
  validator signing domains from continued recovery of an already bound initialization; preserve the embedder's user-signature/replay contract.
  Freeze distinct present-content and pre-existing-absence evidence kinds. Define the absence attestation's codec, locally trusted authentication
  authority, domain/source/target binding, missing-data scope, known circumstances and explicit unknowns, and supporting evidence references.
  Independently verify rollback/inventory/non-ancestry and never-enabled application voting for affected missing-history domains. Bind the evidence
  baseline before preparation; no new baseline or replacement attestation can excuse later loss. Missing raw hashes/watermarks are not fabricated.
  Freeze the canonical never-enabled evidence format, trusted authentication authority and verifier: bind the domain and historical authorized
  signer/key scope to authenticated release/deployment inventories identifying actual binary digests, effective configurations and their active
  intervals, and all signing-key use paths. Require complete lifetime coverage showing application lock/effect vote issuance was unavailable or
  continuously disabled, including custom builds, configuration changes and alternate signing integrations. Bind the retained proof's digest into
  the transition evidence baseline separately from the archive-absence attestation. Specify rejection of gaps, unknown paths and contradictions,
  and reconciled empty-state checks for surviving admission/safety stores; no-store cases must not require fabricated empty stores or watermarks.
- **P0.3 — Reproducible baseline:** resolve all five M2 coordinates from a public Maven repository with Maven Local and private/source dependencies
  unavailable; run maintained neutral reproductions of the audit cases and preserve old canonical bytes and expected failures.
  Name and retain the fixtures for mixed read/write descriptor and reservation derivation, conflicting pre-certificate lock votes, empty-lock
  exact stages, historical finalized-ancestor consumption and empty-plan rejection; map the remaining audited surfaces through P0.4.
- **P0.4 — Evidence map and gate:** map each requirement below to a named maintained fixture and public guide/vector, retaining ADR-0035/0036's
  rejection of cross-lane dependencies and cross-epoch/configuration live-state handoff. Gate: no implementation depends on an unselected
  wire/schema contract, private-only consumer input, or an unproven supported live-upgrade route.

### Phase 1: Core Input, Lock, Footprint, And Plan Contracts

- **P1.1 — Independent descriptors:** implement the versioned role/eligibility derivation and validation with separate full-input, lock-subset,
  declared-footprint, and actual-footprint commitments. Include reads, consensus-only mutations, creation targets, and exact preconditions.
- **P1.2 — Plan entries and admission references:** implement versioned source, declaration and classification-statement commitments in the plan
  preimage. Encode a consensus source with `None` precisely when its derived lock subset is empty and `Some` precisely when a matching non-empty
  subset requires a lock certificate. Keep certified-fast sources bound to their complete certificates and non-empty lock vectors. Preserve
  compatibility-singleton as the sole source in its block rather than an indistinguishable ordinary ordered singleton.
  Represent the opening transition with the same signed consensus source and compatibility declaration; require its manifest-bound authorization
  and complete actual access/reservation coverage, with no implicit state conversion or additional system source variant. Validate its signed
  deadline and reservation equality even when its input-lock certificate is `None`. Bootstrap opening requires `None` and an independently derived
  empty subset; retain the full footprint and reject omissions made to force that subset empty.
- **P1.3 — Empty plan and historical dispatch:** implement the newly activated canonical empty plan/body contract, reject empty waves and mismatched
  membership/roots, and retain old-version validation and hashing without reinterpretation.
- **P1.4 — Core gate:** publish old/new byte and digest vectors; pass JVM/Scala.js agreement and negative manifest, exact binding, missing/extra
  lock, footprint-substitution, source/declaration/certificate substitution, compatibility isolation, unused or missing classification statements,
  alternate statement encodings, source membership, and unsupported-version cases. Cover conflicts hidden across conflict-free waves and allowed
  ordered overlap; changed source semantics must change the authenticated commitment rather than rely on an uncommitted side flag.

### Phase 2: Durable Voting And Complete Reservations

- **P2.1 — Atomic voting API:** transact validation, reciprocal conflict checks, and complete vote-intent persistence before signing/publication.
  Make identical retries safe across signer failure, cancellation, ambiguous writes, restart, and later certificate import.
  Use an authoritative journal with an explicit local-device synchronization barrier before signing. The existing SwayDB 0.16.2 public
  `put` boundary supplies no explicit flush/force contract; do not infer pre-sign durability from its completion. Keep derived snapshots/indexes
  reconstructible, preserve malformed journal tails for diagnosis, and fence ambiguous writes until verified recovery. A file backend must
  handle full writes, force, atomic metadata selection and directory synchronization without silently falling back to weaker operations.
- **P2.2 — Reservation model:** reserve complete concrete read/write identities independently of the lock subset. Include read-only inputs,
  consensus-only mutations, absent creation targets, and compatibility scan results; reject uncovered lock-eligible existing mutations before
  voting. Persist commitment, owner/execution, authenticated plan/proposal scope, deadline, and the witness needed to validate/recover each claim.
  Support multiple reader claims and consensus overlaps authorized by the active branch/wave rules, including an ordered read then write of a
  consensus-only cell. Reject forbidden external fast conflicts. Expiry/application removes only the resolved owner's claim and keeps other
  owners protected; retries must not overwrite a different live claim or its deadline.
  Implement P0.2's representation: verify the complete witness against deterministic actual `AccessLog`, durably publish its authoritative claim
  and witness before signing, and validate/rebuild derived conflict indexes before voting on restart. A digest alone is not a disjointness proof.
  Missing/corrupt witness chunks or incomplete index coverage fail closed until verified recovery completes. Retain shared witness data while any
  owner still needs it, and preserve the historical evidence required after terminal cleanup.
- **P2.3 — Recoverable application:** implement one recoverable boundary for state/result, applied index, and terminal locks/reservations, with a
  documented public embedder hook. Preserve applied-index-guarded expiry, no local early release, terminal retry, and drain-to-empty semantics.
  Bind selected owner/witness acquisition and actual finalized application evidence atomically in preparation. Permit a nonvoting node to
  materialize finality while preserving its incompatible minority claims; this exception grants no new voting permission. Recover complete
  state/finality/nonapplication evidence before publication, including inclusion at the deadline followed by later materialization.
- **P2.4 — Safety gate:** exercise concurrent conflicting votes and both directions of lock/reservation acquisition using deterministic barriers;
  inject failures at durable boundaries and restart both store implementations as applicable. A failed signer retains the persisted safety claim,
  and no observation can mistake partial application or unknown storage outcome for permission to vote again. An individual honest validator must
  not vote for incompatible live lock/effect subjects; different honest validators may initially split their votes. Prove that at most one
  conflicting quorum certificate can form under the tolerated Byzantine bound, including split-vote contention and Byzantine double voting.
  Test read/read coexistence, authorized ordered overlap, and one owner's expiry/application while another owner's reservation remains live.
  Exercise production-scale opening footprints at the protocol limits and just beyond them, local capacity failure, missing/duplicate/tampered
  chunks, omitted actual accesses and crashes between witness/claim/index writes. Recovery must restore the same reciprocal conflict decisions
  without partial canonical conversion or loss of another owner's protection.

### Phase 3: Exact Consensus Modes And Historical Ancestry

- **P3.1 — Consensus application paths:** implement result validation and atomic application for consensus sources without requiring or emitting a
  certified-fast effect certificate. Require input-lock certificates only for non-empty eligible subsets; preserve transaction signatures,
  input/footprint checks, the signed common deadline, profile isolation, reservations and replay. Keep certified-fast certificate validation on its
  separate source path. Cover neither, one, and both consensus stages having non-empty lock subsets in every supported execution mode, with no
  fabricated fast artifacts or lifecycle promotion to `fastCertified`.
- **P3.2 — Ordered atomic execution:** run the exact producer/consumer consecutively against the correct working state with plan-bound witnesses;
  consumer failure rejects the candidate and exposes no partial committed output or applied entry. Preserve bounded reservations after local
  rejection until a canonical terminal condition occurs.
  A deterministic rejected reducer result must retain its complete authenticated access trace. Persist its bounded reservation before returning
  the candidate rejection, without publishing a vote or calling the finalized application preparation path. Missing or invalid authentication
  evidence cannot be promoted into a verified rejected-execution capability.
- **P3.3 — Historical ancestor proof:** wire a public authenticated canonical-history/proof hook into consumer validation and default branch
  assembly. Support a producer older than the current finalized tip and the existing certified, not-yet-finalized ancestor route. Validate chain,
  profile, signed pipeline, output, deadline, and candidate ancestry; reject forks, unrelated producers, and unavailable/invalid proof data.
- **P3.4 — Exact execution gate:** verify adjacent and non-adjacent descendants, finalized-tip advancement and restart/backfill, same-bytes retry,
  application at the inclusive deadline followed by later finality, expired rejection, scope isolation, and reducer rollback through public APIs.
  Both consensus modes must succeed without an application effect quorum certificate; the certified-ancestor case instead verifies the producer's
  block certification and ancestry. Missing required lock/fast-execution certificates on certified-fast sources remain negative cases.

### Phase 4: Automatic HotStuff V2 And Empty-Block Progress

- **P4.1 — Proposal assembly:** select header/plan rules from the historical activation context, derive and retain the plan preimage, and build V2
  roots through ordinary proposal input and runtime assembly. Users must not need to replace the default proposal builder to obtain conformance.
- **P4.2 — Validation and application:** fetch and authenticate every required plan/source/witness before voting; verify root/body membership,
  ordering, certificate presence, exact pre-state roots, footprint conformance, results, and activation rules. Wire application, finalization,
  historical replay, and embedder materialization interfaces without conflating inclusion, finality, and durable application materialization.
  Reject source/declaration substitutions, uncertified fast sources, compatibility mixed with other entries, and forbidden conflict-free overlap
  across wave boundaries; verify classification statements and their required witnesses against each entry's actual pre-state root.
- **P4.3 — Empty progress:** construct and validate canonical empty V2 blocks; continue ordinary consensus rounds to finalize pending work and
  satisfy an explicit maintenance finalized-height target even after the last transaction-bearing anchor is finalized. During drain, the target
  must exceed every relevant live signed deadline and any required legacy unrecorded-vote expiry horizon. Bound progress attempts and retain live
  locks/reservations when reporting a stall. Local clocks, attempts, and proposed heights cannot authorize expiry. Quiesce once the required target
  is met.
- **P4.4 — Four-validator gate:** use four independent node runtimes, identities, stores, and transport with real 3-of-4 signatures. Prove automatic
  V2 execution and identical roots, conflicting-vote rejection, missing/malformed preimage rejection before voting, empty-block finality, stalled
  finality without expiry, restart, and transaction-free drain progress. Include split honest votes and one Byzantine signer voting both ways:
  no honest validator may equivocate while its claim is live, and two conflicting 3-of-4 certificates must be impossible. A four-key certificate
  assembled in one runtime is not this gate.

### Phase 5: Version Activation, Drain, Migration, And Recovery

- **P5.1 — Supported transition matrix:** implement height/configuration-bound historical dispatch for each supported header, plan, protocol,
  signature, and schema combination. Preserve old certified/unfinalized blocks, highQC/lockedQC and voter watermarks through an authenticated
  future-boundary handover with an old-quorum fence and valid continuation. Verify boundary-minus-one, boundary, and boundary-plus-one behavior,
  delayed old votes/QCs, finality proofs spanning the boundary, restart, mixed-version rejection and old replay. Reject a boundary that would
  relabel already signed old-profile evidence or manufacture a suffix-free state by discarding existing or unverified consensus history.
  Distinguish the finalized drain checkpoint from the certified continuation parent; replay and verify retained old suffix transitions to obtain
  the parent's state root, and rebuild/revalidate prepared opening output if the selected parent changes.
  Also implement both eligible initial V2 bootstrap cases: no prior domain, and fenced retired domains proven non-ancestors of the selected
  source lineage. Verify the snapshot's provenance/schema, prior rollback/source-selection evidence where applicable, the present-content or
  accepted historical-absence inventory and fence evidence, and
  complete state-data closure, bind the fresh chain/domain and ordered validator set to the local genesis bundle, and install the inherited root
  as the initial anchor. Use the frozen genesis/initial-justify rules and execute signed opening conversion with an empty lock subset and `None`
  only in the first ordinary descendant. A lock-bearing bootstrap opening is unsupported even if a certificate is supplied.
  Dispatch the initial anchor by its authenticated genesis profile and keep source history in its original chain/configuration context; its heights
  do not form a historical pre-activation range of the new chain.
  Do not fabricate a finalized-anchor proof or assume that existing validator-set trust-root/coordinator APIs already implement state import.
- **P5.2 — Pre-activation drain:** close old-profile admissions and vote issuance at an authenticated boundary, inventory every lock/reservation
  domain, and reconcile all nonterminal exact pipelines. Classify each deployment by retained evidence: complete journal deadline coverage,
  unrecorded-deadline risk requiring an inferred horizon, or proven never-enabled voting. Exact-only history qualifies for the first route only
  when descriptor registration, admission/journal reconciliation and pre-issuance lock/effect binding cover every relevant deadline across the
  historical signer/quorum scope, without bypass or lost history. Generic effect validation's recorded same-deadline lock also covers that deadline;
  neither case proves that individual vote subjects were recorded.
  For initial bootstrap, verify source ancestry and any retired-domain preservation/fencing independently from these application-voting routes,
  and fence the source write authority before selecting the inherited snapshot. Non-empty read-only retired consensus archives do not fail the
  zero-live barrier by themselves. Inventory application claims in every relevant source/retired domain: unresolved claims still need original-rule
  drain or separately verified recovery, and cannot be made expired by archival classification, a domain fence or new-chain finality.
  Source and new-chain height are separate coordinates.
  The historical-absence archive route requires independently proven never-enabled application voting in every affected missing-history domain;
  an absence attestation itself cannot establish that fact or make unknown claims resolved. Other domains retain their normal drain obligations.
  Verify P0.2's authenticated binary/configuration/key-use inventory for the complete historical signer/deployment scope. If no admission/safety
  store was created or its absence meets the historical-absence contract, this inventory must independently establish never-enabled voting without
  an empty-store observation. Artifact identity and effective configuration must prove unavailable or continuously disabled issuance paths, rather
  than relying on a release label or the currently deployed version. Reject uncovered intervals, unknown or enabled issuance paths, unaccounted
  custom builds/signing integrations and conflicting surviving records; a never-enabled claim cannot replace required drain for enabled voting.
  For example, a complete inventory bound to unmodified pre-`0.3.0-M1` release artifacts can establish that the Sigilaris application lock/effect
  APIs were unavailable, provided integration and key-use evidence also excludes custom or alternate issuance.
  For deadlines lacking complete coverage, justify `B_stop` from old base validation and signer fencing for all still-certifiable subjects,
  calculate `D_stop` with checked arithmetic, and finalize strictly beyond both `D_stop` and the greatest recorded deadline. For complete journal
  coverage, finalize strictly beyond the reconciled recorded horizon. Reconcile surviving admission/safety stores and require zero live state,
  including for never-enabled deployments; an eligible no-store case uses the independent inventory proof and fabricates no empty safety history.
  Physical counts or a `Ready` flag alone cannot establish drain. If old empty blocks cannot support progress, require a separately specified
  old-profile-compatible drain/bridge path; do not validate new empty plans early. Missing
  deadline-coverage evidence, an unenforced fence, an unproven bound or an unsupported progress path rejects in-place activation without fabricating
  history.
  Test an old quorum completed after closure from pre-closure honest votes and a later Byzantine signature: keep its deadline and historical
  interpretation, and deny new application after finalized height passes the conservative horizon.
- **P5.3 — Recoverable switch:** back up the complete stopped-node consistency group, including application/block/consensus state, safety journal,
  exact admission/idempotency and generic pipeline records, and active configuration. Validate the drain evidence and zero-live-state barrier,
  prepare migration through explicit schema rules, and atomically publish or recover one consistent activation decision before listeners or votes
  reopen. Bind the known parent/height boundary without introducing the resulting block's own id into its preimage. Inject crashes before/after
  each durable step. Never resume old writers against migrated state or silently reinterpret historical votes and certificates.
  Commit any application conversion through the signed compatibility-singleton opening transaction and matching body result defined in P0.2;
  precomputed conversion output remains inactive until validated at its authenticated parent/height boundary. Storage-schema conversion alone
  does not authorize an uncommitted application state-root change.
  A failed preparation may discard only inactive migration output; preserve every handover/fence signature and consensus safety update since
  backup even when the local activation switch has not happened. Continuing the old profile must obey the published boundary/fences or complete
  a verified cancellation/reverse transition, not erase promises other validators may already rely on.
  Initial bootstrap durably binds the selected bundle, verified installed source state and initialized consensus/safety records before any
  bootstrap-QC signature, proposal or vote. Retry/restart must resume that same identity; another snapshot/root/chain/set cannot replace a decision
  whose promise has already been published.
  Keep available retired roots read-only outside active migration namespaces. Bind verified content digests or accepted absence-attestation digests
  with distinct evidence kinds, their supporting records and signing/retirement fences into backup and activation recovery. Startup guard replacement
  verifies this fixed classification; it cannot delete, rewrite or adopt an archived root as active, or replace expected-present loss with an
  absence attestation. Source-state data, new initialization records and current-transition signing history must remain complete.
- **P5.4 — Upgrade gate and runbook:** demonstrate a supported live-state drain/switch/restart and reject live safety state, stalled, incompatible,
  incomplete-backup, and partially migrated cases. Publish restoration/resumption prerequisites and the application integration boundary; do not
  allow rollback merely because no new block finalized or the local switch has not occurred. Every abort/restore retains transition handover and
  fencing evidence plus post-backup consensus progress. After the switch, whole-group rollback requires proof that admissions, signers and listeners
  remained fenced and no externally valid evidence, voting-safety or canonical application write would be lost. If no eligible whole-group restore
  preserves those records, use forward recovery or a separately specified safe drain and reverse transition. Disaster restore
  must preserve anti-equivocation key/epoch/domain fences and cannot roll back voter watermarks through a history-loss acknowledgment.
  On four independent validators, verify both initial-bootstrap eligibility cases from non-empty inherited state, identical genesis/root, signed
  opening, ordinary empty descendants and actual finality. Reject wrong/untrusted source data, incomplete closure, schema/chain/set/manifest
  mismatch, old validator-artifact replay, related/unclassified history, invented rollback authority, loss/tampering of expected-present archives or bound evidence, unfenced retired
  writers and reused target domains. Check the embedder's explicit user-signature/replay policy separately. Inject installation/initial-signing
  crashes, loss of required archive/evidence/fences and attempts to change the bundle on restart. A failed opening retains its signed-deadline reservation until the
  permitted canonical terminal condition; an unavailable boundary-compatible progress/recovery path blocks activation.
  Include successful bootstrap with wholly/partly pre-baseline absent retired archives, complete source state, independent never-enabled and
  rollback/inventory evidence, authenticated absence records and persistent fences. Reject bare attestations, digest-kind confusion, source-state
  loss and post-baseline losses disguised through new evidence or a restart. Non-empty bootstrap opening fails regardless of certificate presence;
  empty-subset/`None` bootstrap succeeds, while handover retains its ordinary lock-bearing cases.

### Phase 6: Public Conformance And Release Readiness

- **P6.1 — Exported contract:** publish canonical preimages/bytes/roots/signatures and positive/negative vectors for old/new formats, separate
  commitments, lock-optional consensus, exact modes, deadlines, ancestry, reservations, empty blocks, and activation. Publish matching integration,
  compatibility, and recovery guides in exported paths.
- **P6.2 — Standalone artifacts:** run public neutral consumers against all five selected-version Maven coordinates with no parent build or source
  dependency. JVM exercises public runtime/storage/consensus paths; Scala.js verifies the shared protocol/vector surface in both normal and fully
  optimized output. Pin and document JavaScript runtime dependencies. Keep candidate staging verification distinct from clean public-Maven
  verification after a separately executed release.
- **P6.3 — Final source and runtime gate:** run affected suites, `scalafmtCheckAll`, all five module test targets, and the actual four-validator E2E
  gate against the final source revision. Record the commands, counts, failures resolved, and exact artifact/fixture identity; repeat only when
  intervening changes or unresolved findings require it.
- **P6.4 — Readiness review:** verify the public export contains executable fixtures and all referenced guides, map every acceptance criterion to
  passing evidence, and document unresolved operational/security work. Mark this plan complete only when implementation and its required evidence
  are complete. This release-readiness plan requires source, exported-consumer, and candidate staged-artifact checks; post-publication public-Maven
  verification remains an explicitly pending release gate until it is performed. Public availability and deployment readiness require their own
  completed publication and security/operations gates.

## Test Plan

| Area | Required cases and observable result |
| --- | --- |
| Three commitment domains | With lock-eligible mutation `A`, consensus-only mutation `B`, and read `C`, full inputs contain all three, lock subset is `{A}`, and reservation covers writes `{A,B}` plus read `{C}`. Add absent creation key `D` only to the relevant footprint; swapping one commitment for another fails. |
| Plan entry commitments | Source kind/identity, certificate references, declaration and classification statement are committed. Substituting consensus/fast or exact/compatibility sources, mixing a compatibility singleton with another entry, unused/missing/alternate classification statements, and forbidden cross-wave conflict-free overlap fail. Valid ordered overlap succeeds with entry-local Exact and witness checks. |
| Exact and compatibility access | Undeclared exact access, stale reads/writes, wrong authority proof, duplicate stable identity, and uncovered lock-eligible compatibility mutation fail. Compatibility scans and creation targets participate in reciprocal reservation conflicts. |
| Optional lock certificate | Consensus-only and create-only stages use `None` and retain reservation/expiry behavior. Both exact consensus modes succeed without certified-fast effect certificates; producer block QCs/ancestry remain required where applicable. `None` for non-empty lock inputs, `Some` for empty inputs, wrong subsets, missing fast-source certificates, and lock-free certified-fast admission all fail. |
| Vote and reservation safety | Each honest validator rejects a second incompatible live lock/effect subject across restart. Initial votes may split across honest validators, but two conflicting quorums cannot form with at most the tolerated Byzantine participation. Concurrent readers and authorized ordered consensus overlaps succeed; resolving one owner retains every other live claim and its protection against external fast conflicts. |
| Canonical application recovery | Crashes between result/state, applied-index, terminal journal, and activation writes never expose partial success or reopen applied inputs. Same-submission retry remains idempotent and unrelated ownership cannot be overwritten. |
| Exact producer/consumer | Both modes cover supported lock-presence combinations and reject wrong output/profile/pipeline, outside consumers, fresh-output reuse, and undeclared chains. `OrderedAtomic` consumer failure leaves no partial producer/consumer application. `CertifiedAncestor` consumer failure rejects the consumer candidate while preserving any already canonical producer state. |
| Historical ancestry | A producer finalized several blocks before the current finalized tip remains consumable by a proven descendant within the deadline; stale branch, wrong chain, missing proof, restart, and history backfill have explicit results. |
| Version and empty-block rules | Old vectors retain identical bytes/roots/results. New empty plan is `waves = []` with empty body; empty waves, unexpected members, wrong roots, unsupported profile, and pre-activation use fail. An empty block's application root stays unchanged; a root-changing system action requires an explicit source/result in a non-empty plan. |
| Opening transaction | A signed, authorized application transaction executes as the sole `ConsensusTransaction` compatibility-singleton entry from the continuation parent's state. Verify deterministic conversion, actual access/reservation coverage and optional lock-certificate rules; reject missing/wrong authorization, unsigned or new system sources, additional entries, uncovered lock-eligible mutations and hidden conversion writes. |
| Opening deadline | The signed envelope and every reservation share `lastInclusionHeight`. Bootstrap requires an independently derived empty lock subset and `None`; non-empty bootstrap subsets fail regardless of supplied certificates, and omitted lock-eligible mutations fail. Handover retains normal lock/finalized-base cases. Verify authenticated base/lifetime bounds, inclusive inclusion and same-domain finalized-height/nonapplication expiry; retain claims on abandonment or blocked progress. |
| Opening reservation capacity | Freeze witness/index representation and authenticated count/byte/chunk limits. Verify boundary and over-limit cases, complete actual-access coverage, missing/duplicate/tampered chunks, witness/claim/index crash recovery and identical reciprocal conflicts after restart. Local capacity failure holds activation; chunking preserves one canonical opening and owner-scoped protection. |
| Automatic consensus | Four runtimes use ordinary proposal assembly, distributed votes, preimage availability, transport, storage, and finalization; committed roots agree. Missing or invalid preimages cannot produce an honest vote. |
| Deadline and idle drain | Apply at the inclusive deadline and finalize later without expiry; no expiry at equality or while finality stalls. Empty rounds reach a drain target above live deadlines, then quiesce. A bounded attempt limit reports a stall without releasing claims. |
| Legacy deadline coverage | Exact registration and reconciled admission/journal state cover all bound subjects; a generic effect's deadline is covered by its recorded lock. A direct pre-certificate lock vote, unbound exact lock subject or lost voting history without independent never-enabled proof requires the inferred-horizon route. Test all three evidenced drain routes and admission/journal crashes. Never-enabled cases cover surviving empty stores and eligible no-store domains with complete authenticated binary/configuration/key-use inventories. Reject inventory gaps/tampering, enabled or unknown issuance paths, unaccounted custom builds/integrations, contradictory surviving state and release-label/store-absence-only assertions. A watermark never passes the durable vote-subject gate, and unsupported exact-only claims cannot bypass the inferred horizon. |
| Upgrade recovery | Fence issuance and prove the deadline horizon of every still-certifiable old subject; an old certificate assembled after closure retains that bound. Replay retained old suffix writes from drain checkpoint to continuation parent, and revalidate on parent change. Preserve consensus locks, handover and fencing evidence across pre-switch failures, delayed QCs, cross-boundary finality proofs and restart. Unknown bounds, unsafe fences/boundaries or lost safety records reject activation/restore even without a new block. |
| Initial V2 bootstrap | Four validators exercise no-prior-domain and retired-non-ancestor bootstrap, including present archives and independently verified historical absence with authenticated attestations and never-enabled evidence. Import a complete source root into fresh domains and execute lock-free opening plus empty descendants to real finality. Reject false lineage/rollback claims, bare attestations, digest-kind confusion, source-data loss, peer-selected roots, validator-artifact replay, related/unclassified history, loss/tampering of expected-present or bound evidence, unfenced writers and reused domains. Restart cannot replace the evidence baseline; verify user signatures under the embedder's policy. |
| Public reproducibility | Before plan completion, an exported standalone consumer verifies all five candidate staging artifacts and vectors on JVM and both Scala.js outputs without private paths or parent-source dependencies. After publication, repeat with a fresh cache and only public Maven resolution, excluding Maven Local; record that separate release gate's checksums and actual outcomes. |

Use deterministic barriers and fault injection for storage/race tests; use actual consensus messages and votes for four-validator integration.
Relevant source suites may remain private under repository policy, but the advertised public contract must have independently runnable exported
conformance coverage. No public guide should require access to an internal suite to establish its result.

The final source gate includes `sbt scalafmtCheckAll` and the `coreJVM/test`, `coreJS/test`, `nodeCommonJVM/test`, `nodeCommonJS/test`, and
`nodeJvm/test` targets. The standalone build must cover:

- `org.sigilaris:sigilaris-core_3:<selected-version>`;
- `org.sigilaris:sigilaris-core_sjs1_3:<selected-version>`;
- `org.sigilaris:sigilaris-node-common_3:<selected-version>`;
- `org.sigilaris:sigilaris-node-common_sjs1_3:<selected-version>`; and
- `org.sigilaris:sigilaris-node-jvm_3:<selected-version>`.

Record exact run/full-link commands and runtime dependencies in the fixture README after its API/build shape is frozen. Staged artifact checks
cannot be described as public-Maven checks; after publication, repeat the public consumer with a fresh cache and only the public resolver.

## Risks And Mitigations

- **Changing a digest under an existing version would corrupt history:** preserve immutable vectors and select explicit new versions/domains;
  test historical replay and every supported activation boundary.
- **An incomplete reservation permits a conflicting vote:** derive/verify complete reads and writes independently of lock inputs and test both
  interlock directions, including scans and absent keys.
- **A single-owner reservation blocks legal work or drops another reader's protection:** represent all live claims and their scope/deadlines;
  test concurrent readers, authorized ordered overlap and per-owner terminal cleanup while preserving external fast-lock interlocks.
- **Opening footprints exceed the store's practical capacity:** freeze a complete witness/index representation and authenticated resource limits,
  test realistic size and recovery cost, and preflight local capacity. A compact digest cannot replace exact overlap evidence; chunking cannot
  weaken completeness, signing durability or the single-transaction opening boundary.
- **Separate validation and signing races across threads or restart:** expose one durable voting operation, persist before signing, and make
  failure/retry states explicit rather than releasing a claim on a local error.
- **Empty-plan support alone does not drain an idle node:** add a finalized-height maintenance target to the normal finality driver, verify
  actual four-validator progress, and retain all claims on bounded-attempt failure or stalled finality.
- **Upgrade can depend circularly on new validity rules:** prove an old-profile-compatible drain/bridge path before activation and explicitly
  reject unsupported deployment histories.
- **Finalized-height activation can relabel a signed old suffix:** preserve certified branches and consensus safety state; authenticate a future
  boundary, old-quorum fence and continuation, and test delayed artifacts plus finality proofs that span the transition. Replay suffix writes from
  the drain checkpoint to the selected continuation parent's state before preparing the opening transition.
- **Initial bootstrap can be confused with resetting consensus history:** classify continuing ancestry, no prior domain and retired non-ancestor
  domains explicitly. Require trusted source/rollback provenance, preserved available evidence, authenticated prior-absence records where eligible
  and persistent retirement fences; reject attempts to excuse new losses or erase continuing history. An absence attestation is not ancestry or
  never-enabled proof. A fresh validator domain does not itself change the embedder's user-signature domain or expire old claims.
- **Legacy vote subjects and deadlines have different recording boundaries:** prove complete journal deadline coverage, including exact lock-plan
  binding and admission/journal reconciliation, or enforce authenticated old vote closure and infer a conservative expiry horizon for missing
  deadlines. Wait beyond the applicable horizon and every greater recorded deadline. Refuse automatic activation when neither that evidence nor
  proven never-enabled voting is available; neither synthetic records, an exact-only label nor empty-store observations supply the missing proof.
  Delayed assembly of a bounded certificate from earlier honest votes is possible and does not invalidate the fence or extend its deadline.
- **A finalized-tip shortcut rejects valid historical consumers:** verify authenticated canonical ancestry with retained/backfilled proof data;
  fail closed when it is unavailable instead of substituting tip equality or untrusted ancestry hints.
- **Application state and safety journals may use different stores:** define the public recoverable application/checkpoint boundary and test
  interrupted integration; an internal safety-store transaction alone does not establish whole-application atomicity.
- **Snapshot rollback can forget promises before the local switch or any new block:** preserve handover/fence signatures and consensus progress
  through every abort/restore; inactive migration output may be discarded without discarding safety history. Keep admission/signing/listeners
  fenced for eligible rollback and require proof that no external evidence or safety write would be lost. Otherwise recover forward or perform a
  separately validated cancellation/domain drain/reverse transition, preserving anti-equivocation fences during disaster recovery.
- **Published APIs and operational hardening touch common stores:** inventory abstract methods and consumers before changes, coordinate with plan
  0032, and publish complete source migration instructions.
- **Public export can omit the only useful verification:** place public vectors, guides, and consumers outside private-only directories, and
  verify an exported-tree build with public artifact resolution.
- **Protocol conformance can be mistaken for overall deployment safety:** retain explicit dependency-security and operational follow-ups and
  report their status separately from this plan's functional evidence.

## Acceptance Criteria

1. A public, reviewed compatibility matrix fixes all selected wire, signature, commitment, manifest, header, and schema versions. Published
   M1/M2 fixtures retain their original bytes, hashes, and historical validation behavior.
2. Independent descriptor derivation produces the correct full vector, lock subset, and complete footprint for mixed authority/access and
   creation/compatibility cases. Versioned plan entries bind source, certificate, declaration and classification semantics; validators reject
   substitutions, incomplete coverage, compatibility mixing and cross-wave conflict-free bypasses.
3. The public voting operation durably claims inputs before signing/publication and prevents each honest validator from signing incompatible
   live subjects across races, storage faults and restart. Split honest votes are allowed, but two conflicting quorums cannot form within the
   Byzantine bound. Reservations permit authorized overlap and retain each live owner's protection until its own terminal outcome.
   Opening footprint representation and protocol limits are frozen; complete witnesses and conflict indexes recover consistently at supported
   scale. Missing coverage or excessive protocol size cannot obtain a vote, and local capacity failure cannot silently change validity rules.
4. `ConsensusAdmission` with an empty lock subset works through admission, both supported exact modes, proposal, application, expiry, and replay
   using no lock certificate. Consensus sources do not require an application certified-fast effect quorum. Certified-fast empty subsets,
   missing fast-source certificates and inconsistent input-lock certificate presence are rejected.
5. Historical finalized producers older than the current finalized tip remain eligible in proven descendants within the signed deadline;
   incorrect or unavailable proof data cannot authorize execution.
6. Ordinary HotStuff assembly constructs activated V2 headers and validates complete execution-plan/source/witness preimages before voting.
   Source kind, declaration, classification and entry pre-state witnesses are authenticated. Application, finalization, replay and materialization
   integration preserve their documented boundaries.
7. Activated empty blocks have canonical empty plan/body roots and their parent's application state root; empty waves, hidden application
   transitions, and pre-activation use are rejected. Actual four-validator finality and idle drain progress reach their target and remain safe
   under stalls/restart. Opening conversion is a signed, authorized compatibility-singleton consensus transaction with a matching body result and
   complete actual access/reservation coverage; it adds no system source or exception to input-lock eligibility. Its signed deadline bounds every
   reservation even without locks; inclusive application and same-domain finalized-height/nonapplication expiry apply without abandonment unlock.
   Initial-bootstrap opening requires an empty derived lock subset and `None`; non-empty bootstrap opening is unsupported, while handover follows
   the ordinary finalized-base/certificate rules.
8. A supported deployment completes old-profile drain, backup, explicit migration, and recoverable version activation. Unsupported progress
   paths, missing evidence for the selected deadline-coverage/fencing route, and live safety/partial/incompatible state fail closed. Retained journal
   coverage, an inferred finalized-height horizon or proven never-enabled voting establishes the drain prerequisite; exact-only claims require
   pre-issuance subject/plan binding and reconciliation evidence. None substitutes for durable vote-subject safety in the new profile.
   Never-enabled evidence covers the complete historical binary/configuration/key-use inventory and reconciles any surviving admission/safety
   stores; an eligible no-store domain uses that independent proof without inventing empty state. Missing or contradictory evidence fails closed.
   The handover preserves the certified suffix, consensus locks and voter watermarks, validates cross-boundary finality proofs and replays old suffix
   writes to the continuation parent's state. Historical dispatch covers each
   transition range, including delayed assembly of bounded old certificates. Any rollback that loses handover/fence evidence or later safety
   writes is refused, even before the local switch or a new finalized block; recovery preserves anti-equivocation fences.
   A source without HotStuff ancestry can instead bootstrap a fresh V2 chain either without prior domains or with proven retired non-ancestor
   domains. Verify rollback/source provenance, present-content inventory or the accepted pre-baseline absence evidence and continued fencing.
   The absence route requires independent eligibility/never-enabled evidence and an authenticated attestation bound before preparation/signing;
   it cannot excuse missing source data, unresolved claims or later loss of promised content/evidence. All validators agree on
   genesis/root and execute opening as its first ordinary descendant; source fences, fresh validator domains, initial-signing safety and restart
   identity are verified. Non-empty retired archives remain preserved, unresolved old application claims remain subject to their original rules,
   and an empty directory alone cannot authorize bootstrap. User-transaction domain/replay behavior remains an explicit embedder contract.
9. Canonical application, applied-index, and terminal lock/reservation state recover without partial visibility or conflicting replay across
   every injected durable-boundary interruption, including the documented public embedder integration boundary.
10. Public golden/compatibility/recovery guides and independently runnable neutral JVM/Scala.js consumers cover the advertised contract using
    all five selected-version Maven coordinates; exported artifacts/fixtures match recorded identity and no private inputs are required.
11. Relevant suites, required formatting, all five source test targets, and actual four-validator E2E pass against the final reviewed revision.
    Staging, later public-Maven checks, unresolved plan 0032 work, and dependency-security status are reported accurately and separately.

## Checklist

### Phase 0: Contract, Version, And Public Baseline Freeze

- [x] P0.1: Inventory compatibility surfaces, the replaced v1 lock derivation, and immutable historical fixtures.
- [x] P0.2: Freeze versions/APIs, opening deadline/witness/capacity, bootstrap eligibility/absence-attestation contracts, failures and milestone.
- [x] P0.3: Reproduce the audit baseline using all five public M2 artifacts and maintained neutral fixtures.
- [x] P0.4: Complete the requirement-to-evidence map and pass the contract/public-baseline gate.

### Phase 1: Core Input, Lock, Footprint, And Plan Contracts

- [x] P1.1: Implement independently derived and verified input, lock, and footprint commitments.
- [x] P1.2: Implement source/declaration/classification entries, signed compatibility opening, optional consensus locks, and fast certificates.
- [x] P1.3: Implement activated empty plans and preserve historical validation/hash dispatch.
- [x] P1.4: Publish vectors and pass the JVM/Scala.js core conformance gate.

### Phase 2: Durable Voting And Complete Reservations

- [x] P2.1: Implement durable atomic voting with fault, retry, signer, and restart behavior.
- [x] P2.2: Implement complete multi-owner witness/index reservations, capacity limits, allowed overlap, cleanup and compatibility interlocks.
- [x] P2.3: Implement and document the recoverable application/index/terminal-state boundary.
- [x] P2.4: Pass reciprocal-interlock, concurrent-vote, failure-injection, and recovery gates.

### Phase 3: Exact Consensus Modes And Historical Ancestry

- [x] P3.1: Support both exact consensus modes without fast effect certificates, preserving required input locks and the separate fast-source path.
- [x] P3.2: Implement ordered atomic execution and full rejection without partial committed state.
- [x] P3.3: Integrate authenticated historical ancestry and explicit proof-unavailability behavior.
- [x] P3.4: Pass exact execution, ancestry, deadline, retry, restart, and rollback gates.

### Phase 4: Automatic HotStuff V2 And Empty-Block Progress

- [x] P4.1: Wire activated V2 headers and plan preimages into ordinary proposal assembly.
- [x] P4.2: Integrate pre-vote validation, application, finalization, replay, and materialization interfaces.
- [x] P4.3: Implement canonical empty blocks and bounded idle drain progress to an explicit finalized-height target.
- [x] P4.4: Pass the actual four-validator V2, preimage, conflict, finality, idle drain, and restart gate.

### Phase 5: Version Activation, Drain, Migration, And Recovery

- [x] P5.1: Implement historical dispatch, both initial-bootstrap eligibility cases, and suffix-preserving handover/fencing/proofs.
- [x] P5.2: Prove the journal-covered, inferred-horizon or never-enabled drain route, vote fencing and progress; reject unsupported upgrades.
- [x] P5.3: Implement backup, zero-live-state checks, schema migration, and a recoverable activation switch.
- [x] P5.4: Pass initial-bootstrap and handover recovery cases on independent validators and publish the supported-deployment runbook.

### Phase 6: Public Conformance And Release Readiness

- [x] P6.1: Publish exported golden vectors and integration, compatibility, and recovery guides.
- [x] P6.2: Pass standalone five-coordinate JVM/Scala.js artifact conformance; distinguish staging from later public-Maven evidence.
- [x] P6.3: Pass and record final formatting, source suites, and actual four-validator E2E results.
- [x] P6.4: Review public export and every acceptance criterion; record unresolved work and the remaining publication/deployment gates.

## Documentation Review

On `2026-09-08`, ADR-0037 and this plan underwent three review rounds, with separate reviews of transition safety and implementation/evidence
consistency. Findings were corrected before the next round.

| Round | Findings and resolution |
| --- | --- |
| 1 | Five findings corrected: preserve the certified/unfinalized consensus suffix during handover; commit plan source/declaration/classification semantics; provide consensus execution without fast effect certificates; support multiple reservation owners and permitted overlap; distinguish per-validator vote safety from permitted split honest votes. |
| 2 | Two findings corrected: preserve published handover/fence evidence through pre-switch abort and recovery; allow delayed assembly of bounded old certificates without extending their deadlines. Also clarified drain-checkpoint versus continuation-parent state, suffix replay and prepared-output invalidation. |
| 3 | Both independent reviews reported **No finding** after the corrections. |

At that revision, document checks passed for all seven changed Markdown files, 45 added relative links and their public-export targets, whitespace, and matching
seven-phase/28-item deliverable and unchecked-checklist structure. The new documents require no private application repository or temporary path.
These are document-review results only: no implementation phase is complete and no runtime test, staged-artifact check, release or activation
validation was performed by this review.

On `2026-09-09`, a follow-up code review led to four refinements: specify the signed compatibility-singleton opening source and Phase 0
authorization/codec hooks; record that M2 locks both `MutableRead` and `MutableWrite` identities and needs a replacement lock-derivation contract;
distinguish journal deadline coverage from missing vote-subject records when selecting the drain route; and remove unreproducible temporary-test
counts while requiring named maintained baseline fixtures. The phase deliverables, tests, acceptance criteria and checklists were updated together.

Independent reviews of opening-contract consistency and vote/deadline recording boundaries both reported **No finding**. The same document link,
public-export, whitespace and phase/checklist checks passed again. This follow-up also changed documentation only and supplies no implementation,
runtime, artifact-publication or deployment-activation result.

A second follow-up on `2026-09-09` added authenticated initial V2 bootstrap from inherited state as an explicit supported implementation target,
separate from existing-HotStuff handover. It also added the Phase 0 opening witness/index representation and resource-limit decision, restored
the ADR's Date-only convention and clarified system/checkpoint transaction terminology. Independent review found two boundary-description issues:
historical profiles below an activation boundary and source-to-parent suffix replay must apply to existing-chain handover, while initial genesis
uses its authenticated bundle and keeps imported history in a separate chain context. Both were corrected and re-reviewed with **No finding**.
The independent opening representation/capacity review also reported **No finding**.

The final document checks passed for seven changed Markdown files and 50 added relative links with public-export-safe targets; seven phases and
28 matching unchecked deliverables remain. These are documentation and code-inspection results, not evidence that bootstrap, migration or any
implementation/release gate has been completed.

A third follow-up on `2026-09-09` distinguished continuing HotStuff ancestry, absence of prior domains and preserved/fenced retired non-ancestor
domains. Conditional bootstrap now requires prior rollback/source provenance and read-only archive/fence evidence, while every relevant application
claim remains subject to its original drain rules. It also made the opening's signed deadline mandatory without locks, restricted the initial-anchor
base rule to lock-free bootstrap opening, and separated validator-artifact signing domains from embedder-owned user signatures and replay policy.
Two independent code/document reviews reported **No finding**. Document links, public-export targets, whitespace and the 28-item checklist passed;
these results do not establish deployment eligibility or complete an implementation/activation gate.

A fourth follow-up on `2026-09-10` added conditional bootstrap for retired non-ancestor domains whose raw archives were already unavailable
before the transition evidence baseline. Eligibility requires independent rollback/source provenance, deployment inventories, application-voting
never-enabled evidence and enforceable retirement fences; an authenticated archive-absence attestation records the missing scope and binds it to
the bootstrap bundle without replacing those proofs. Present archive content and prior absence use distinct evidence kinds, and later evidence
loss cannot be reclassified by a replacement attestation or baseline. Bootstrap opening now explicitly requires an empty derived lock subset and
`inputLockCertificateId = None`; lock-bearing bootstrap opening is unsupported, while handover retains its ordinary lock requirements.
Two independent document reviews reported **No finding** after these changes. The decisions, phase deliverables, tests and acceptance criteria
remain aligned; no deployment eligibility, implementation, runtime-test or activation result is established by this documentation review.

A fifth follow-up on `2026-09-10` restricted the never-enabled route's empty-state checks to surviving admission/safety stores. Domains with no
store ever created or with accepted historical absence instead require a separately authenticated, complete binary/configuration/key-use
inventory proving unavailable or continuously disabled application vote issuance. The contract, evidence tests and acceptance criteria reject
gaps, unknown or enabled paths, unaccounted custom integrations and contradictory records without fabricating empty safety history. The pre-M1
artifact example was checked against local release notes and tagged source, and an independent document re-review reported **No finding**.
All 97 relative links and 50 added public-export targets, whitespace and the matching 28-item checklist passed. This follow-up changes
documentation only and establishes no actual deployment's eligibility or runtime/activation result.

## Follow-Ups

Plan 0032 continues to own observer failure isolation, idempotency-alias failure contracts, reconstruction concurrency, and corruption diagnostics.
Journal retention/checkpoint policy, namespace consolidation, event-driven wait APIs, and general pipeline extensions remain separate unless a
specific prerequisite is explicitly moved into a reviewed revision of this plan.

The M2 release notes' Scala.js crypto dependency remediation and broader dependency-advisory review remain independent release/deployment work.
Completing this conformance plan does not establish production signing safety. The next artifact version and subsequent tag/publication workflow
must use new immutable release identities and attach the applicable functional, artifact, security, and operational evidence.

The subsequent [M3 crypto security record](../conformance/m3-crypto-security-2026-09-13.md) tracks signing/provider/TLS dependency remediation,
fresh consumer evidence and the bounded assessment of the remaining elliptic Low advisory. [Plan 0035](0035-scala-js-build-dependency-security-plan.md)
owns the remaining Scala.js build-tool dependency work. Historical M1/M2 release identities and this plan's earlier gate records remain unchanged.
