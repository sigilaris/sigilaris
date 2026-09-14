# Application execution compatibility inventory

This is the Phase 0.1 source inventory for [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md).
It identifies historical contracts and the additive target contract. A source inspection is not an artifact conformance result; the maintained
standalone baseline and subsequent JVM/Scala.js vectors provide that separate evidence.

## Baseline identity and version axes

The inventory was taken from source `efe4f4529a1c266e024ea8be74b3c7ad58a08847` on 2026-09-11. The immutable release tags are:

| Artifact release | Annotated tag object | Peeled source commit | Application protocol | Core input/plan domains |
| --- | --- | --- | --- | --- |
| `0.3.0-M1` | `c6ca72036dd125db788b8c1f791ca2e877658c90` | `1c8c2b7c394b923e89800efcced800b7844861ae` | `ProtocolVersion.M1`, integer 1 | v1 |
| `0.3.0-M2` | `1f82c3d4153981159878df57e1ba3afbbaee69ab` | `cc9a77631f4e988a3f18fbf8983857aef59be302` | `ProtocolVersion.M1`, integer 1 | v1 |

Maven milestones are not wire versions. In particular, M2's `exactV2` JSON discriminator is not application protocol 2. The selected development
milestone is `0.3.0-M3-SNAPSHOT`, with M3 as a release candidate name; this selection does not publish or activate an artifact.

| Axis | M2 published value | Selected additive contract |
| --- | --- | --- |
| Application protocol | 1 | 2 |
| Family input derivation/manifest | implicit v1 descriptor structure | explicit derivation 2 and family manifest 2 |
| Block execution-plan format | 1 | 2 |
| Block header | historical V1 and V2, tags 1 and 2 | existing V2, tag 2, with active plan profile selected separately |
| Application protocol manifest | `ApplicationProtocolManifestV1.Version = 1` | manifest 2 |
| Lock subject/certificate codec | 1 | 2 |
| Effect subject/certificate codec | 1 | 2 |
| Exact transport/record/snapshot codec | 2, `exactV2` | 3, separately dispatched `exactV3` |
| Exact plan and identity hash domains | v1 | v2 |
| Application safety records/journal | schema 1 | schema 2 |
| Application safety snapshot | aggregate of schema-1 records; no independent persisted snapshot-version field | explicit new aggregate format bound to schema 2 |
| Dependency profile version | application-selected positive integer | explicitly activated application-selected id/version/verifier digest; no global forced profile number |

The exact published substrate tuple is `ApplicationSubstrateVersions.M1 = (2, 1, 1, 1, 2, 1)` in the order header, plan, lock, effect,
exact pipeline, application journal. The old validator requires that exact tuple and nonempty dependency profiles. New profile activation must
preserve old manifest decoding and archived verifier registrations. An application with no exact dependency profile must not manufacture one
merely to satisfy the old manifest validator; the new manifest owns its explicit profile rules.

## Core API, canonical bytes and validation

The historical public core definitions are under `org.sigilaris.core.application.protocol`. New definitions belong to the additive
`org.sigilaris.core.application.protocol.v2` namespace. Existing constructors, normalization, validators, and signing-preimage helpers keep their
published behavior. In particular, passing `ProtocolVersion(2)` into historical normalization does not select derivation 2: historical normalization
accepts arbitrary family versions and still uses its fixed v1 domains.

