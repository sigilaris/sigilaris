# Application execution V2 contract

This is the implementation contract for [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md).
Its baseline is source `efe4f45` and immutable Maven `0.3.0-M2`. A contract selection is not evidence that an implementation or deployment passes it.
The development artifact is `0.3.0-M3-SNAPSHOT`; the candidate milestone is `0.3.0-M3`. Publication, tagging and deployment remain separate work.

## Version selection

| Axis | Historical contract | Selected contract |
| --- | --- | --- |
| Application protocol | `ProtocolVersion(1)` (M1 in both artifact milestones) | `ProtocolVersion(2)` |
| Input derivation / family manifest encoding | legacy descriptor and four field roles, v1 domains | additive `protocol.v2` types, format 2 |
| Execution plan | format 1; execution-id-only waves; empty rejected | format 2; complete entries; empty permitted |
| Block header | format 1 or 2 under historical rules | format 2, unchanged header bytes and block hash domain |
| Application protocol manifest | manifest 1 | manifest 2 with the complete format tuple and limits |
| Lock / effect artifacts | codec and signing domains 1 | codec and signing domains 2 |
| Exact signed plan / identity | v1 commitment domains; transport `exactV2` | v2 commitment domains; transport `exactV3` |
| Exact persistent record / snapshot | schema 2 | schema 3 |
| Application safety records / journal | schema 1 | schema 2 in separate active namespaces |
| Opening envelope / witness / transition evidence | absent | format 1, newly introduced domain names below |

The historical tuple `(header=2, plan=1, lock=1, effect=1, exactCodec=2, journal=1)` remains readable.
New work uses `(protocol=2, input=2, header=2, plan=2, manifest=2, lock=2, effect=2, exactCodec=3, journal=2)`.
Every other new-work combination is rejected. Application dependency profile ids and versions remain application-owned; the new manifest
binds each id, version and verifier digest rather than assigning application versions implicitly.
An authenticated chain/configuration/height schedule dispatches historical and active tuples, including V1-to-V2 and V2-plan1-to-V2-plan2.
Initial imported source history belongs to its original domain, never a fabricated height range of the new chain.

Historical entry points retain their types, bytes, domains and validation behavior. Passing `ProtocolVersion(2)` to a legacy descriptor
does not select the new derivation. New types are introduced under `org.sigilaris.core.application.protocol.v2`; runtime/store types use
`org.sigilaris.node.jvm.runtime.application.v2`. Boundary adapters dispatch explicitly and never coerce new values into legacy subjects.
M1 and M2 have distinct legacy validation provenance despite sharing protocol 1: family-id parsing and duplicate-identity acceptance changed
between artifacts. Historical dispatch selects `LegacyM1` or `LegacyM2` from authenticated configuration/deployment evidence and retained bytes;
missing provenance fails closed. M2's parser must not reconstruct M1's raw spaced identifiers.

## Canonical encodings and domains

Use the existing canonical byte primitives: a product is field encodings in declaration order, integers use their declared existing primitive
encoder, vectors use the existing length-prefixed list encoding, opaque bytes are length-prefixed, and digests are 32 bytes. Version/tag fields
are explicit; enum tags below are one byte. Decoders reject unknown tags, duplicate members, noncanonical order, trailing bytes and malformed
lengths before accepting a preimage. Signature verification uses precisely the committed canonical bytes; alternate encodings are rejected.

The existing `ApplicationProtocolHash` framing is retained with new domain strings. Selected domains are:

| Value | Domain |
| --- | --- |
| Full resolved input | `sigilaris.application.input.full.v2` |
| Eligible mutation lock subset | `sigilaris.application.input.lock-subset.v2` |
| Declared / actual footprint | `sigilaris.application.input.footprint.declared.v2` / `sigilaris.application.input.footprint.actual.v2` |
| Execution identity | `sigilaris.application.execution.id.v2` |
| Family manifest / protocol manifest | `sigilaris.application.family-manifest.v2` / `sigilaris.application.manifest.v2` |
| Declaration / classification | `sigilaris.application.declaration.v2` / `sigilaris.application.classification.v2` |
| Execution plan | `sigilaris.application.execution-plan.root.v2` |
| Lock vote / certificate | `sigilaris.application.lock.vote.v2` / `sigilaris.application.lock.certificate.v2` |
| Effect vote / certificate | `sigilaris.application.effect.vote.v2` / `sigilaris.application.effect.certificate.v2` |
| Exact plan / identity / reference | `sigilaris.tx-pipeline.exact.plan.v2` / `sigilaris.tx-pipeline.exact.identity-binding.v2` / `sigilaris.tx-pipeline.exact.reference.v2` |
| Opening envelope | `sigilaris.application.opening.v1` |
| Reservation witness / chunk | `sigilaris.application.reservation.witness.v1` / `sigilaris.application.reservation.chunk.v1` |
| Bootstrap / handover / activation | `sigilaris.application.bootstrap.v1` / `sigilaris.application.handover.v1` / `sigilaris.application.activation.v1` |
| Absence / never-enabled / inventory | `sigilaris.application.archive-absence.v1` / `sigilaris.application.never-enabled.v1` / `sigilaris.application.evidence-inventory.v1` |

