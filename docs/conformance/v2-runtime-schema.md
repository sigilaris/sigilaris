# V2 Runtime, Evidence And Recovery Schema

This document freezes the runtime-side encodings and public boundaries selected by the [V2 contract](v2-contract.md).
It is a specification for implementation and executable vectors, not evidence that a deployment or implementation satisfies it.
The [runtime inventory](runtime-compatibility-inventory.md) records the historical APIs which remain readable.
Core input, plan and manifest encodings use the companion [core schema](v2-core-schema.md).

## Primitive Encoding And Shared Context

Products concatenate fields in the exact order listed below. No implicit Scala enum/product discriminator is included.
Named versions are values encoded in their specified field, not a second wrapper. The following primitives use existing canonical encoders:

| Symbol | Exact encoding and allowed value |
| --- | --- |
| `Tag` | One unsigned byte; only the listed discriminants are accepted. |
| `I64` | Eight bytes, big endian, existing `ByteEncoder[Long]`; all numeric fields here must be nonnegative unless stated otherwise. |
| `Nat` | Existing canonical `BigNat` encoding; alternate length forms and leading-zero magnitudes are rejected. Heights remain unbounded natural values; protocol arithmetic checks the declared lifetime without narrowing to `Long`. |
| `H32` | Exactly 32 bytes, existing `UInt256` big-endian encoding. |
| `Bytes` | Canonical `Nat` byte length followed by exactly that many bytes. |
| `Text` | `Bytes` of valid UTF-8; no trimming or Unicode rewriting during decode. Chain ids additionally satisfy the existing canonical `ChainId.parse` grammar. |
| `Vec[T]` | Canonical `Nat` element count, then the encodings of each element in order. |
| `Opt[T]` | One byte `00` for none, or `01` followed by `T`; other markers reject. |

`DomainContext` is the core product `(protocolVersion:I64, chainId:Text, configurationDigest:H32, epoch:I64, validatorSetHash:H32)`.
New application work requires protocol version 2 and an activated complete tuple. The same canonical `chainId` is the sole validator
signing-domain discriminator in both application and HotStuff contexts. There is no independently selectable duplicate `signingDomain` field.
Initial bootstrap requires a previously unused target chain id and verified target signer history; ordinary recovery continues that exact id.
User transaction signature domains remain the embedder's separately authenticated manifest/replay policy.

Existing HotStuff proposal, vote, timeout-vote, new-view, QC, identity and signature preimages/domains remain byte-for-byte unchanged.
They already bind `chainId`; the fresh chain id therefore separates validator promises without introducing a new field into old artifacts.
New application subjects encode `DomainContext` once. Existing application protocol 1 subject codecs retain their original layout.

`H(domain,payload)` means the existing `ApplicationProtocolHash` framing: Keccak-256 of `Text(domain) ++ Bytes(payload)`.
All new record digests below use this framing; the existing HotStuff signatures and ids continue using their existing framing.
The named `SignatureEnvelope` product is `(authorityId:Text, algorithm:Tag, publicKey:Bytes, signature:Bytes)` with `algorithm=1` for the existing
secp256k1 signature codec. Trusted-key lookup must match the declared authority and public key. A peer-supplied key never establishes trust.
`publicKey` has exactly 64 bytes (`x || y`), and `signature` has exactly 72 bytes (`v:I64 || r:H32 || s:H32`), matching the existing HotStuff
signature encoding. Validate the curve point, recovery id, scalar ranges and low-S normalization. The signature verifies the Keccak-256 digest
of the canonical domain-framed signing preimage below, matching `HotStuffCanonicalEncoding.sign` without adding another hash layer.
All newly signed evidence has exactly one envelope per declared authority; quorum evidence instead has unique signers sorted by canonical id.

## Vote, Claim And Witness Records

Core lock/effect subjects use the exact core schema. Runtime records retain canonical subject bytes, not just a digest.
Digests are recomputed on decode and verified before indexing. In the following products `Owner`, `Scope`, and `WitnessRef` are defined below.

| Product | Ordered fields |
| --- | --- |
| `VoteIntent` | `schema:I64=2, context:DomainContext, validatorId:Text, kind:Tag, executionId:H32, subject:Bytes, subjectDigest:H32, owner:Opt[Owner], witness:Opt[WitnessRef], lastInclusionHeight:Nat` |
| `LiveLockClaim` | `schema:I64=2, context:DomainContext, executionId:H32, subjectDigest:H32, inputIds:Vec[Bytes], lastInclusionHeight:Nat, lifecycle:Tag, terminal:Opt[TerminalResolution]` |
| `ConsensusVoteIntent` | `schema:I64=2, context:DomainContext, validatorId:Text, unsignedVoteSignBytes:Bytes, proposalId:H32, targetBlockId:H32, planRoot:H32, bodyRoot:H32, validatedStateRoot:H32, ownerDigests:Vec[H32]` |
| `Owner` | `context:DomainContext, executionId:H32, scope:Scope` |
| `Scope` | `kind:Tag, parentBlockId:H32, candidateHeight:Nat, planRoot:H32, entryIndex:I64, authorizationDigest:H32` |
| `ReservationClaim` | `schema:I64=2, owner:Owner, witness:WitnessRef, lastInclusionHeight:Nat, lifecycle:Tag, terminal:Opt[TerminalResolution]` |
| `WitnessRef` | `format:I64=1, witnessDigest:H32, identityCount:I64, encodedBytes:I64, chunkCount:I64` |
| `TerminalResolution` | `kind:Tag, evidenceDigest:H32, resolvedHeight:Nat, applicationBatchDigest:Opt[H32]` |
| `ConflictIndexRow` | `schema:I64=2, identity:Bytes, owners:Vec[IndexedOwner]` |
| `IndexedOwner` | `ownerDigest:H32, claimDigest:H32, mode:Tag, lastInclusionHeight:Nat` |