| Historical surface | Encoding/canonicalization and behavior | Required preservation or new contract |
| --- | --- | --- |
| `ApplicationFieldRole` | Single byte: Immutable=1, MutableRead=2, MutableWrite=3, Opaque=4. `all` enumerates exactly those cases. | Keep tags and old meaning. New access/binding/eligibility types do not append optional semantics to these roles. |
| `ApplicationFieldManifest` | Product order: field id, role. | Historical bytes remain unchanged. New manifest authenticates role, schema, access, binding, ordering and authority eligibility policy. |
| `ApplicationFamilyManifest` | Product order: protocol/family version, family id, vector of field manifests. | New manifest identity independently binds derivation version and the application authentication policy. |
| `ApplicationFieldDescriptor` | Product order: field id, role, value bytes, optional stable identity. | Do not insert new fields into the derived historical encoder. |
| `ApplicationInputDescriptor.normalize` | Sorts fields by field-id string. Full input binds all sorted fields. Both mutable roles require stable ids; immutable/opaque roles reject them. | v2 retains all resolved inputs, including reads and consensus-only mutations, independently of lock derivation. |
| Historical lock subset | Every provided stable id becomes `(fieldId, role, inputId)`, sorted by `(inputId hex, fieldId)`. | Preserve v1 identity membership. New subset selects only existing Exact/Mutate inputs whose authenticated authority is eligible. |
| Historical footprint | Every provided stable id becomes `(inputId, writes)` where only MutableWrite sets writes, sorted by `(inputId hex, writes)`. | The v1 lock and footprint identity sets coincide, although bytes and domains differ. v2 declared/actual footprints include all reads, writes and absent creation targets independently. |
| Input validation | Manifest nonempty, duplicate field rejection, exact field membership and roles, stable-id rules, then three independent digest comparisons. M2 also rejects duplicate stable ids. | Preserve historical acceptance and failure behavior; v2 validates canonical membership, independent commitments and manifest-bound eligibility. |
| `ExactInputFreshness.validate` | Rejects duplicate expected input, missing current input, changed value digest, and unexpected current input. | Existing-state Exact reads and mutations must still match at each entry's working state. Ordered overlap does not permit implicit rebinding. |
| `ExecutionIdentity.compute` | Fixed v1 domain; product binds protocol/configuration/family, normalized transaction bytes, full/lock/footprint commitments, dependency plan and deadline. | New execution identity domain binds the independent v2 commitments and authenticated context. |
| `NormalizedApplicationResult` | Digest commits its complete bytes under a fixed v1 domain. | Retain v1 result normalization where its bytes/semantics remain unchanged; any new normalization contract needs its own domain. |
| `ExecutionWave` | ConflictFree tag 1 and Ordered tag 2, followed by execution-id vector. | Historical wave bytes do not commit admission source or declarations. New source-bearing wave entries need plan format 2. |
| `ExecutionPlan` | Product is 8-byte version and wave vector. Validates only version 1; rejects empty plans/waves, unsorted conflict-free ids, duplicates and body-membership mismatch. | Keep version-1 empty rejection. New format accepts `waves=[]` only with empty application membership and unchanged parent application root. |
| `compatibilitySingleton` | Historical helper is one Ordered wave with one id. No distinct compatibility tag exists. | v2 CompatibilitySingleton has a distinct wave tag and is the sole entry/wave in its block. |
| `OrderedWaveExecutor` | Folds working state; failure returns no partial result. Empty ordered wave rejects. | Continue actual working-state execution and atomic visibility; block-level empty plans do not make a present wave empty. |
| `HistoricalApplicationValidatorSet` / certificate verification | Nonempty distinct validator list; quorum floor(2N/3)+1; verify epoch, validator-set hash, full subject equality, signer membership/uniqueness, signatures and quorum. | v2 uses historical validator sets for the subject's authenticated domain. A block QC is distinct from an application effect certificate. |

M1 and M2 must have separate validation baselines despite sharing protocol 1. The M1-to-M2 source diff adds `DuplicateStableConflictId` rejection
and changes `ApplicationFamilyId.parse` from preserving a nonblank original string to storing its trimmed value. Therefore an M1 family-id byte
vector with surrounding spaces must be retained as historical bytes, rather than reconstructed by M2's parser. Historical dispatch selects explicit
`LegacyM1` or `LegacyM2` validation provenance from authenticated historical configuration and deployment inventory; protocol integer 1 alone
cannot select between them. Missing provenance cannot justify guessing. The new work must not retroactively claim that those artifact-level
validation behaviors were identical.

Canonical primitives are also part of the contract: product encoders concatenate fields in declaration order; `Long` is eight-byte big endian;
vectors use the list length prefix; byte vectors use BigNat length then bytes; options encode as zero/one-element lists; booleans are 00/01;
UInt256 values are fixed-width bytes. Application protocol hashing is Keccak-256 over encoded UTF-8 domain and length-prefixed payload. New
decoders must consume the entire selected preimage and reject unknown tags and noncanonical encodings rather than silently normalizing them.

## Hash and signature domain inventory

