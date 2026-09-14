# Runtime Compatibility Inventory For Plan 0033

This inventory records source observations at revision `efe4f4529a1c266e024ea8be74b3c7ad58a08847` on 2026-09-11.
It complements [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md) and
[ADR-0037](../adr/0037-versioned-application-execution-upgrade-and-empty-blocks.md).
Source inspection is not artifact-conformance, deployment eligibility, or an executed upgrade result. Proposed contracts below identify
the additive boundaries required by later phases; their implementation and maintained public fixtures must be reviewed separately.

## Published Runtime Boundaries

| Surface | Observed contract | Required additive boundary |
| --- | --- | --- |
| `ApplicationSafetyStore.transact` | Atomically updates one `ApplicationSafetySnapshot`; public `snapshot`, `journal`, and `recover` complete the store interface. | A new snapshot/journal schema for vote intents, complete reservation witnesses and multiple owners. Retain the old API and decoder for historical consumers. |
| `ApplicationSafetyRuntime.validateLockVote` | Reads a snapshot, checks the active manifest, supplied base/deadline, nonempty canonical input ids and conflicts with recorded locks. It does not mutate the store or invoke a signer. | Public persist-before-sign lock voting; a complete subject and every reciprocal reservation conflict must be checked and durably recorded in one transaction. |
| `recordLockCertificate` | Authenticates a certificate and records its canonical lock descriptor under each input identity; advances greatest admitted deadline. | Certificate import must reconcile identical or conflicting prior intents, and cannot remove an intent following signer failure. |
| `reserveProposal` | Accepts a lock subject; requires every reservation identity to have an identical live lock descriptor/deadline. Stores one record per identity. | Independent complete read/write footprint, authenticated scope and owner deadline; lock subset equality is not the new reservation contract. |
| `validateEffectVote`, `validateExactEffectVote` | Read-only checks. Both require a recorded live lock with matching context and deadline plus reciprocal reservations. Exact validation additionally checks the registered plan digest, execution membership and deadline. | Durable complete effect-subject intent for certified-fast voting; consensus execution uses its own proposal/result path. |
| `recordApplication`, `replayApplication` | Require an authenticated fast effect certificate; update applied/terminal safety state. | Separate validated consensus application record and a recoverable embedder state/result/index boundary. |
| `recordExactApplicationsAtomically` | Authenticates all effect certificates, then updates safety records and exact lifecycle in one safety-store transaction. | Consensus exact results without fast effect certificates; state/result persistence must participate in the same recovery decision. |
| `closeAdmission`, `advanceDrain`, `beginActivation`, `activateManifest` | Local drain phase, recorded watermark and live counts gate a manifest configuration rotation. Finalized height is supplied by the caller. | Authenticated same-domain finality/nonapplication evidence, historical signer fence and deadline coverage, whole-group activation and recovery. |

Source: [ApplicationSafetyStore.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyStore.scala)
and [ApplicationSafetyRuntime.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyRuntime.scala),
especially `inspect`, `validateAdmission`, `validateEffect`, `validateReciprocalLocks`, `validateAllReciprocalReservations`, and `activateManifest`.

`ApplicationSafetyRuntime` currently needs `Monad`, so the existing interface does not itself express a cancellation-safe storage/signing
boundary. A new public voting runtime should own the complete effect sequence with the stronger effect capabilities its implementation requires.
The signer must receive only the canonical preimage associated with the already committed intent. An embedding application that separately
calls a validator then signs outside this boundary does not obtain the new anti-equivocation guarantee.

## Subject Durability And Deadline Coverage Are Different