Intent kinds are `Lock=1` and `Effect=2`. A lock intent has `owner=None` and `witness=None`: it durably claims only the independently verified
nonempty eligible lock subset through `LiveLockClaim`, checks all existing complete reservations reciprocally, and does not acquire all reads
or consensus-only writes before execution. An effect intent requires its matching complete proposal reservation owner/witness and result.
The separate `ConsensusVoteIntent` stores the exact existing HotStuff unsigned-vote signing bytes and validated proposal commitments.
Its owner digests are in plan-entry order and include every full proposal reservation; an empty block has an empty owner vector and no invented
execution or reservation. Its digest uses `sigilaris.application.consensus-vote-intent.v2`. The vote window/validator/target must match its
context, signer and proposal; a prepared consensus vote atomically persists this intent together with every entry's reservation claim.
Scope kinds are `FastAdmission=1`, `ConsensusOrdered=2`,
`ConsensusConflictFree=3`, and `CompatibilitySingleton=4`. For `FastAdmission`, candidate parent/height/plan/index are canonical zero values
and `authorizationDigest` binds the authenticated admission-base evidence; it never grants ordered-consensus overlap.
For consensus scopes, authorization commits the candidate plan, entry and verified branch/order proof.
Consensus intents are not application effect votes or fast certificates.

Claim lifecycle tags are `Live=1`, `Applied=2`, `ExpiredUnapplied=3`; terminal is absent exactly for live claims. Resolution tags are
`Applied=1`, `ExpiredUnapplied=2`. Applied resolution requires the matching batch digest; expiry forbids it and requires authenticated
same-domain canonical finality strictly beyond the signed deadline plus nonapplication proof. Owner resolution never deletes another owner.
Effect and lock subjects, source declarations and any present owner/witness must agree on context, execution and deadline.
Every retained owner scope, eligible lock claim and vote intent for the same context/execution preserves that one signed deadline, including
terminal history. A new scope or lifecycle transition cannot shorten, extend or reset it.
`LiveLockClaim.inputIds` are the exact nonempty canonical core eligible subset, never the full footprint. Its digest uses
`sigilaris.application.live-lock.v2`; the same terminal rules apply to its lifecycle.

Access tags are `Read=1`, `Write=2`; identities are nonempty and at most 256 bytes. Accesses sort lexicographically by raw identity bytes;
duplicates reject, and a read/write of the same identity is represented once as write. Index owners sort by owner digest, with duplicates rejected.
`witnessDigest = H("sigilaris.application.reservation.witness.v1", ReservationWitness)`.
Claim/owner/intent digests respectively use `sigilaris.application.reservation.claim.v2`,
`sigilaris.application.reservation.owner.v2`, and `sigilaris.application.vote-intent.v2`; subject digests use the selected core subject domain.
`H("sigilaris.application.reservation.chunk.v1", WitnessChunk)` authenticates an individual transported chunk.
`ReservationWitness`, `WitnessEntry`, `WitnessAccess` and `WitnessChunk` reuse the exact core schema and codec; there is no duplicate runtime
encoding. In particular, witness fields are `(format:I64=1, entries:Vec[WitnessEntry])`, each entry is `(identity:Bytes, access:Tag)`, and
chunk fields are `(format:I64=1, witnessDigest:H32, index:I64, count:I64, bytes:Bytes)`. `count` equals `WitnessRef.chunkCount`.

Witness byte chunks are consecutive canonical slices of exactly 65,536 bytes except the last, which is nonempty and at most that size.
`chunkCount = ceil(encodedBytes / 65,536)`. The empty witness still encodes its eight-byte format and zero element count, so its encoded length
is nine bytes and it has **one chunk**, not zero. Reassembly and canonical decode must reproduce the advertised digest, identity count and length.
The manifest limits are 100,000 identities, 16,777,216 bytes and 256 chunks per transaction, applied together. Any inconsistent count,
out-of-range index, missing/duplicate/noncanonical chunk or coverage omission rejects before signing. Local storage/memory shortage returns
`CapacityUnavailable`, retaining claims/fences; it does not redefine those protocol limits.

The witness is authoritative once its reservation claim is prepared; the index is a derived projection. Preparing a proposal/effect intent
atomically verifies the whole witness and actual access coverage, publishes its complete owner claims, updates the exact index and records
the subject. Preparing a lock intent instead publishes only its eligible `LiveLockClaim` and checks existing reservations. An index cannot
declare two owners disjoint merely because a chunk is missing. Recovery verifies witnesses and rebuilds complete owner coverage before voting.
Terminal history and any still-referenced witness remain available under the declared retention policy.

## Journal And Recoverable Application

New active namespaces begin with `application-v2/`; old application safety namespaces and schema 1 history remain immutable evidence.
The selected subspaces are `witness/`, `chunks/`, `locks/`, `claims/`, `intents/`, `consensus-intents/`, `certificates/`, `index/`, `batches/`, `applied/`, `terminal/`, `journal/`,
`activation/`, `bootstrap/`, and `control/`. Keys use lowercase digest hex except journal keys, which use twenty decimal digits.
Exact schema 3 admission/idempotency data remain a separately inventoried consistency-group member, never inferred from generic pipelines.
The safety journal's exact mirrors use `exact-registrations/` and `exact-records/`, keyed by the digest of the canonical domain and node pipeline id.

| Product | Ordered fields |
| --- | --- |
| `JournalRecord` | `schema:I64=2, sequence:I64, operation:Tag, previousDigest:H32, payload:Bytes, payloadDigest:H32, status:Tag` |
| `JournalPayload` | `format:I64=2, intents:Vec[VoteIntent], locks:Vec[LiveLockClaim], consensusIntent:Opt[ConsensusVoteIntent], claims:Vec[ReservationClaim], witnesses:Vec[WitnessRef], applicationPreparation:Opt[PreparedApplication], applicationDecision:Opt[ApplicationDecision], terminalResolutions:Vec[TerminalResolution], activationPreparation:Opt[ActivationPreparation], activationDecision:Opt[ActivationDecision], bootstrapStartup:Opt[BootstrapStartupRecord], fences:Vec[SignedFencePromise], rebuiltIndexDigest:Opt[H32], importedCertificates:Vec[ImportedCertificate], bootstrapVoteIntent:Opt[BootstrapVoteIntent], exactRegistration:Opt[ExactRegistration], exactRecordUpdates:Vec[ExactRecordUpdate]` |
| `ApplicationBatch` | `format:I64=2, context:DomainContext, parentBlockId:H32, candidateHeight:Nat, priorStateRoot:H32, nextStateRoot:H32, planRoot:H32, bodyRoot:H32, entries:Vec[AppliedEntry], statePayloadDigest:H32` |
| `AppliedEntry` | `executionId:H32, resultDigest:H32, normalizedResult:Bytes, ownerDigest:H32, lastInclusionHeight:Nat` |
| `PreparedApplication` | `schema:I64=2, batchDigest:H32, batch:ApplicationBatch, preparedStateInventory:H32, preparedAtSequence:I64` |
| `ApplicationDecision` | `schema:I64=2, batchDigest:H32, blockId:H32, decisionSequence:I64, terminalOwners:Vec[H32]` |
| `AppliedIndexRecord` | `schema:I64=2, context:DomainContext, executionId:H32, batchDigest:H32, blockId:H32, candidateHeight:Nat, resultDigest:H32` |
| `ExactRegistration` | `schema:I64=2, context:DomainContext, nodePipelineId:Text, canonicalRecord:Bytes, bindingDigest:H32, requestDigest:H32, lastInclusionHeight:Nat` |
| `ExactRecordUpdate` | `schema:I64=2, context:DomainContext, nodePipelineId:Text, bindingDigest:H32, priorRecordDigest:H32, canonicalNextRecord:Bytes, evidenceDigest:H32` |