| Published literal domain | Owner / scope | Target treatment |
| --- | --- | --- |
| `sigilaris.application.input.full.v1` | Full descriptor preimage | Preserve; new full-input domain ends in `.v2`. |
| `sigilaris.application.input.lock-subset.v1` | Both mutable-role identities | Preserve membership and bytes; new eligibility derivation ends in `.v2`. |
| `sigilaris.application.input.footprint.v1` | Descriptor stable ids and write flags | Preserve; v2 separately distinguishes declared and actual footprint commitments. |
| `sigilaris.application.result.normalized.v1` | Normalized result bytes | Preserve unless result normalization itself changes. |
| `sigilaris.application.execution.id.v1` | Historical execution identity | Preserve; new execution identity ends in `.v2`. |
| `sigilaris.application.execution-plan.root.v1` | Id-only ordered wave plan | Preserve, including historical invalid empty-plan bytes/root; new root ends in `.v2`. |
| `sigilaris.application.lock.vote.v1` | Lock signing preimage | Preserve; new signing context/contract ends in `.v2`. |
| `sigilaris.application.lock.certificate.v1` | Canonical lock certificate preimage | Preserve; new certificate ends in `.v2`. |
| `sigilaris.application.effect.vote.v1` | Effect signing preimage | Preserve; new fast-effect signing contract ends in `.v2`. |
| `sigilaris.application.effect.certificate.v1` | Canonical effect certificate preimage | Preserve; new fast-effect certificate ends in `.v2`. |
| `sigilaris.tx-pipeline.exact.plan.v1` | Verified dependency plan | Preserve; source-aware version ends in `.v2`. |
| `sigilaris.tx-pipeline.exact.identity-binding.v1` | Node/application pipeline identity binding | Preserve; new binding ends in `.v2`. |
| `sigilaris.tx-pipeline.exact.reference.v1` | Opaque reference commitment | Preserve if opaque-reference encoding is unchanged; reference semantics remain profile-bound. |
| `sigilaris.application.manifest.v1` | Canonical manifest digest, profiles sorted by id/version | Preserve; complete new version tuple and family policies use `.v2`. |
| `sigilaris.block.header.id.v1` | Historical header | Preserve unchanged. |
| `sigilaris.block.header.id.v2` | Header with optional execution-plan root, mandatory for a valid V2 header | Preserve unchanged; root/profile activation is independently checked. |
| `sigilaris.block.record.hash.v1` | Application record hash | Preserve unchanged while record codec is unchanged. |
| `sigilaris.block.body.root.v1` | Sorted complete records, not just sorted record hashes | Preserve unchanged, including canonical empty body root. |

Lock subjects canonically sort input ids before signing. Certificate encoders also canonicalize lock subjects and sort votes by signer id;
verification still rejects an empty, duplicate or noncanonical certificate subject. Effect certificate votes are sorted by signer id for encoding.
Do not treat this encoder canonicalization as permission for a v2 decoder to accept arbitrary alternate witness bytes.

## Plan-2 source, witness and opening contract

The additive plan preimage must bind an entry's execution/transaction identity, admission source, declaration and applicable canonical
classification-statement commitment. The selected source cases are ConsensusTransaction and CertifiedFastExecution; there is no system source.
The wave cases are ConflictFree, Ordered and CompatibilitySingleton. Declarations are ExactFootprint and Compatibility. Numeric discriminators
and full product order are frozen with the public v2 codec/vector definitions before accepting v2 writes.

A consensus source contains `None` exactly when the independently derived lock subset is empty, and `Some` exactly when a matching nonempty
lock certificate is required. A certified-fast source requires a nonempty lock subset, input-lock certificate and complete matching effect
certificate. Plan validation compares exact body/source membership, rejects duplicate transaction or certified execution identities, and checks
every ConflictFree entry against every other exact entry across all waves. Ordered overlap remains subject to Exact freshness and external locks.

The classification statement binds transaction/source bytes, applicable entry pre-state root, activated manifest, exact keys/expected values and
purpose. It commits semantic statement bytes, not arbitrary Merkle-proof transport. A dependent classification requires its proof artifact and
independent verification; an unused statement commitment rejects. If a profile does hash proof bytes, its full-consumption codec must reject
redundant nodes, duplicate elements, alternative ordering and equivalent alternate encodings.