| Operation | Complete individual pre-sign subject persisted? | Deadline persistence/coverage | Important limitation |
| --- | --- | --- | --- |
| Generic `validateLockVote` | No | None from this operation | The caller supplies `baseHeight`; a successful validation is not a certificate or a durable claim. |
| `recordLockCertificate` | Stores the certified lock descriptor; does not establish prior individual vote intent history | Advances journal watermark to the certificate deadline | Later certificate import cannot prove that all earlier votes were journaled. |
| Generic `validateEffectVote` | No | Requires a recorded live lock with the same deadline | Covered expiry horizon does not establish effect-subject anti-equivocation. |
| `ExactPipelineAdmissionService.submit` | No | Persists the verified descriptor in the exact admission store | Admission service alone does not write the application safety journal. |
| `ExactPipelineTransportService.submit` | No | Reconciles the admitted descriptor into the safety journal before successful transport return | It does not own every historical signing integration. |
| `ExactPipelineExecutionRuntime.register` / `registerExactPipeline` | No | Journals descriptor and greatest deadline | Generic lock voting still lacks exact execution/domain/deadline-to-plan enforcement. |
| `validateExactEffectVote` | No | Requires journaled plan digest/execution/deadline plus same-deadline live lock | A covered exact deadline does not mean an individual effect vote was persisted. |
| `ExactPipelineStartupGate.reconcile` | No | Restores missing admission-to-journal registration and rejects missing/changed admitted descriptors | Reconciliation cannot reconstruct a vote or deadline absent from both histories. |

Sources: [ExactPipelineAdmission.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineAdmission.scala),
[ExactPipelineTransportService.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineTransportService.scala),
and [ExactPipelineExecutionRuntime.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineExecutionRuntime.scala).

`journalAdmittedExactPipeline` deliberately accepts an already persisted admission after admission closes, so a close/store-write interruption
does not orphan an accepted descriptor. Startup must reconcile that boundary before assessing drain. This does not authorize new post-fence
signatures or supply the deployment-wide fence required by ADR-0037.

## Historical Formats, Domains And Storage

| Format | Current identity and meaning | Compatibility constraint |
| --- | --- | --- |
| Application lock/reservation/applied/terminal/drain rows | Each has `schemaVersion = 1` | Keep their decoder and validation behavior; do not reinterpret a reservation vector as a full independent footprint. |
| Application journal | `ApplicationJournalEntry.SchemaVersion = 1`, sequence, operation, delta, `Prepared`/`Committed` | Retain immutable historical entries. New vote/witness/activation records need a new schema. |
| Exact record and snapshot | `schemaVersion = 2` | Exact pipeline schema 2 is already used by M2; a replacement cannot reuse 2 for a different layout or lifecycle contract. |
| Application manifest | Manifest version 1, `ApplicationSubstrateVersions.M1 = (2,1,1,1,2,1)` | Tuple is header, plan, lock, effect, exact codec, journal. This manifest validator rejects another tuple. |
| Lock vote and certificate | `sigilaris.application.lock.vote.v1`, `sigilaris.application.lock.certificate.v1` | Subject binds protocol/configuration/epoch/set, execution, dependency digest, deadline and canonical input ids. Preserve original preimages and signature interpretation. |
| Effect vote and certificate | `sigilaris.application.effect.vote.v1`, `sigilaris.application.effect.certificate.v1` | Subject additionally binds normalized result digest and state root; new source/footprint semantics cannot silently change its historical meaning. |
| Exact commitments | `sigilaris.tx-pipeline.exact.plan.v1`, `.identity-binding.v1`, `.reference.v1` | Retain exact plan/reference/identity binding hashes; verify affected preimages before retaining any domain for new use. |
| Manifest commitment | `sigilaris.application.manifest.v1` | Preserve old manifest digest and publish an explicit new manifest domain when adding profile, signing-domain or limit fields. |
| HotStuff artifact domains | `sigilaris.hotstuff.proposal.sign.v1`, `.vote.sign.v1`, `.proposal.id.v1`, `.vote.id.v1`, `.validator-set.v1` | Existing signatures commit the chain id and set context. Fresh target domain requirements and any newly signed transition artifacts need explicit validation and version dispatch. |
| Header snapshot encoding | Separate legacy header encoding and tagged V2 header encoding | Existing V2 header layout can retain its version only with identical bytes and hashing; new plan grammar needs independent version selection. |
| Historical proposal archive | Archive envelope schema byte `0x01`; archived proposal keeps its original header/artifacts | An archive is proof material, not automatic canonical ancestry or a reusable active signer state. |

Sources: [ApplicationSafetyModels.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/application/ApplicationSafetyModels.scala),
[ExactPipelineModels.scala](../../modules/node-common/shared/src/main/scala/org/sigilaris/node/txpipeline/ExactPipelineModels.scala),
[Certification.scala](../../modules/core/shared/src/main/scala/org/sigilaris/core/application/protocol/Certification.scala),
[Artifacts.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Artifacts.scala),
[SnapshotCodecs.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/SnapshotCodecs.scala), and
[Materialization.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Materialization.scala).