Operations are `VoteIntent=1`, `Reservation=2`, `ApplicationPrepare=3`, `ApplicationCommit=4`, `Expiry=5`, `ActivationPrepare=6`,
`ActivationCommit=7`, `BootstrapBind=8`, `Fence=9`, `IndexRebuild=10`, `CertificateImport=11`, and `BootstrapVote=12`.
`ExactRegistration=13` and `ExactLifecycle=14` complete the operation tags.
Status is `Prepared=1` or `Committed=2`. `ImportedCertificate` is a tagged product: `Tag(1) ++ LockCertificate` or
`Tag(2) ++ EffectCertificate`, using the exact core canonical codecs. Imported vectors sort by `(tag, core certificate id)`.
Payload is always the complete `JournalPayload` product; its digest uses `sigilaris.application.journal.payload.v2`.
All fields not allowed by the operation table below are canonically empty/`None`; allowed vectors contain unique rows in key order, except
application entries and consensus owners which preserve plan order. The empty recovered journal has sequence zero and zero digest; the first
record has sequence one and a zero previous digest. Subsequent sequences increase by exactly one with checked `Long` arithmetic and refer to
the prior committed record digest. Recorded `preparedAtSequence` and `decisionSequence` are positive and equal their enclosing journal sequence.
No unrelated operation may interleave while a prepared operation has an unresolved outcome. A commit marker finalizes the same prepared payload
and sequence. The committed record digest uses `sigilaris.application.journal.record.v2` including `Committed` status.

Journal vectors use these explicit keys: intents by intent digest; locks by canonical context bytes then execution id; claims by owner digest;
witness references by witness digest; imported certificates by tag then certificate id; signed fences by signed evidence digest; exact updates
by canonical context bytes then canonical UTF-8 node pipeline id. Terminal resolutions sort uniquely by their canonical bytes; multiple owners
resolved by one batch may reference the same resolution row. Consensus owner vectors and application entries retain plan order.
`ApplicationDecision.terminalOwners` contains the selected batch owners in plan order, followed by every additional live owner of those same
executions sorted by owner digest; the complete vector is duplicate-free. Its exact closure is derived from retained claims at commitment.
These keys clarify canonical ordering without introducing an additional discriminator or changing any product field.

Runtime snapshots are in-process projections of authoritative records and the journal, not an additional independently encoded safety schema.
The derived conflict-index inventory is `H("sigilaris.application.conflict-index.v2", Vec[ConflictIndexRow])`, with rows sorted uniquely by raw
identity bytes. A recovered safety inventory uses `H("sigilaris.application.safety.inventory.v2", committedJournalDigest ++ indexDigest)`;
the initial journal digest is the zero hash. Recovery verifies every referenced authoritative witness before returning these inventory digests.
An inventory hash alone never establishes witness availability or a storage durability barrier.

| Operation | Permitted populated fields and required relation |
| --- | --- |
| `VoteIntent` | Either exactly one lock intent with matching `locks` and no claims/witnesses, exactly one effect intent with complete existing/matching claims/witnesses, or one `consensusIntent` with all proposal claims/witnesses. These three alternatives are mutually exclusive. Matching `exactRecordUpdates` may project newly reserved stages; a lock vote alone cannot mark a quorum certificate present. |
| `Reservation` | Complete `claims` and matching `witnesses`; nonempty claims, verified against current live locks and ordering scope. Matching `exactRecordUpdates` may project Reserved stages. |
| `ApplicationPrepare` | `applicationPreparation`, optionally with complete matching `claims` and `witnesses` for every selected batch owner. If present, the claims have exact owner/execution/deadline/parent/height/plan/index membership and the witnesses cover those claims without unused references. Complete inactive state/result and canonical finality evidence already exist and verify before the new application claims become authoritative. |
| `ApplicationCommit` | Exactly `applicationDecision`, matching terminal `locks`/`claims`, `terminalResolutions`, and required `exactRecordUpdates` for affected registered exact stages. Every resolved execution is in the prepared batch; additional owner scopes require the retained claim's same-execution binding. All live owners and eligible locks for those executions resolve together, and every other execution remains protected. |
| `Expiry` | Terminal `locks`/`claims`, `terminalResolutions` and required `exactRecordUpdates` for affected registered exact stages; at least one resolution or exact update is present, and every resolved claim/stage has verified same-domain finality/nonapplication evidence. Lock-free unreserved admitted stages may expire through an exact update with no invented lock/reservation. |
| `ActivationPrepare` | Exactly `activationPreparation`. |
| `ActivationCommit` | Exactly `activationDecision`, matching the retained preparation. |
| `BootstrapBind` | Exactly `bootstrapStartup`; used for all monotonic startup-phase updates and initial-vote intent references. |
| `Fence` | Nonempty `fences`, verified and persisted before taking effect externally. |
| `IndexRebuild` | Exactly `rebuiltIndexDigest`, computed from all authoritative live lock/owner/witness records. |
| `CertificateImport` | Nonempty independently authenticated `importedCertificates` plus exact matching `locks` for imported lock certificates and optional matching `exactRecordUpdates`. Full signed-source and quorum verification permits conservative archival alongside another execution's minority local claims; all prior subjects, witnesses and deadlines remain immutable. Creates no local vote intent or reservation; an effect certificate alone is retained evidence. |
| `BootstrapVote` | Exactly `bootstrapVoteIntent` and the matching monotonic `bootstrapStartup` update referencing it; source/state/safety installation and evidence verification already succeeded. |
| `ExactRegistration` | Exactly `exactRegistration`; complete verified schema-3 admission/binding/common deadline, including an admission with two empty eligible subsets. |
| `ExactLifecycle` | Nonempty `exactRecordUpdates` only. Evidence-backed Reserved/Failed and LockCertified projections may refer to an already durable matching complete consensus intent or certificate; no new claim or certificate is created by this operation. Finalized/Materialized projections preserve existing canonical application facts. An identical-record update is permitted solely for the canonical alias evidence defined in the exact schema. First application and expiry use their owning atomic operations above. |