Opening conversion uses an ordinary signed consensus transaction with a Compatibility declaration as the sole block entry. Its signed envelope
binds parent/boundary, manifest, authorization and last inclusion height without depending on the resulting block's own id. The application-owned
maintenance-authority verifier and deterministic conversion produce the complete actual AccessLog witness. Every existing lock-eligible mutation
must have been explicitly declared as Exact; dynamically discovered existing mutations may only be consensus-only. Reads and absent creation
targets stay in the actual footprint. Initial bootstrap opening has an independently verified empty lock subset and `None`; handover opening
keeps the normal finalized-base and lock rules. The signed deadline applies even with no locks and every reservation carries that same deadline.

Witness storage should contain one canonical complete witness referenced by its footprint commitment and a reconstructible multi-owner index,
not one duplicated full witness per identity. Authenticated identity/byte/chunk limits are consensus inputs; node-local execution/storage/recovery
capacity checks cannot change validity. Complete witness publication precedes the vote. Chunking cannot turn one opening into several transactions.

## Decoder, adapter and replay inventory

| Location | Existing dispatch or exhaustive assumptions | Required additive handling |
| --- | --- | --- |
| Core protocol sources | Byte encoders are public; the descriptor/plan/certificate files do not supply a complete public binary decoder suite. | Add explicit v2 codecs and full-consumption decode helpers; do not assume a historical transport decoder exists. |
| `node-common/.../ExactPipelineModels.scala` | Exact mode tags 1/2 and wires `orderedAtomic`/`certifiedAncestor`; lifecycle `accepted`, `lockCertified`, `effectCertified`, `included`, `finalized`, `materialized`, `expiredUnapplied`, `failed`. Envelopes dispatch absent kind/genericV1 versus exactV2. | Keep all old cases and schema-2 record/snapshot validators. New consensus lifecycle must not fake effect certification. Add exactV3 or a separate explicit new envelope. |
| `ApplicationProtocolManifestV1` JSON | Flat substrate fields; version 1 and exact M1 tuple; canonical lowercase digests; unique profile ids and verifier bindings. | Separate manifest2 decoder and validator. A larger positive protocol integer does not authenticate or activate it. |
| `node-jvm/.../application/ApplicationSafetyModels.scala` | Every stored lock/reservation/applied/terminal/drain/journal record validates schema 1 before content. Context requires protocol M1. Lifecycle codec has exactly live/applied/expiredUnapplied. | Preserve schema-1 replay; schema-2 records own vote intents, complete witnesses and multi-owner reservations. Do not mutate old journal rows to add missing vote history. |
| `ApplicationSafetyStore` / `SwayDbApplicationSafetyStore` | Journal delta replay, physical equivalence and startup reconciliation; one map row per input id in the historical snapshot. | New representation and schema need separate authoritative publication/reconstruction. Migration runs only at verified zero-live transition. |
| `node-jvm/.../block/Model.scala` | Header V1 omits version/root from bytes; V2 prefixes tag and encodes optional root. `renderPlanFailure` exhaustively matches old plan failures. `BlockProtocolActivation` selects header version only. | Keep both header codecs/hash domains. Add authenticated plan/profile dispatch, including V2-to-V2; use new failure mapping. |
| `consensus/hotstuff/SnapshotCodecs.scala` | Historical binary header decoder distinguishes V2 prefix from historical V1 parent option and rejects unsupported version/shape. | Preserve byte discrimination and full old proof decoding; new plan preimages are separately retrievable and verified. |
| `consensus/hotstuff/Materialization.scala` | Historical proposal archive uses schema byte 1. | Retain archives and their proof chains; do not relabel source history as new-chain genesis history. |
| `ExactPipelineExecutionRuntime` | Constructs old plan1, requires historical lock/effect paths; branch context stops at finalized anchor by default. | New consensus execution result/application path, authenticated historical ancestor lookup and per-profile dispatch are necessary. |

The existing Scala compiler catches exhaustive matches only when their input enums are extended. Separate additive types avoid silently changing
historical codecs, but they do not remove the need to wire every public transport, persistence and runtime entry point to the new dispatch.

## Vote and deadline durability boundary