The SwayDB application store uses a single database with `locks/`, `reservations/`, `applied/`, `terminal/`, `exact-pipelines/`, `journal/`,
and `control/drain` namespaces. Its physical keyspace validator rejects unknown keys. Values use Circe JSON; journal keys are twenty-digit
sequence strings. `transact` serializes changes through a semaphore, writes a prepared delta, writes projections, then commits the journal.
A caught write error sets `recoveryRequired`, preventing another mutation until recovery. This observation does not establish cancellation safety.
`ApplicationStoreFaultPoint` exposes boundaries
after prepared, each projection group, control and commit. These are useful test injection points; the safety database is not a cross-store
transaction with the application's canonical state, block data, exact admission/idempotency store or generic pipeline store.

Source: [SwayDbApplicationSafetyStore.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/storage/swaydb/SwayDbApplicationSafetyStore.scala).

`repairLegacyDrainState` can increase a missing/low watermark from retained lock and nonterminal exact rows and demote a live `Ready` state.
Its operation only repairs facts recoverable from retained rows. It cannot invent pre-certificate vote subjects, unknown deadlines or deleted
history. New-schema migrations must preserve that distinction and place new active data outside the old decoder's namespace contract.

## Reservation And Application Contract To Add

The selected representation should contain one canonical full read/write witness per footprint commitment, chunked only for storage and
transport; authoritative owner claims reference that witness. A derived exact identity-to-owner index supports conflict checks without copying
the entire footprint into every identity row. The full witness and all owner references survive until their applicable terminal/retention
conditions are met. Index recovery must verify all committed chunks and reconstruct the same overlap decisions before voting resumes.

Each new claim must bind its execution, signed source/dependency context, plan/proposal scope, read/write mode, complete footprint commitment,
same-domain signed deadline and terminal condition. Multiple readers coexist. Ordered consensus overlaps require authenticated ordering scope;
they do not waive Exact freshness or a conflicting external certified-fast lock. Resolving one owner removes only that owner's active claim.
The shared identity index cannot encode a single exclusive reservation merely because several valid executions touch the same identity.

Authenticated protocol limits must cover identity count, total canonical bytes, chunk size/count and canonical chunk ordering. Local capacity
checks are separate: protocol oversize rejects before voting; insufficient local capacity holds execution/activation without changing validity.
A digest with missing witness bytes is unavailable evidence, never proof of disjointness. A compatibility opening remains one atomic transaction
even if its witness spans chunks.

The additive voting, witness and recoverable application interfaces are selected in the
[V2 contract](v2-contract.md) and [runtime schema](v2-runtime-schema.md).
Their transaction payload must include the complete subject, owner claims and witness references. The
application commit hook prepares inactive canonical state/result output, durably binds it to the applied/terminal decision and recovers that
same decision before public observation. A returned computed `nextState` by itself does not establish durable application materialization.

Identical voting retries may reuse the same persisted intent; an incompatible subject, footprint, deadline or result fails. Signer failure,
cancellation after durable preparation and ambiguous storage success retain the intent. Recovery resolves ambiguous storage before signing;
certificate import must compare with all retained intents. Typed failures must distinguish conflicting intent, missing/corrupt witness,
incomplete index, unavailable recovery, protocol resource excess and local capacity shortage.

## Exact Consensus And Historical Ancestry

`executeOrderedAtomic` currently accepts a vector of `CertifiedEffectCertificate`, validates reducer execution results against those
certificates, and calls the certified application safety boundary. `recordCertifiedAncestorProducer`,
`validateCertifiedAncestorConsumerVote`, and `recordCertifiedAncestorConsumer` are likewise tied to effect certificates/subjects and live locks.
The existing lifecycle passes through `LockCertified` and `EffectCertified`; skipping only a lock check cannot implement consensus admission.

New consensus-specific methods must accept independently validated signed source/result records, require a lock certificate exactly when the
eligible subset is nonempty, retain full reservations with an empty subset, and never promote consensus work to fast certification. Both
`OrderedAtomic` and `CertifiedAncestor` must support neither/one/both stages carrying nonempty subsets. The ordered reducer works against a
private working state and publishes a complete pair only after success. A consumer rejection preserves any previously canonical ancestor
producer and every still-live reservation until a valid canonical terminal condition.