The ApplicationPrepare allowance above is an unpublished P0 semantic amendment found during P2 review; it changes no encoded product field,
tag or domain. A validator's individual unfinished lock vote cannot prevent it from materializing a different execution already authenticated
as canonical by ordinary finality. Only the private verified-finalized-batch boundary may publish these selected application claims despite
conflicting local claims. Its preparation inventory binds the complete retained finality, candidate/parent, state and result evidence, and the
same atomic journal payload binds every new selected owner and complete witness. Recovery authenticates that preparation and evidence before
accepting the overlap or reopening the store. A bare QC, caller assertion, detached in-memory permission or ordinary proposal reservation does
not grant the application exception. New lock/effect/proposal voting retains all reciprocal interlocks. Other executions' claims and signed deadlines
remain unchanged; canonical application resolves only the applied executions, and any conflicting unapplied execution requires its own
authenticated nonapplication expiry. A crash cannot retain exception claims while losing the preparation that justifies them.

Certificate archival has a separate, strictly narrower permission: a verified lock/effect quorum and its complete authenticated signed
source may be retained even where an unrelated minority local claim overlaps. The imported lock adds protection; it never replaces that
minority claim, authorizes a local vote, fabricates an owner, or relaxes a future acquisition check. The closed CertificateImport payload
cannot contain VoteIntent/Reservation rows. An existing subject or common deadline for the same execution cannot change. A certificate
received after its signed deadline is conservatively retained as Live if no authenticated terminal outcome is known; passing a height is
not nonapplication evidence and never yields a zero-live inventory. A matching known terminal outcome is preserved without resurrection.
Effect-certificate-only archival creates no actual reservation; any already retained matching owner must still have the exact independently
reexecuted full witness. Recovery repeats quorum/source checks and every existing subject/witness/deadline comparison before accepting
archive-related overlap.

Re-signing the exact original durable vote intent does not acquire a new vote. It may return the same subject/signature after certificate
archival only while the original execution remains live, the installed domain/fences allow publication, and a future inclusion remains
within the unchanged deadline. Effect/consensus retries additionally recheck their authenticated current-state conditions. The original
full subject, request evidence, owner and witness stay immutable. A new intent, another execution or another scope still passes the full
reciprocal lock/reservation checks; archival grants no exemption for that work.

Batch digests use `sigilaris.application.batch.v2`; state payload inventory must bind every prepared state/result chunk under the embedder's
authenticated schema. The retained inventory/evidence products, blob namespaces and acyclic content bindings are concretized in the
[application storage schema](v2-application-storage.md). Entries preserve execution-plan order, reject duplicate execution/owner ids, and compare normalized result bytes to
the selected core result digest. A batch contains all applications in an atomic ordered unit. Empty batches preserve the prior application
state root. A batch still has one selected owner per unique execution; additional owner scopes are resolved only by the later decision's
complete retained-owner closure, without duplicating applications. `blockId` appears only in the later decision;
it never feeds back into a plan/state/body preimage from which that same block id is computed.

`prepare` persists inactive state/result data and safety claim changes. `commit` durably selects the batch and block, applied index and terminal
owners as one recoverable decision before any canonical reader observes success. `recover` completes projection writes or restores a complete
uncommitted prior state while retaining all vote/claim/fence history. It never chooses a second batch for the same committed execution.
Failure in an ordered consumer must not commit a producer from that atomic unit. A previously canonical certified ancestor producer remains.

`ExactRegistration.canonicalRecord` and `ExactRecordUpdate.canonicalNextRecord` contain the full canonical schema-3 `ExactPipelineRecord`
from the [exact schema](v2-exact-schema.md), with full consumption and no JSON wrapper. Context, node pipeline id, binding digest, request digest
and deadline must independently equal the decoded record and recomputed signed plan. The record digest is
`H("sigilaris.tx-pipeline.exact.record.v3", canonical record bytes)`; registration/update digests use
`sigilaris.application.exact-registration.v2` and `sigilaris.application.exact-record-update.v2` respectively.
`admissionJournalSequence` is `Some` of the initial registration's already selected journal sequence and remains immutable through updates;
it is not a hash of the enclosing record. Exact update vectors sort uniquely by domain/node pipeline id, compare `priorRecordDigest` against
the authoritative current mirror, retain the original request/binding and atomically replace the complete two-stage record. An ordered atomic
application updates both stages in one record; an ancestor producer/consumer application updates only its applicable stage.

Every exact admission is durably registered before public success, proposal reservation or any stage vote. Admission and journal interruptions
are reconciled before reopening: verify the full retained source descriptor/request/binding and stage/output ownership indexes, journal an
already durably accepted but unregistered admission, and reject a missing/changed source for a retained journal registration. Only the
admission-journal sequence and lifecycle/application projections established by authoritative journal evidence may differ from the admission
store's older projection; the signed plan, immutable identity, accepted time and request never change. Closed admission cannot orphan an
already durable accepted row, but reconciliation does not authorize new post-fence issuance. Missing history, unbound stage subjects or
conflicting records fail closed; a journal deadline entry never fabricates a prior individual vote intent.

All nonterminal registered stages contribute their common deadline to drain, even before lock/proposal claims exist. Updates to Included and
ExpiredUnapplied participate in the same recoverable journal operation as canonical application/index/terminal evidence; the stage projection
cannot become terminal early to satisfy zero-live drain. `evidenceDigest` resolves the actual application decision/finality/materialization/
failure evidence with matching execution/result/height, not a caller assertion. Later Finalized/Materialized evidence preserves first application
fields. Failed candidate projection retains every existing safety claim and may not remove the registered deadline obligation while a stage
can still be included or holds a live claim. Terminal stages preserve immutable stage/output ownership for replay; recovery reconstructs that
ownership and the same subject/deadline decisions before admission or voting resumes.

## Evidence Records

Evidence references are `(kind:Tag, digest:H32)`; they are verified content references, never claims that missing bytes existed.
Inventory references are sorted by `(kind,digest)` and unique. The complete supporting closure must be available except explicitly classified
historical archive absence. Signing authority for each evidence class is selected in locally trusted deployment/bootstrap policy and bound
by the bundle; an arbitrary successful cryptographic signature does not authorize a source, fence or rollback decision.