| M2 boundary | Durable vote subject before caller can sign? | Durable deadline coverage |
| --- | --- | --- |
| `ApplicationSafetyRuntime.validateLockVote` | No, `inspect` is read-only. | Not established by this call. |
| `validateEffectVote` | No. | Requires the matching recorded live lock/deadline through effect validation. |
| `validateExactEffectVote` | No. | Also binds exact execution/dependency-plan/deadline to journaled plan. |
| `recordLockCertificate` | Imports a verified certificate; not a pre-sign vote operation. | Stores lock and advances greatest admitted deadline atomically. |
| `reserveProposal` | Creates a reservation, not an individual vote-subject record. | Bound to the existing lock's same input vector/deadline. |
| `ExactPipelineAdmissionService` | No individual subject. | Admission store only; not by itself the safety-journal boundary. |
| `ExactPipelineTransportService` and runtime registration | No individual subject. | Verified plan and deadline watermark are journaled; startup reconciles admission/journal interruptions. |

The generic lock-vote API does not enforce exact-plan binding. A deployment's “exact-only” description cannot prove coverage without retained
evidence for all subject execution/domain/deadline bindings and absence of bypass/lost history. A greatest-deadline watermark is not durable
anti-equivocation history. New public voting must atomically validate and persist the complete claim before signer invocation, retaining claims
after signer failure, cancellation or ambiguous publication. Historical validation helpers remain read-only for historical callers.

## Immutable vector register

These are required retained vector identities, not a statement that the source audit executed them. The public baseline/vector report records
the exact bytes, digest, source artifact and observed result for each. Existing implementation tests are references for extraction; release evidence
must live in the exported conformance fixture paths and run without test/private-source dependencies.

| Vector family | Required historical/new cases | Existing source seed |
| --- | --- | --- |
| `historical-input-m1` / `historical-input-m2` | Manifest/field tags; mixed read/write full, lock and footprint preimages/digests; reordered fields; duplicate stable ids; spaced family ids; unchanged v1 bytes. | `ApplicationProtocolSuite`, tagged historical source |
| `historical-execution-identity` | Full input, lock/footprint, dependency digest and signed deadline substitutions. | `ApplicationProtocolSuite` |
| `historical-plan-v1` | Both wave tags, ordered reversal, conflict-free ordering, duplicate/missing/extra members, canonical empty bytes/root with EmptyPlan rejection. | `ApplicationProtocolSuite`, `BlockModelSuite` |
| `historical-certificates-v1` | Lock/effect subject and certificate bytes; sorted votes; reordered/noncanonical/empty/duplicate lock inputs; duplicate/unknown signer, quorum, epoch and validator-set mismatch. | `ApplicationProtocolSuite` |
| `historical-block-v1-v2` | Header bytes/id, parented header, V2 plan root, record hash, body root and canonical empty body; retain all existing pinned hashes. | `BlockModelSuite` |
| `historical-exact-v2` | Request/envelope/record/snapshot JSON, both modes, verified-plan/reference/identity/manifest preimages and pinned digests, unknown tags and schemas. | `ExactPipelineModelSuite` |
| `historical-safety-v1` | All schema-1 record types, lifecycle tags, journal replay and reconciliation, empty-lock/reciprocal-reservation rejection, nonterminal/terminal outcomes. | `ApplicationSafetyRuntimeSuite`, `SwayDbApplicationSafetyStoreSuite` |
| `v2-input-independent` | Read-only, eligible/ineligible existing mutation, creation target, Exact freshness, independent full/lock/declared/actual commitments and authority/manifest substitution. | New public core fixture |
| `v2-plan-sources` | Source/declaration/classification/certificate substitution; optional consensus locks; required fast certificates; compatibility isolation; full body membership; cross-wave conflicts and allowed ordered overlap. | New public core fixture |
| `v2-empty-opening` | Empty plan/body canonical bytes/root, unchanged state root, empty-wave rejection, signed opening deadline/authentication, bootstrap empty subset, missing actual coverage and lock-eligible mutation omission. | New public core and opening fixtures |
| `v2-durable-votes` | Concurrent conflicting claims, restart, identical retry, signer failure/cancellation, unknown durable success, imported certificates, independent owner release and complete witness/index recovery. | New public runtime/store fixture |

Every change to a domain, product field order, enum discriminator, canonicalization or accepted historical language must first identify the affected
vectors. Compatibility gates compare frozen expected bytes/digests; tests that merely compare two calls to the same new implementation cannot
establish preservation of an immutable published contract.