Normalized application results may retain `sigilaris.application.result.normalized.v1`: normalization still commits exactly the same opaque bytes.
The existing `BlockBody`, record encoding and `sigilaris.block.body.root.v1` domain also remain unchanged, including the empty body root.
V2 sources/results use the existing application-record envelope; body membership and correspondence are additional activated checks.
Validator-signed V2 application artifacts additionally bind chain id, epoch, validator-set hash and configuration digest. The fresh canonical
chain id is the validator signing-domain discriminator and is encoded once; it is not an independently selectable second domain string.
Existing HotStuff proposal/vote/timeout/new-view signed layouts already bind that chain id and retain their historical bytes/domains.
New user transaction signature domains are not inferred from validator-domain changes; the application manifest and replay verifier define them.

## Independent input and footprint APIs

`InputManifest`, `ResolvedField`, `InputDescriptor`, `Footprint`, `ActualAccess` and `InputDerivation` are the new core surface.
The family manifest commits format, family id/version, ordered unique field declarations and authority-verifier digest.
Each resolved field commits field id, role, resolved value bytes, stable identity where applicable, exact value precondition and authority evidence.
Role tags are `Immutable=1`, `ExactRead=2`, `ExactMutate=3`, `Opaque=4`. Authority tags are `ConsensusOnly=1`, `LockEligible=2`.
Read/mutate fields require a nonempty stable id and exact precondition; immutable/opaque fields cannot inject a mutable identity.
The trusted application resolver verifies role, value/precondition and authority against authenticated manifest and execution pre-state.
Sigilaris verifies returned fields against that manifest, uniqueness, canonical ordering and all independent commitments.

The full vector contains every declared resolved field sorted by field id. The lock vector contains exactly existing `ExactMutate` fields
with verified `LockEligible` authority, sorted by stable identity. Duplicate fields/identities are rejected rather than deduplicated.
The concrete declared footprint has canonical disjoint read and write identity vectors (writes subsume reads of the same identity), including
consensus-only mutations and absent creation targets. Creation targets are writes with an authenticated absence precondition, never locks.
`derive` computes commitments; `validate` independently recomputes and checks the manifest, authority and pre-state bindings. A lock-subset hash
cannot be substituted for either footprint hash even if its identities happen to coincide.

`ActualAccess` comes from deterministic execution instrumentation, not proposer claims. Exact declarations cover every actual read/write and
creation absence check. Compatibility declarations derive the complete actual footprint after execution and reject any dynamically discovered
existing lock-eligible mutation that was not an explicit Exact input protected before proposal. Writes cannot be disguised as reads.
Authority evidence and actual instrumentation remain public embedder obligations; accepting a caller-supplied `true` is not authenticated validation.

## Plan and source APIs

`PlanEntry` commits execution id, source, declaration, declaration digest, classification reference, full-input/lock/footprint commitments,
dependency-plan digest, signed deadline and entry pre-state witness commitment. The execution id commits manifest/configuration, normalized
signed transaction, independent input commitments, dependency plan and deadline.
Source tags: `ConsensusTransaction=1` binds the signed transaction reference and optional input-lock certificate;
`CertifiedFastExecution=2` binds the certified subject, nonempty input-lock certificate and effect certificate references.
Certificate verification checks complete subjects, historical signer set and signatures, not just reference presence.
Consensus source application requires no fast effect certificate and never promotes its lifecycle to `fastCertified`.