| Product | Ordered fields |
| --- | --- |
| `TransitionIntent` | `format:I64=1, kind:Tag, sourceDomain:Text, targetChain:Text, targetManifestDigest:H32, targetValidatorSetHash:H32, futureBoundary:Opt[Nat], sourceAuthorityScopeDigest:H32, sourceSchemaDigest:H32, authorityPolicyDigest:H32` |
| `EvidenceBaseline` | `format:I64=1, transitionIntentDigest:H32, sourceDomain:Text, targetChain:Text, sourceCheckpoint:H32, sourceStateRoot:H32, authorityPolicyDigest:H32, entries:Vec[EvidenceRef]` |
| `PresentArchive` | `format:I64=1, retiredDomain:Text, inventoryDigest:H32, sourceBinding:H32, targetChain:Text, retirementFenceDigest:H32` |
| `ArchiveAbsence` | `format:I64=1, retiredDomain:Text, sourceBinding:H32, targetChain:Text, missingScopes:Vec[Text], knownCircumstances:Text, earliestKnownAbsence:Opt[I64], latestKnownAbsence:Opt[I64], explicitUnknowns:Vec[Text], survivingEvidence:Vec[EvidenceRef], rollbackDecisionDigest:H32, nonAncestryProofDigest:H32, neverEnabledDigest:H32, authorityId:Text` |
| `SignedArchiveAbsence` | `record:ArchiveAbsence, authentication:SignatureEnvelope` |
| `NeverEnabled` | `format:I64=1, domain:Text, lifetimeStart:I64, lifetimeEnd:I64, authorizedSignerInventory:H32, deployments:Vec[DeploymentInterval], keyUses:Vec[KeyUseInterval], supportingEvidence:Vec[EvidenceRef], authorityId:Text` |
| `DeploymentInterval` | `deploymentId:Text, start:I64, end:I64, binaryDigest:H32, effectiveConfigDigest:H32, signerKeyIds:Vec[Text], lockIssuance:Tag, effectIssuance:Tag, pathInventoryDigest:H32` |
| `KeyUseInterval` | `signerKeyId:Text, start:I64, end:I64, usePaths:Vec[SigningPath], evidenceDigest:H32` |
| `SigningPath` | `pathId:Text, binaryDigest:H32, configurationDigest:H32, lockIssuance:Tag, effectIssuance:Tag` |
| `SignedNeverEnabled` | `record:NeverEnabled, authentication:SignatureEnvelope` |
| `FencePromise` | `format:I64=1, context:DomainContext, signerId:Text, scope:Tag, boundary:Nat, greatestPreviouslySignedHeight:Opt[Nat], signingHistoryDigest:H32, transitionBinding:H32` |
| `SignedFencePromise` | `record:FencePromise, authentication:SignatureEnvelope` |
| `DrainEvidence` | `format:I64=1, transitionIntentDigest:H32, domain:DomainContext, kind:Tag, fenceDigest:H32, reconciledInventoryDigest:Opt[H32], greatestRecordedDeadline:Opt[Nat], baseUpperBound:Opt[Nat], oldMaximumLifetime:I64, coverageProofDigest:H32, finalizedEvidenceDigest:Opt[H32], zeroLiveEvidenceDigest:Opt[H32]` |

Archive evidence discriminants are `PresentContent=1` and `HistoricalAbsence=2`. Supporting evidence kinds are `Source=3`, `Rollback=4`,
`NonAncestry=5`, `Fence=6`, `Deployment=7`, `KeyUse=8`, `NeverEnabled=9`, `Drain=10`, and `AuthorityPolicy=11`.
These tags cannot be substituted: an attestation digest does not claim a raw archive content digest or missing signer watermark.
Baseline entries bind the signed evidence digest (or the complete independently authenticated `PresentArchive` inventory digest).
Individual records bind `sourceBinding = H("sigilaris.application.source-binding.v1", SourceBinding)` and target chain rather than the
baseline's own resulting digest. `PresentArchive` commits its raw content inventory and retirement fence without any baseline field;
the later baseline commits that independent record under `PresentContent=1`. The baseline never includes itself, a bundle containing its own
digest, or an activation decision which refers back to it. All baseline inputs must therefore be constructible and verifiable first.

`TransitionIntent` kinds are `Handover=1` and `InitialBootstrap=2`; the former requires `Some(futureBoundary)` and the latter requires `None`.
Its digest is `H("sigilaris.application.transition-intent.v1", TransitionIntent)`. This unsigned product contains only independently available
source deployment/schema/authority scope and known target policy/configuration/set/boundary. It contains no source snapshot root/checkpoint,
fence signature, certificate, selected continuation parent, evidence baseline, bundle or activation decision. Trusted transition policy
authenticates its meaning; signatures over its digest bind the exact selected intent. Every `FencePromise.transitionBinding` equals this
intent digest, and the verifier proves that its source/retired context and signing/write authority are included in the authenticated scope.

The source write fence is enforced first against that source authority scope, before selecting the inherited snapshot. Only then is complete
source state selected and verified into `SourceBinding`. Retired archives and later baseline/bundle bind the resulting source and independently
created fence evidence; neither feeds back into the intent. Required construction order is intent, enforceable fences, source selection and
independent supporting evidence, fixed baseline, signed bundle or handover, then activation preparation/decision. A missing or changed authority
scope cannot be repaired by replacing the intent or baseline after promises exist. Changing a continuation parent only changes later proofs
and preparation; existing future-boundary promises remain binding and cannot be erased.

Evidence digests use the domains in the V2 contract for inventory, absence and never-enabled. New fence and drain domains are
`sigilaris.application.fence.v1` and `sigilaris.application.drain-evidence.v1`. Authentication signs the domain-framed unsigned record;
the evidence digest of a signed record covers both record and signature envelope, under the same domain with the explicit signed-wrapper
prefix `Tag(1)`. Unsigned record signing uses prefix `Tag(0)`. This distinguishes signable record bytes from signed evidence reference bytes.
Present archive inventory digests use `sigilaris.application.archive-inventory.v1`; their authenticity follows the independently verified
rollback/source/retirement inventory and the later authenticated baseline/bundle. A content digest alone never authenticates that authority.