The current consumer checks `branchContext.containsCertifiedBlock(producerBlock)` or equality with `bestFinalizedBlockId`.
`HotStuffProposalInputBranchContext.fromParent` stops collecting ancestors at the current finalized anchor. Consequently an older finalized
producer is not represented in that default branch context even when it is a valid canonical ancestor. The `complete` flag means complete
to that anchor, not complete historical lineage.

Source: [ProposalInput.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ProposalInput.scala)
and [ExactPipelineExecutionRuntime.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/txpipeline/ExactPipelineExecutionRuntime.scala).

Add a public authenticated ancestor lookup returning `Available(VerifiedAncestorProof)`, `Unavailable` or `Invalid` for an explicit chain, historical profile,
producer block/execution/output and candidate parent. It must verify parent linkage and relevant historical block certification through the
trusted finalized anchor, retaining/backfilling older proof material as necessary. Existing `HistoricalProposalArchive`,
`HistoricalBackfillService` and `HistoricalBackfillWorker` provide storage/retrieval building blocks; an archive membership hit alone is not
ancestry proof. Missing data must retain claims and reject the candidate with a distinct unavailable reason.

## Automatic Proposal And Finality Hooks

`HotStuffProposalInput.blockHeader` calls the legacy `BlockHeader` constructor with no plan root. The provider result holds parent, height,
state/body roots, timestamp and transaction set, but no application plan preimage. Existing validation providers remain useful application
hooks, while the default activated runtime needs shared profile dispatch and preimage availability checks before proposal voting.

Add an authenticated `HotStuffApplicationProfileSchedule` and plan/source/witness repository to ordinary proposal assembly, local/remote voting,
application, replay and bootstrap verification. Historical header V1 to V2 and header V2/plan V1 to header V2/new-plan transitions are distinct
schedule cases. Empty new-profile plans have zero waves, zero application body entries and the parent's application state root; every present
wave remains nonempty. A missing or wrong preimage/root cannot fall back to legacy acceptance.

`HotStuffFinalityDriveCandidate.fromBranch` chooses retained ancestors only when they contain transactions and limits descendant depth.
It therefore does not express a finalized-height maintenance target after the final transaction-bearing anchor disappears from that range.
Add a retained same-domain finalized-height target with bounded attempts and explicit stalled/quiesced results. Only authenticated finality
above the deadline authorizes expiry; local attempt/time counters are liveness diagnostics.

Sources: [ApplicationProposalValidation.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/ApplicationProposalValidation.scala)
and [FinalityDrive.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/FinalityDrive.scala).

## Activation And Initial Bootstrap

Existing `BootstrapTrustRoot` variants authenticate validator sets/windows. `FinalizedAnchorSuggestion` carries a real `FinalizedProof`, and
the bootstrap coordinator consumes that authenticated HotStuff history. Those contracts do not verify arbitrary inherited application state
or create an initial chain from a source without HotStuff ancestry.

Sources: [Bootstrap.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/Bootstrap.scala),
[BootstrapCoordinator.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/BootstrapCoordinator.scala), and
[HotStuffRuntimeBootstrap.scala](../../modules/node-jvm/src/main/scala/org/sigilaris/node/jvm/runtime/consensus/hotstuff/HotStuffRuntimeBootstrap.scala).

Add a separately authenticated initial bundle, source provenance/schema/state-closure verifier and recoverable initial-state installer.
The bundle binds the unchanged source root, fresh validator signing domain, new chain, ordered validator set, genesis/initial-justify rules,
activated manifests and the source-to-genesis linkage. The durable startup identity is committed before any bootstrap-QC, proposal or vote
signature. Restart resumes that identity and all issued promises; it cannot choose another source root. Genesis `G` is an initial condition,
not a fabricated finalized proof.

The signed opening envelope binds known continuation parent and height, activation/manifests, authorization, classification reason and common
deadline, without requiring its own resulting block id. A maintenance-authority verifier and deterministic conversion hook validate it as
the sole consensus compatibility entry. Bootstrap opening must independently derive an empty eligible lock subset and `None`; complete actual
read/write reservations remain mandatory. Handover opening retains normal authenticated finalized-base and lock-certificate rules.