Declaration tags: `Exact=1` commits the complete declared footprint and preconditions; `Compatibility=2` commits the manifest-defined reason digest.
Wave tags: `ConflictFree=1`, `Ordered=2`, `CompatibilitySingleton=3`. Conflict-free members use application transaction/source-id order. Ordered members preserve signed
order. Compatibility is exactly one consensus entry in the sole wave in its block. Present waves are nonempty; ids and source body membership
are unique and exact. Conflicts involving a conflict-free entry are rejected across waves too; placing two conflicting entries in separate waves
does not grant permission. Ordered overlaps still require per-entry freshness and reservations and cannot override an external fast lock.

Classification statements bind manifest, transaction/source identity, declaration, reason, purpose, exact referenced keys/expected values and
entry pre-state root, and have one canonical encoding. A witness-dependent classification requires the corresponding proof artifact to be
available and independently verified; witness-independent classification requires `None`. Each required
statement appears once; absent, duplicate, unused or alternate statements fail. Entry-local witnesses bind the actual pre-state resulting from
prior ordered entries, including producer output, rather than always using the block parent's root.
The sole empty plan is format 2 with `waves=[]`, no statements and empty body membership. Its root is the normal V2 hash, not a sentinel.
Its body root is the canonical empty V2 application body root; its application state root equals the verified parent's root.

## Signed opening and capacity

`OpeningEnvelope` format 1 signs source-domain/checkpoint/root/schema, target chain/signing-domain/configuration, target manifest, known parent,
first height, authenticated admission-base reference, `lastInclusionHeight`, conversion payload and reason digest. The resulting block id is not
part of its preimage. `MaintenanceAuthorizationVerifier` validates the signature under a locally trusted, manifest-bound maintenance authority.
`OpeningExecutor` deterministically converts the parent's working state, returns normalized body result and instrumented actual accesses.
The neutral fixture reads and increments consensus-only cells and creates an absent key, using a signed maintenance envelope and complete witness.

Ordinary/handover admission uses an authenticated finalized base; only initial bootstrap's lock-free opening can use authenticated anchor `G`.
Require `base < deadline <= base + maxLockLifetimeBlocks` with checked height arithmetic; all reservations use the signed deadline and domain.
Bootstrap opening is the first ordinary descendant of `G`, derives an empty subset and supplies `None`; a nonempty subset is unsupported even
with a certificate. Handover requires a matching nonempty certificate exactly when its independently derived subset is nonempty.
Application at the deadline is allowed. Only same-domain canonical finality strictly beyond it, together with authenticated nonapplication,
can expire unapplied claims. A failed mandatory opening retains claims and halts activation unless a verified boundary-compatible recovery exists;
empty descendants cannot skip the required opening.

One canonical complete footprint witness is stored once by content digest and referenced by claims. Identity index rows contain owner references
and access mode, not replicated full witnesses. Witness format 1 encodes sorted unique identities with `Read=1` / `Write=2`. Chunk format 1
binds witness digest, index, total count and bytes; chunks are consecutive slices of the canonical witness bytes, verified after reassembly.
The authenticated V2 manifest limits each witness to 100,000 identities, 16,777,216 encoded bytes, 256 chunks, each at most 65,536 bytes,
and each stable identity to 256 bytes. Every nonfinal chunk is exactly 65,536 bytes, the final chunk is the remaining nonempty suffix,
and chunk count is `ceil(encodedWitnessBytes / 65,536)`. An empty footprint still has a format/count encoding and therefore one chunk.
Duplicate/missing/out-of-order/tampered
chunks and noncanonical segmentation are invalid. Limits apply before votes and to the one transaction, not separately to each transported piece.
Node-local memory/disk/recovery limits are additional capacity checks: shortage returns `CapacityUnavailable`, retains fences and does not make
an otherwise valid block invalid. Protocol over-limit data returns `ProtocolLimitExceeded` consistently on all validators.

## Durable voting, reservation and application APIs

`DurableApplicationVoting.voteLock` / `voteEffect` transact complete canonical subject validation, reciprocal interlock and durable intent
before invoking `ApplicationVoteSigner`. The intent includes validator/domain, execution, dependency plan, input vector, result/root when applicable,
deadline and source/proposal scope. An identical retry can sign the same subject; a different live subject cannot overwrite it.
Signer failure/cancellation keeps the claim. Unknown write success poisons the writer until recovery resolves the journal; no signature follows
an ambiguous write. Certificate import must agree with existing intents and cannot erase them. The signing boundary remains fenced on restart
until authoritative witness/claim records are verified and indexes rebuilt. Historical missing subjects are never synthesized.