Intervals are `[start,end)` in authenticated epoch milliseconds and require `start < end`. No current wall-clock observation proves expiry;
these times delimit retained deployment evidence only. Deployment ids, key ids and path ids are unique in their scope and sort lexicographically;
intervals sort by id then start. The complete historical authorized signer inventory and deployment roster must account for every interval from
lifetime start to lifetime end. Capability tags are `Unavailable=1`, `ContinuouslyDisabled=2`, `Enabled=3`, `Unknown=4`; the last two reject
never-enabled eligibility. Custom binaries, alternate key integrations and effective configuration changes need their own verified intervals.
Surviving admission/safety records must be reconciled and empty; a valid no-store case supplies no invented zero watermark or empty store.

Fence scopes are `ApplicationIssuance=1`, `ConsensusProfileAtOrAbove=2`, and `SourceOrRetiredDomainWritesAndSigning=3`.
The typed verifier also requires enforceable key/write fencing, not just a signature over a promise. For consensus scope the signer's verified
historical watermark must be strictly below the boundary and no already-issued old subject at or above it may be omitted from history.
Application closure uses the independently verified subject deadline envelope; previously issued bounded votes may still assemble a certificate.

Drain kinds are `JournalCovered=1`, `InferredHorizon=2`, and `NeverEnabled=3`. Journal coverage requires independently proven pre-issuance
binding to each subject's own durable deadline record; exact subjects additionally bind their journaled exact execution/domain/deadline plan.
Inferred horizon requires authenticated `baseUpperBound` and checked `D_stop = B_stop + oldMaximumLifetime`; journal-covered forbids that field.
The first two require original-domain authenticated finality strictly above the maximum applicable horizon and zero reconciled live state.
Never-enabled requires the complete verified inventory, forbids a manufactured base bound or finalized proof, and validates any surviving
stores. Physical emptiness, local `Ready`, store absence, an exact-only label or an archive-absence statement never supplies the coverage proof.

## Bootstrap Certificate And Initial State

| Product | Ordered fields |
| --- | --- |
| `SourceBinding` | `format:I64=1, domain:Text, checkpointId:H32, checkpointHeight:Nat, stateRoot:H32, stateSchemaDigest:H32, dataInventoryDigest:H32, provenanceDigest:H32, replayPolicyDigest:H32` |
| `InitialValidator` | `validatorId:Text, publicKey:Bytes` |
| `BootstrapBundle` | `format:I64=1, transitionIntent:TransitionIntent, source:SourceBinding, target:DomainContext, validators:Vec[InitialValidator], targetManifestDigest:H32, genesisTimestampMillis:I64, sourceWriteFenceDigest:H32, ancestryKind:Tag, retiredEvidence:Vec[EvidenceRef], evidenceBaselineDigest:H32, authorityPolicyDigest:H32` |
| `SignedBootstrapBundle` | `bundle:BootstrapBundle, authentication:SignatureEnvelope` |
| `BootstrapSubject` | `format:I64=1, bundleDigest:H32, genesisBlockId:H32` |
| `BootstrapCertificate` | `format:I64=1, bundleDigest:H32, genesisBlockId:H32, quorum:Bytes` |
| `BootstrapStartupRecord` | `schema:I64=2, bundleDigest:H32, genesisBlockId:H32, installedStateInventory:H32, initializedSafetyInventory:H32, baselineDigest:H32, phase:Tag, issuedVoteIntents:Vec[H32]` |
| `BootstrapVoteIntent` | `schema:I64=2, bundleDigest:H32, genesisBlockId:H32, validatorId:Text, unsignedVoteSignBytes:Bytes, initializedSafetyInventory:H32, baselineDigest:H32` |

The bundle digest is `H("sigilaris.application.bootstrap.v1", signed wrapper bytes)` using the signed-wrapper discriminator above.
Validators are in the exact initial ordering committed by the bundle, with unique ids/keys; its validator-set hash must equal the existing
validator-set hashing result. Source ancestry tags are `NoPriorDomain=1` and `RetiredNonAncestorDomains=2`; continuing or unknown HotStuff
ancestry rejects this API. The second tag requires independently verified rollback/source selection, non-ancestry and retirement evidence
for every prior domain. Every relevant application domain separately satisfies its drain or never-enabled route.

The initial header `G` uses unchanged header V2 bytes, no parent, height zero, the imported source state root, canonical empty V2 application
body root and empty plan root, and exactly the bundle timestamp. This explicit initial condition does not execute conversion and is not an
ordinary finalized block. Its computed block id and bundle digest are independent inputs to `BootstrapSubject`.

Set `bootstrapProposalId = H("sigilaris.application.bootstrap.subject.v1", BootstrapSubject)` and form the existing
`QuorumCertificateSubject(window=(target chain,height=0,view=0,target set), proposalId=bootstrapProposalId, blockId=G)`.
`BootstrapCertificate.quorum` contains the existing canonical QC bytes for that exact subject. Its votes are real existing-format HotStuff
votes signing that target proposal id with each target validator key, using unchanged vote signature and vote-id rules. There are no invented
genesis proposal signatures, child/grandchild blocks or finality proof. The first ordinary height-one proposal retains the existing QC field
layout and carries this pinned initial QC as its justify.

The new explicit initial-justify verifier must verify the bundle's local trust, installed root, set, unique signature quorum and exact
bootstrap subject binding. It accepts that special QC only as the initial justify for the new chain's height-one ordinary proposal, whose
parent is `G`. It must never use this certificate as evidence that `G` finalized, resolve it as an ordinary historical proposal, accept another
bundle/root under the same startup identity or use it as an ordinary later justify. Later proposals and finality use normal certified
descendants; mixed initial/finality proofs still require actual descendant signatures and ordinary parent linkage.

The threshold is the active historical validator-set threshold `q=floor(2n/3)+1`, with distinct valid members; the conformance fixture uses
four independent validators and three votes. Before any bootstrap vote, proposal or other promise, install the complete verified source
closure and durably bind the startup record, unchanged initial state and initialized consensus/application safety state. Bootstrap phases
are `Bound=1`, `Installed=2`, `Signing=3`, `Opened=4`; transitions preserve prior signature intents. A crash never resets domain freshness.
Only exact retries may recover an already bound initialization; evidence-baseline loss or a changed bundle/root fails before signing.
Every initial-signing call requires a persisted phase of at least `Installed`, re-verifies the same source/state/safety/baseline inventories
and enforceable fences, then durably writes its exact vote intent before signature creation. Missing referenced content, contradictory
surviving history or loss/replacement of any fixed baseline evidence prevents the transition to `Signing` and every later signing retry.
`BootstrapVoteIntent` uses digest domain `sigilaris.application.bootstrap.vote-intent.v1`; its unsigned signing bytes are exactly the existing
HotStuff vote bytes for the pinned bootstrap subject and signer. Its digest is added atomically to the startup record's sorted unique
`issuedVoteIntents` vector by `BootstrapVote`, without fabricating an ordinary application execution or proposal intent.