Continuing HotStuff ancestry requires a future authenticated handover boundary compatible with retained certified/unfinalized evidence,
old-quorum fencing and valid continuation. Preserve high/locked QC information, voter watermarks, published fence/handover signatures and
subsequent consensus progress through every abort/restart. Replay old-profile suffix state from finalized drain checkpoint `F` to continuation
parent `P`; a changed `P` invalidates prepared opening output. The inventory does not claim that the existing default runtime already durably
provides all those handover records.

For no-ancestry sources, distinguish no prior domain from retired non-ancestor domains. Retired domains require independent rollback/source
selection, lineage and fence evidence. Preserve present archives read-only with content digests. The narrow historical-absence alternative
uses a distinct authenticated absence-attestation digest and independent never-enabled/inventory proof; it cannot replace missing source
state, explain post-baseline loss or erase current-transition signatures. Bound archive classifications cannot change on restart.

One recoverable activation decision must bind the complete stopped-node consistency group: application state, block/consensus state, safety
journal, exact admission/idempotency, generic pipeline and configuration, plus every retired evidence/fence record. Individual namespace
restore, absence of new finalized blocks, or local switch status cannot prove a rollback is eligible. Preserve every external promise and
later safety/canonical write or recover forward.

## Feasible Legacy Drain Evidence Routes

No inspected source proves a particular deployment's old vote fence, historical integration coverage or valid old-profile progress route.
Automated in-place activation therefore starts unavailable until an independently verified evidence bundle establishes one of these cases:

| Route | Required evidence | Required expiry observation |
| --- | --- | --- |
| Complete journal deadline coverage | Retained exact descriptors/admission-journal reconciliation and signing integrations prove every old lock/effect subject's execution, domain and deadline was durably bounded before issuance, without bypass or history loss; authenticated historical signer/quorum closure. | Original-domain finalized height strictly greater than the greatest reconciled deadline, followed by zero live locks/reservations/nonterminal exact work. |
| Inferred horizon for unrecorded deadlines | Authenticated old issuance fence and admission-base bound `B_stop` cover every still-certifiable subject; old maximum lifetime and all greater retained deadlines. | Checked `D_stop = B_stop + oldMaxLockLifetimeBlocks`; original-domain finality strictly above both that horizon and every greater recorded deadline, then reconciled zero-live state. |
| Proven never-enabled application voting | Authenticated full-lifetime binary digests, effective configurations/intervals, authorized key scope and every signing path show unavailable or continuously disabled lock/effect issuance. Verify custom builds and alternate integrations; reconcile surviving stores. | No invented deadline or empty history. Eligible no-store cases rely on the complete independent proof and accepted absence classification, not store absence. |

`ApplicationBaseHeightProvider.current` and generic lock validation provide plain heights, not authenticated base proofs. A local tip, clock,
`Ready` state, empty database or an "exact-only" configuration label cannot justify `B_stop`. Two pre-closure honest votes and a later Byzantine
vote may still assemble a bounded 3-of-4 old certificate; the verified horizon must cover that unchanged signed deadline.

Every enabled historical domain must also demonstrate finality progress under its original validity rules. New empty plans cannot be enabled
early to make that proof possible. A compatible old-profile bridge may be supplied and independently tested; absent that route, stalled or
unproven domains remain unsupported. New-chain height and retired-archive classification never expire old-domain application claims.

## Required Evidence Follow-Through

Public maintained fixtures must independently reproduce the observed boundaries before any phase claims to fix them: conflicting unrecorded
lock validation, generic/exact effect deadline coverage, admission/journal interruption, strict legacy reservation equality, exact empty-lock
and effect-certificate requirements, historical finalized producer exclusion, default V1 header assembly and old empty-plan rejection.
New conformance fixtures then verify concurrency, signer/storage faults, same-intent restart, multiple-owner cleanup, witness recovery,
consensus exact execution, historical ancestry, activated default proposals and four independent validators' real finality/drain behavior.

The legacy source inventory does not itself make those fixtures pass. Later phase evidence must record exact commands, source/artifact identity,
actual outcomes, public export paths and unresolved gates; passing safety-store tests alone cannot claim whole-application or deployment safety.