`ReservationClaim` owns `(domain, execution, authenticated plan/proposal scope)` plus deadline, footprint witness digest and lifecycle.
Multiple read owners coexist. Conflicting consensus claims coexist only with authenticated ancestor/ordered-plan authorization; the verifier
checks plan/root, ordering and branch. Every external fast write conflicts with all live readers/writers on that identity, in either acquisition
direction. A retry has byte-identical ownership and deadline. Resolving an owner removes only that owner's index entries; shared witnesses remain
while referenced, and terminal history is retained for replay. An empty lock subset still has a reservation/terminal identity.

`RecoverableApplicationStore` provides `prepare`, `commit` and `recover` for canonical state/result, applied index and terminal claim changes.
Preparation writes an immutable result batch bound to parent/root/body/plan; one durable decision makes the complete batch visible.
Readers and signing remain fenced during unresolved recovery. An embedder with separate stores must supply this atomic visibility/recovery
adapter; an internal safety transaction alone cannot claim cross-store atomicity. Replays compare the complete committed batch and are idempotent.

## Exact execution, ancestry and ordinary consensus APIs

V2 exact descriptors bind two ordered stage identities, profile/lane/configuration, producer output slot, consumer reference and common signed
deadline. Both `OrderedAtomic` and `CertifiedAncestor` support consensus stages with neither, one or both lock subsets nonempty.
`ExactConsensusExecutionRuntime` validates optional input certificates independently from the certified-fast path, executes immutable working
state and commits through `RecoverableApplicationStore`. A consumer rejection exposes no partial atomic producer application.

`CanonicalAncestorLookup` returns `Available(proof)`, `Unavailable(reason)` or `Invalid(reason)` for chain, candidate parent and producer.
Proof verification binds canonical root/QCs, parent edges, heights, profile, pipeline and output; retained/backfilled finalized history is searched
past the current finalized tip. Certified unfinalized ancestry still requires genuine producer block certification. Missing proof never becomes
tip equality or an unauthenticated id list. Cross-pipeline, lane, epoch/configuration and undeclared dependency chaining remain rejected.

Ordinary HotStuff proposal input/assembly and pre-vote validation use the same `HotStuffApplicationProfileSchedule`, plan-preimage store and public
application verifier. Activated input builds header V2 and persists the full source/plan/witness before publication. Remote validation retrieves
and authenticates those preimages before voting. Application, finalization observations and materialization remain separate lifecycle events.
`HotStuffMaintenanceTarget` binds domain and a finalized height strictly above all relevant deadlines. Bounded progress returns
`Idle` when no target is requested, `Requested` with attempt metadata, `TargetReached`, or `Stalled(reason)`/`Unavailable(reason)`. The embedding `HotStuffMaintenanceSource`
must reconstruct the target from its retained drain command after restart; attempt counters and identity capacity are local to the provider instance.
Stalls never terminalize claims or activate early.

## Transition evidence and recovery APIs

`TransitionEvidenceVerifier` verifies locally trusted signed inventories and proof objects before constructing an eligible transition.
No deployment is declared eligible merely by selecting this contract. An unsupported old progress route remains typed `UnsupportedUpgrade`.
The maintained neutral old-profile fixture must demonstrate valid V1 empty-header progress or valid nonempty old-plan progress before that route
is advertised; it may not use new empty-plan validation ahead of activation.

Three application drain evidence kinds are distinct: complete reconciled pre-issuance journal coverage; authenticated vote closure and proven
base bound `B_stop` with `D_stop=B_stop+oldMaxLifetime`; or independently authenticated never-enabled lifetime inventory.
The first two require finality strictly beyond the applicable horizon and greater recorded deadlines and zero reconciled live state.
Previously issued bounded honest signatures may assemble a certificate after closure; it retains the old deadline and historical verification.
A journal watermark is not vote-subject durability. Every generic lock subject requires independently proven pre-issuance durable deadline
binding; exact subjects additionally require exact-plan execution/domain/deadline binding before issuance to claim coverage.

Never-enabled format 1 binds domain, lifetime start/end, complete ordered signer/key inventory, deployment intervals with binary digest,
effective-config digest and explicit issuance capability, all alternate/custom signing paths, authority id and supporting evidence digests.
Intervals must cover the complete lifetime without gaps or unknown/possibly-enabled paths, including all authorized signers.
The verifier checks signatures and referenced binary/configuration/key-use evidence and reconciles surviving admission/safety stores for
contradictions. Eligible never-created/previously absent stores need independent evidence; no empty store or watermark is fabricated.