The mandatory first opening remains a signed compatibility-singleton consensus transaction. Its independently derived eligible lock subset
must be empty with `None`; complete witness/reservation and signed deadline are still required. Initial `G` is allowed only as that lock-free
opening's authenticated admission base. A failed opening retains claims and halts unless a boundary-compatible recovery was separately verified.

## Handover, Activation And Restore

| Product | Ordered fields |
| --- | --- |
| `CertifiedSuffixEntry` | `height:Nat, blockId:H32, parentBlockId:H32, profileDigest:H32, proposalDigest:H32, quorumDigest:H32, resultInventoryDigest:H32` |
| `ConsensusSafetyBinding` | `signerId:Text, highQcDigest:H32, lockedQcDigest:H32, voterWatermarkDigest:H32, journalInventoryDigest:H32` |
| `HandoverEvidence` | `format:I64=1, transitionIntent:TransitionIntent, source:DomainContext, target:DomainContext, drainCheckpointId:H32, drainCheckpointHeight:Nat, drainStateRoot:H32, drainEvidenceDigest:H32, boundaryHeight:Nat, continuationParentId:H32, continuationParentRoot:H32, retainedSuffix:Vec[CertifiedSuffixEntry], safety:Vec[ConsensusSafetyBinding], fencePromises:Vec[SignedFencePromise], continuationProofDigest:H32, targetManifestDigest:H32, evidenceBaselineDigest:H32` |
| `PreparedNamespace` | `name:Text, schema:I64, contentDigest:H32, sourceInventoryDigest:H32` |
| `ActivationPreparation` | `schema:I64=2, transitionDigest:H32, baselineDigest:H32, completeOldGroupDigest:H32, prepared:Vec[PreparedNamespace], parentBlockId:H32, firstHeight:Nat, targetManifestDigest:H32, retainedSafetyInventoryDigest:H32` |
| `ActivationDecision` | `schema:I64=2, preparationDigest:H32, decisionSequence:I64, parentBlockId:H32, firstHeight:Nat, targetManifestDigest:H32, retainedSafetyInventoryDigest:H32` |
| `RestoreEvidence` | `format:I64=1, sourceGroupDigest:H32, currentSafetyInventoryDigest:H32, fenceEvidenceDigest:H32, preservedPromiseInventoryDigest:H32, preservedCanonicalWriteInventoryDigest:H32, authorityId:Text` |

Handover digest uses `sigilaris.application.handover.v1`; preparation/decision use `sigilaris.application.activation.v1` with explicit
variant `Tag(1)`/`Tag(2)`. Restore evidence uses `sigilaris.application.restore.v1`; the authority envelope is verified as for other evidence.
Namespaces and safety bindings sort by canonical name/id and are unique; suffix entries are in strictly consecutive height/parent order.
The authenticated boundary is the continuation parent's height plus one, lies strictly beyond every retained old-profile signed height,
and cannot relabel any issued old-profile block or remove an unknown suffix. Source and target chain ids match for handover.
All duplicate target/boundary/policy fields in bundle, handover, baseline and drain evidence must equal their `TransitionIntent` values.
Fence signatures bind only the acyclic intent digest above plus their own explicit scope/boundary/history fields; they never sign a hash of
a later enclosing handover, bundle, baseline or drain evidence that contains those signatures.

For each historical validator set still able to certify an old boundary subject, verify a quorum of enforceable signed fence promises.
Each honest signer must refuse a promise if its complete retained safety history already signed an old subject at or above that boundary.
For `q` fence signatures and Byzantine bound `f` satisfying `2q > n+f`, at least `q-f` honest signers prevent a new incompatible old quorum;
unavailable history, an unaccounted set or an unenforced fence rejects handover. This is a verifier obligation and required test case, not a
claim that a current deployment supplied the evidence. Delayed old QCs below the boundary retain their historical meaning.

`continuationProofDigest` resolves a retained, certified parent whose branch extends each applicable consensus lock under the historical
safe-vote rule and supplies a valid justify into the new range. Verify/replay every old-profile state transition from drain checkpoint `F`
through that parent `P`; the prepared opening state is `P`'s verified state, not automatically `F`'s. A parent change invalidates preparation.
Handover does not waive Exact freshness, cross-lane restrictions or zero old live safety claims. Required proofs must be actually verified;
a caller-supplied digest or Boolean cannot stand for consensus continuity.

The whole group includes application state/results/replay, block/consensus data and voter safety, safety journal, exact admission/idempotency,
generic pipeline data, manifests/configuration and every present/absent retired evidence record/fence. Preparation is inactive. One durable
decision selects all prepared namespaces while preserving immutable history; unresolved recovery fences listeners, signers and writers.
Abort discards only inactive outputs and preserves every handover/fence signature and subsequent consensus/application safety update.
Restore is eligible only if verification proves no external promise or safety/canonical write can be lost and all writers/signers remain
fenced. No-new-finalized-block and pre-local-switch checks are insufficient; otherwise recover forward or use a separately verified reverse
transition. An earlier backup does not reset a signer's watermarks or allow an archive baseline to be replaced.

## Public Scala Boundaries

The following signatures are the selected additive interfaces. Core wire products belong to
`org.sigilaris.core.application.protocol.v2`; JVM effect/store interfaces belong to
`org.sigilaris.node.jvm.runtime.application.v2`. The products named here have the exact field order above; aliases such as `Digest` and
`Height` map to the existing `UInt256` and `InclusionHeight`. `Result[F,A] = EitherT[F,V2RuntimeFailure,A]`.
`Verified*` wrappers have private constructors and can be obtained only from the corresponding authenticated verifier, not a public Boolean.