`BootstrapBundle` format 1 binds complete source provenance/schema/state-data closure, source write fence, source checkpoint/root,
fresh target chain and validator signing domain, ordered validator keys/set hash, target manifests, genesis parameters and evidence baseline.
`SourceSnapshotVerifier` checks trusted source canonicality and complete reachable data. `InitialStateInstaller` durably installs the same bundle,
root and initialized consensus/safety state before signing. Genesis height is zero, with no parent; its state root is the imported source root.
Initial justify is a dedicated bundle-bound bootstrap certificate with a real quorum of target validators, never a fabricated finalized proof.
The first ordinary proposal has height one and parent `G`; existing HotStuff verification must explicitly recognize the bootstrap certificate.
The initialized target domain must have no prior signing history; retries recover its bound identity and cannot select a new bundle.

Source ancestry has three explicit cases: continuing HotStuff history requires handover; no ancestry/no prior domains allows bootstrap;
no ancestry plus independently proven retired non-ancestor domains allows bootstrap with persistent signing/write fences.
Retired evidence uses separate tagged `PresentContent=1` and `HistoricalAbsence=2` records. Present roots/QCs/locks/watermarks/fences are
inventoried by content digest and preserved read-only. HistoricalAbsence binds the locally trusted attestation authority, affected domain,
source and target, missing scope, known circumstances/timing, explicit unknowns, surviving evidence and independent rollback/non-ancestry/
never-enabled inventory digests. It is valid only for absence predating the frozen transition baseline and cannot substitute for those proofs.
No replacement attestation/baseline or restart can excuse subsequent loss of expected-present content or supporting evidence/fences.
Source data and current transition/initial-signing records must always be complete. New-chain height cannot expire old-domain claims.

`HandoverEvidence` binds drain checkpoint `F`, retained certified suffix, highQC/lockedQC, voter watermarks, authenticated future profile boundary,
old-quorum fence and valid continuation parent `P`. No signed old block is relabeled. Historical mixed-range finality proof validation uses the
schedule. Replay retained old suffix from `F` to `P`; prepared output binds `P` and is discarded/recomputed if `P` changes.
Back up the complete stopped-node consistency group and evidence before inactive schema preparation. `ActivationStore` atomically commits one
decision binding prepared namespaces and the exact boundary; recovery selects one consistent group before listeners/signers reopen.
Abort can discard inactive output only. Published handover/fence signatures and all later safety/consensus/application writes survive even
pre-switch failure. A restore must prove it loses none of these promises/writes and keeps writers/listeners fenced; otherwise recover forward.

## Failure contract

Core failures distinguish unsupported format/tuple, noncanonical encoding, duplicate/member/commitment mismatch, role/authority/precondition
mismatch, access omission, missing/unexpected/mismatched certificate, classification mismatch, forbidden overlap and protocol limit violation.
Runtime failures additionally distinguish conflict, already applied/terminal, deadline/domain mismatch, unavailable/invalid proof, capacity
unavailable, signer failure, storage unknown/recovery required, incomplete witness/index, unsupported upgrade, evidence missing/contradictory,
unsafe boundary, evidence-baseline loss and startup identity mismatch. No such failure authorizes early release, guessed history or signing.

## Evidence and review policy

Every requirement is mapped in [the evidence map](v2-evidence-map.md). Maintained executable vectors live in exported `release-smoke` main
sources; module tests supplement them. Phase results record commands and actual outcomes. Public M2 baseline, candidate staging and later
public M3 resolution are separate evidence classes. A checked plan item requires implementation and a review with no remaining findings.
Any necessary contract amendment must update this document, the executable fixtures and remaining plan before changing affected encoders.
The [core schema](v2-core-schema.md), [exact schema](v2-exact-schema.md) and [runtime schema](v2-runtime-schema.md) fix ordered fields, primitive codecs and public method signatures;
they are part of this contract rather than implementation-specific choices deferred beyond Phase 0.

## Verifier binding identity

The frozen V2 manifest identifies verifier bindings by the pair `(verifierSlot, verifierManifestDigest)`, with separately unique profile IDs. A slot may occur with different digests; consumers must resolve the selected profile and its complete binding. Slot-only uniqueness would narrow the accepted manifest format and requires a separately versioned decision.