```scala
final case class SignatureEnvelope(
  authorityId: Utf8, algorithm: Byte, publicKey: ByteVector, signature: ByteVector,
)

trait ApplicationVoteSigner[F[_]]:
  def validatorId: ApplicationValidatorId
  def sign(context: DomainContext, canonicalPreimage: ByteVector): F[ByteVector]

trait ApplicationRequestVerifier[F[_]]:
  def verifyLock(subject: LockSubject, descriptor: InputDescriptor,
    signedTransaction: ByteVector, proofs: Vector[ResolutionEvidence]): Result[F, VerifiedLockRequest]
  def verifyEffect(subject: EffectSubject, owner: Owner,
    witness: ReservationWitness): Result[F, VerifiedEffectRequest]
  def verifyProposal(proposal: Proposal, plan: ExecutionPlan): Result[F, VerifiedConsensusProposal]

trait DurableApplicationVoting[F[_]]:
  def voteLock(request: VerifiedLockRequest): Result[F, LockVote]
  def voteEffect(request: VerifiedEffectRequest): Result[F, EffectVote]
  def prepareConsensusVote(request: VerifiedConsensusProposal): Result[F, PreparedConsensusVote]
  def signConsensusVote(prepared: PreparedConsensusVote): Result[F, Vote]
  def importLock(certificate: VerifiedLockCertificate): Result[F, Unit]
  def importEffect(certificate: VerifiedEffectCertificate): Result[F, Unit]
  def recover: Result[F, VotingRecovery]

trait ReservationWitnessStore[F[_]]:
  def putInactive(witness: ReservationWitness): Result[F, WitnessRef]
  def read(ref: WitnessRef): Result[F, ReservationWitness]
  def readChunk(ref: WitnessRef, index: Int): Result[F, WitnessChunk]
  def verifyAndRebuildIndex: Result[F, ReservationRecovery]

trait RecoverableApplicationStore[F[_]]:
  def prepare(batch: VerifiedApplicationBatch): Result[F, PreparedApplication]
  def commit(prepared: PreparedApplication, block: VerifiedCandidateBlock): Result[F, ApplicationDecision]
  def recover: Result[F, ApplicationRecovery]
  def applied(context: DomainContext, execution: ExecutionId): Result[F, Option[AppliedIndexRecord]]
  def expire(proof: VerifiedFinalityAndNonapplication): Result[F, Vector[TerminalResolution]]

trait CanonicalAncestorLookup[F[_]]:
  def lookup(request: AncestorRequest): F[AncestorLookupResult]

trait SourceSnapshotVerifier[F[_]]:
  def verify(bundle: SignedBootstrapBundle): Result[F, VerifiedBootstrapSource]

trait TransitionEvidenceVerifier[F[_]]:
  def verifyNeverEnabled(record: SignedNeverEnabled): Result[F, VerifiedNeverEnabled]
  def verifyAbsence(record: SignedArchiveAbsence, baseline: EvidenceBaseline): Result[F, VerifiedHistoricalAbsence]
  def verifyDrain(evidence: DrainEvidence): Result[F, VerifiedDrain]
  def verifyHandover(evidence: HandoverEvidence): Result[F, VerifiedHandover]
  def verifyBootstrap(bundle: SignedBootstrapBundle): Result[F, VerifiedBootstrap]
  def verifyRestore(evidence: RestoreEvidence): Result[F, VerifiedRestore]

trait InitialStateInstaller[F[_]]:
  def bind(source: VerifiedBootstrapSource, transition: VerifiedBootstrap): Result[F, BootstrapStartupRecord]
  def install(record: BootstrapStartupRecord): Result[F, BootstrapStartupRecord]
  def prepareInitialVote(record: BootstrapStartupRecord): Result[F, PreparedBootstrapVote]
  def signInitialVote(prepared: PreparedBootstrapVote): Result[F, Vote]
  def verifyInitialCertificate(certificate: BootstrapCertificate): Result[F, VerifiedBootstrapCertificate]
  def recover: Result[F, BootstrapStartupRecord]

trait ActivationStore[F[_]]:
  def prepare(transition: VerifiedHandover, group: VerifiedConsistencyGroup): Result[F, ActivationPreparation]
  def commit(prepared: ActivationPreparation): Result[F, ActivationDecision]
  def recover: Result[F, ActivationRecovery]
  def verifyRestore(evidence: VerifiedRestore): Result[F, RestoreEligibility]
```

`VerifiedLockRequest` contains the core lock subject, authenticated admission base, independently derived input descriptor, signed-source proof
and optional exact-plan binding; it does not require execution or a complete actual reservation witness before lock voting.
`VerifiedEffectRequest` carries the independently validated execution result, complete actual reservation witness/owner and source certificate
context. `VerifiedConsensusProposal` contains the existing unsigned vote, authenticated
candidate/plan/source/results and all complete owner claims. Prepared consensus/bootstrap vote tokens bind the durable intent digest, signer,
context and exact unsigned vote; signing rechecks token/store equality and current fences. Constructing or reusing a stale token cannot bypass
recovery. Successful prepare followed by signer failure retains every intent and claim; same-subject retry is allowed only while its current
domain/fence/deadline rules authorize publication.
`ApplicationRequestVerifier` has trusted manifest/authority/historical-base/ancestor/artifact repositories and deterministic execution hooks
as constructor dependencies. It resolves all referenced material, validates exact journal binding where applicable and independently executes
effect/proposal work against authenticated state. Caller-supplied subject, witness, descriptor or plan bytes never create a `Verified*` wrapper
without those checks; missing dependencies return unavailable errors. Pure derivation alone is not a successful runtime verifier.

`AncestorRequest` fields are `(context, candidateParent, producerBlock, producerExecution, pipelineDigest, outputDigest, deadline)`.
Results are `Available(VerifiedAncestorProof)`, `Unavailable(reason)` and `Invalid(reason)`; no unverified id vector is a successful result.
The proof verifies canonical parent linkage, historical profile/validator sets/QCs and producer output through retained/backfilled history.
The private verified batch/block wrappers prevent the public application hook from accepting an arbitrary claimed state root.

Recovery results expose the selected durable decision, highest sequence and verified inventory digest; unresolved/corrupt records return a
typed failure and keep the runtime fenced. `VotingRecovery` and `ReservationRecovery` additionally bind the verified/rebuilt index digest.
`RestoreEligibility` records the preserved safety inventory and complete group digest; it does not itself execute an operator restore.
Local capacity and unavailable proof are retryable holds; conflict, protocol noncanonicality, evidence contradiction and deadline violations
reject their candidates. Every failure preserves live claims until authenticated application or expiry, never an implicit local unlock.

Independent concrete implementations of the verifier/installer/application hooks must resolve trusted authority and content references,
validate the complete reachable evidence/state closure and expose no partial canonical result. Functional fixtures must test the real
boundary and genuine four-validator messages; a stub returning `Verified*`, one-node multi-key QC, or caller assertions cannot satisfy the gate.
