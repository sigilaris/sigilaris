# Historical profile selection and handover execution

This document records the P5 historical products and their current implementation. They are additive products. Published M1/M2 proposal, vote, QC, header, block-body, plan and transaction codecs remain their original languages. Header V2 alone does not select application protocol V2: both legacy milestones can carry header V2 and plan V1.

## Required original deployment evidence

`AuthenticatedHistoricalProfiles.pinned` accepts complete canonical configuration bytes only against an independently installed digest. Ranges select by the actual chain, height and validator-set hash; configuration and epoch come from the selected immutable range. Missing heights, overlaps, gaps, reordered ranges, unexpected header versions and manifest/context substitutions fail closed. Each chain starts at height zero and ends in one unbounded range.

Supported live handover additionally requires a source deployment that retained its actual safe-vote decisions **before signing**. `HistoricalConsensusSafetyStore` is one concrete supported adapter. Its separately forced original records retain full proposals, highQC/lockedQC evolution, signing windows and fence decisions, and its controller exclusively owns the key. The configured safe-vote rule requires descent from the retained locked block and increasing `(height, view)` windows. Recovery replays the original records and independently verifies signatures, QCs, profiles, parent links and application execution.

Unmodified M1/M2 deployments that did not retain those facts are unsupported for this adapter. A pacemaker snapshot, current lock map or transition-time reconstruction cannot supply missing original lockedQC, signing watermarks or issuance history. The independent birth configuration is original evidence, not a synthesized transition checkpoint.

`HistoricalContinuationAuthentication` reads the actual current key-owning controllers and original safety stores. It checks the complete historical validator/key roster, exact original archive bytes, actual F-to-P parent/QC/root continuity and descent from every retained lock. Possible signing intents include uncertain key use. Fence promises are checked against the controller prefix immediately before their original enforcement; permitted old progress below B does not rewrite that promise's watermark. Current complete controller inventory is retained separately and determines the final maximum possible old signing height.

## Canonical encoding

Products below use the existing V2 canonical primitives: `Long` is signed 64-bit big-endian, enum tags are one byte, hashes are fixed 32 bytes, heights use canonical nonnegative `BigNat`, and `Text`/`Bytes`/vectors use canonical `BigNat` lengths. `Option` uses its existing closed tag. Fields concatenate in the listed order. Full-consumption decoding rejects noncanonical encodings and unknown tags. Original `Proposal`, `QuorumCertificate` and `FinalizedAnchorSuggestion` use their unchanged HotStuff codecs. Structural decoding does not grant signature, execution or activation authority.

`H(domain, bytes)` means the existing `Commitment.hash` over the canonical domain-framed preimage. No additional implicit fields or current-profile values enter these hashes.

| Product | Ordered fields |
| --- | --- |
| `HistoricalArtifactProfile` | `release:Tag`, `headerVersion:Long`, `manifest:Option[ProtocolManifest]` |
| `HistoricalProfileRange` | `firstHeight:Height`, `lastHeight:Option[Height]`, `context:DomainContext`, `profile:HistoricalArtifactProfile`, `originalConfigurationEvidenceDigest:Hash` |
| `HistoricalProfileConfiguration` | `format:Long=1`, `ranges:Vector[HistoricalProfileRange]` |
| `HistoricalSafetyProfile` | `format:Long=1`, `context:DomainContext`, `voter:Text`, `initialHighQc:QuorumCertificate`, `initialLockedQc:QuorumCertificate`, `originalDeploymentEvidenceDigest:Hash` |
| `HistoricalSafetyRecord` | `format:Long=1`, `sequence:Long>0`, `previousDigest:Hash`, `profileDigest:Hash`, `operation:Tag`, `proposal:Option[Proposal]`, `certificate:Option[QuorumCertificate]`, `fenceBoundary:Option[Height]`, `signBytes:Bytes` |
| `HistoricalIssuancePolicy` | `format:Long=1`, `context:DomainContext`, `signerId:Text`, `publicKey:Bytes`, `lockIssuance:Tag`, `effectIssuance:Tag`, `maxLifetime:Long`, `baseUpperBound:Height`, `originalDeploymentEvidence:Bytes` |
| `HistoricalLockFields` | `protocolVersion:Long=1`, `configurationDigest:Hash`, `epoch:Long`, `validatorSetHash:Hash`, `executionId:ExecutionId`, `dependencyPlanDigest:Hash`, `lastInclusionHeight:Height`, `inputIds:Vector[InputId]` |
| `HistoricalIssuanceRequest` | `domain:Text`, `format:Long=1`, `context:DomainContext`, `baseHeight:Height`, `subject:HistoricalLockFields`, `sourceEvidence:Bytes` |
| `HistoricalIssuanceRecord` | `format:Long=1`, `sequence:Long>0`, `previousDigest:Hash`, `policyDigest:Hash`, `request:Bytes`, `terminalEvidence:Option[Bytes]` |
| `HistoricalIssuanceArchive` | `policy:HistoricalIssuancePolicy`, `records:Vector[Bytes]` |
| `HistoricalCertifiedMaterial` | `proposal:Proposal`, `certificate:QuorumCertificate` |
| `HistoricalSafetyArchive` | `profile:HistoricalSafetyProfile`, `originalRecords:Vector[Bytes]` |
| `HistoricalContinuationProof` | `format:Long=1`, `drain:FinalizedAnchorSuggestion`, `suffix:Vector[HistoricalCertifiedMaterial]`, `safety:Vector[HistoricalSafetyArchive]` |
| `HistoricalExecutionMaterial` | `planBytes:Bytes`, `sourceAndStateProof:Bytes`, `canonicalStatePayload:Bytes`, `normalizedResults:Vector[Bytes]` |
| `HistoricalCanonicalAdvance` | `format:Long=1`, `handoverDigest:Hash`, `context:DomainContext`, `parentBlockId:Hash`, `blockId:Hash`, `height:Height`, `priorStateRoot:Hash`, `nextStateRoot:Hash`, `profileDigest:Hash`, `proposal:Proposal`, `certificate:QuorumCertificate`, `finalized:FinalizedAnchorSuggestion`, `material:HistoricalExecutionMaterial` |
| `HistoricalCanonicalRecord` | `format:Long=1`, `sequence:Long>0`, `previousDigest:Hash`, `advance:HistoricalCanonicalAdvance`, `advanceDigest:Hash`, `status:Tag` |

Release tags are `LegacyM1=1`, `LegacyM2=2`, `ApplicationV2=3`. Legacy profiles require protocol 1 and no V2 manifest; V2 requires header 2 and the full matching manifest. Profile ranges sort by canonical UTF-8 chain bytes, then first height.

Safety operation tags are `VoteIntent=1`, `ObserveQc=2`, `Fence=3`. VoteIntent contains only a proposal and nonempty original signing bytes. ObserveQc contains a proposal and its actual certificate with empty signing bytes. Fence contains only its boundary with empty signing bytes. Every other optional field combination is rejected. The first record has sequence 1 and zero previous digest. A failed or uncertain key operation does not erase its preceding VoteIntent.

Canonical status tags are `Prepared=1`, `Committed=2`. Each pair has identical sequence, previous digest, advance and advance digest. The next pair references the committed record digest. The first pair has sequence 1 and zero previous digest. Recovery reauthenticates the full old-profile advance before resolving a prepared tail.

| Digest | Domain and exact payload |
| --- | --- |
| Profile range | `sigilaris.application.historical-profile.range.v1` + canonical `HistoricalProfileRange` |
| Profile configuration | `sigilaris.application.historical-profile.configuration.v1` + canonical `HistoricalProfileConfiguration` |
| Original safety profile | `sigilaris.hotstuff.historical-safety.profile.v1` + canonical `HistoricalSafetyProfile` |
| Original safety record | `sigilaris.hotstuff.historical-safety.record.v1` + canonical `HistoricalSafetyRecord` |
| Original signing watermark | `sigilaris.hotstuff.historical-safety.watermark.v1` + canonical `Option[HotStuffWindow]` |
| Original safety inventory | `sigilaris.hotstuff.historical-safety.inventory.v1` + profile digest + last record digest + signing watermark digest |
| Original issuance policy | `sigilaris.application.historical-issuance.policy.v1` + canonical `HistoricalIssuancePolicy` |
| Original issuance request | `sigilaris.application.historical-issuance.request.v1` + canonical `HistoricalIssuanceRequest` (including its fixed domain field) |
| Original issuance record | `sigilaris.application.historical-issuance.record.v1` + canonical `HistoricalIssuanceRecord` |
| Original issuance archive | `sigilaris.application.historical-issuance.archive.v1` + canonical `HistoricalIssuanceArchive` |
| Historical proposal evidence | `sigilaris.application.historical-proposal.evidence.v1` + unchanged canonical `Proposal` |
| Historical quorum evidence | `sigilaris.application.historical-quorum.evidence.v1` + unchanged canonical `QuorumCertificate` |
| Historical result inventory | `sigilaris.application.historical-result.inventory.v1` + canonical `Vector[Bytes]` of ordered normalized results |
| V2 historical replay evidence | `sigilaris.application.historical-replay.evidence.v1` + proposal evidence digest + canonical state blob digest + result inventory digest + execution plan root |
| Continuation proof | `sigilaris.application.historical-continuation.proof.v1` + canonical `HistoricalContinuationProof` |
| Canonical advance | `sigilaris.application.historical-canonical.advance.v1` + canonical `HistoricalCanonicalAdvance` |
| Canonical record | `sigilaris.application.historical-canonical.record.v1` + canonical `HistoricalCanonicalRecord` |

Legacy replay evidence remains defined by its original authenticated application adapter. Its bytes are not silently interpreted by the V2 replay implementation. Lookup capacities are local availability limits and return typed unavailability; they do not introduce a protocol maximum history length.

## F, P and the activation boundary

F is the actual old finalized canonical state. P is the certified continuation parent at B−1, obtained by re-executing the complete original suffix. Installing P makes it a working execution parent; it does not finalize P or publish its state as canonical.

`HistoricalCanonicalVerifier.verifyInstallation` issues the private `HandoverInstalledBase` only after rechecking the handover and an actual `VerifiedActiveGroup` from the atomically committed activation journal. An inactive migration payload is insufficient. The capability retains the exact activation journal/controller affinity, original F payload, original P working payload and original suffix proofs. A target journal with the same context and root cannot borrow another journal's installation.

The additive historical canonical ledger preserves old-profile proposals, QCs, finality proofs, source/state bytes and ordered results. It never converts an old execution into a target-context `ApplicationBatch`. This auxiliary ledger is a prepublication P5 addition; it does not reinterpret the frozen 14 safety journal operations or the existing block-body root.

`catchUp` verifies the complete actual certified branch and reconstructs real three-chain finality for each old advance before the first append. A new B finality can therefore prove P through its original P/B/B+1 chain. A larger height alone cannot prove P finalized. Forks, gaps, missing originals, wrong profiles and cycles fail closed. The handover finalizer reports global canonical F while P remains working, requires authenticated historical canonical P before the first target ApplicationPrepare, and subsequently publishes ordinary V2 application commits. Recovery checks the same ordering across both journals. Ambiguous historical ledger I/O closes the shared application signing readiness boundary.

## Recorded validation

The following actual gates have passed during P5 development:

- Six `V2HistoricalConformance` cases cover independent original controllers, full M1/M2 replay and F3/P5 distinction, original fence prefixes with permitted old progress, actual quorum scope, uncertain key use, full reopen, historical producer rejection, pure-cache key changes, original source loss and authenticated pre-F finality.
- Five `V2HistoricalProfilesConformance` cases cover immutable range selection and malformed profile configuration.
- `V2HistoricalGroupConformance` performs actual four-controller F3 canonical publication, complete capture, migration, atomic activation and four controller/journal reopen. Post-capture original safety writes and signing fail without changing the original archive.
- `V2HistoricalRuntimeFixture.run` completed F3→old4→P5→V2 B6, four actual target lock votes and three-voter certificate import, four full controller/journal reopens, unchanged live D10 lock retry and B7 continuation (304.212 seconds; the same final source run also passed in 289.337 seconds). Its lock uses independently verified finalized F3, not working P5, and rejects changed roots, old-domain relabelling and loss of the retained original signed lock source even for an already prepared request.
- `V2HistoricalDrainFixture.run` passed the JournalCovered, InferredHorizon and NeverEnabled routes against original birth policies and all four physical issuance stores; actual finality2 equal to the original inferred deadline2 remains stalled (22.29 seconds).
- `V2HistoricalCanonicalFaultFixture.run` passed through the installed historical materializer: forced Prepared uncertainty, whole-tail forward recovery, uncertainty after recovered Committed HEAD force, another four-node reopen, partial-frame rejection, rollback behind HEAD rejection and original coverage loss rejection (110.598 seconds).

`V2HistoricalTransportFixture.run` passed the actual ordinary four-node scenario in 1676.745 seconds: four independent installed controllers, four ready target journals, no raw local key map, actual transported three-voter QCs at B6/B7/B8, B6 three-chain finality and target application on all four nodes. The first scenario completed every assertion and resource cleanup. Scala CLI then discovered the same suite again through its test classpath; that duplicate run alone was terminated (runner exit 143), so no full-runner exit-zero is claimed. Earlier interrupted transport attempts are not passes. The final repository/artifact gates belong to P6.

The transport fixture installs the complete actual signed source 0..5 archive into each ordinary sink once, then sends only newly emitted B6+ proposal/vote artifacts through the twelve directional Gossip sessions. Every archived proposal and vote still passes the ordinary sink validation; no source height, QC, application replay or profile check is skipped. Explicit ordinary public emission makes this boundary run deterministic; P4 separately exercises automatic scheduling. The measured transport pass predates the subsequent fixture-only original-counter signature memoization; the changed-key/command/signature negatives and all six historical cases passed against that final change. The final source/artifact run reuses the same public transport body without duplicate suite discovery.

The additional lock execution exercises actual installed target voting, certificate import and original-source recovery. It is independent of the consensus-only execution included at B6; the lock gate does not claim to include that additional execution in a block. The enabled original issuance adapter has its own public gate and evidence described below.

Repeated historical verification uses a bounded positive cache of deterministic cryptographic success. Keys contain the full immutable Proposal/QC and complete current and justify validator member/key vectors. Controller structural history also caches only complete immutable successful snapshots. The neutral fixture separately memoizes only successful original counter signature equations, keyed by the complete signed command and actual 64-byte public key, with at most 16 entries of at most 65536 canonical bytes each. Changed key, command and signature regressions remain rejected after a hit; larger inputs simply recompute. Every call still performs actual profile/validator lookup, original source/state authentication, current file checks and enforcement checks. Cache saturation recomputes validation. Readiness, ancestry, file availability and signing permission are never cached as authority.

Valid finality older than F is a zero-advance observation only after actual three-chain signatures and full original F-to-target parent/application replay prove ancestry. At source genesis, the actual signed genesis, child1/child2 proof and child1 original prior-state replay are required. Neither a missing parent nor a fabricated genesis proposal is accepted.

## Original live issuance and three drain routes

`HistoricalIssuanceSafetyStore` is an explicit supported original deployment adapter, installed before original key use. Its independently authenticated full birth policy is forced into the issuance journal identity and the controller's signed birth closure. The supported policy currently permits original M1 lock issuance, with effect issuance unavailable. An unmodified legacy application store cannot be treated as this adapter.

The request domain is exactly `sigilaris.application.historical-issuance.request.v1`. Its subject bytes must equal the published `ApplicationLockVoteSubject` encoder field for field. The actual signature preimage remains the published `ApplicationLockVoteSubject.signingPreimage`: no V2 domain or new request fields replace the historical signing language. Issuance capability tags retain the transition schema's `Unavailable`, `ContinuouslyDisabled`, `Enabled` and `Unknown` encodings.

A forced record with no terminal proof introduces the immutable subject/deadline before key use. A terminal record retains that exact request and the actual original-domain finality/nonapplication proof; finality must be strictly above the signed deadline. The coordinated `issue` method holds the original mutation gate through force and controller signing. A separate `beforeLock` or direct controller call cannot create the exact active signing lease. All source metadata mutations and group capture use that same gate instance.

Drain coverage reauthenticates every original birth policy, complete per-validator archive and possible original-domain application key intent. Duplicate signer votes for one subject do not duplicate its execution or its deadline. Every actual original signer remains covered by its own forced records. A live original claim prevents the completed coverage baseline from being fixed, so a stalled attempt can later retry after real expiry. Target-context signatures cannot be mistaken for uncovered old issuance.

The public enabled gate accepts original claims before the fence, rejects expiry at actual F2=D2, expires at actual F3>D2, captures and atomically activates the complete source group, reopens original and target journals, and authenticates a late old quorum assembled from two original honest votes and one later Byzantine vote. The old signed deadline and terminal original claims remain unchanged; signature validity does not grant V2 execution permission. `V2HistoricalIssuanceConformance.run` passed all three public cases, including uncertain-before-key and lost-original-birth failures. Its late M1 certificate is first validated under the actual old signing language, then rejected by the V2 certificate codec and target request verifier under both original and relabelled contexts; target journal/HEAD and source canonical-state/HEAD remain byte-for-byte unchanged.

The [standalone public runner](../../release-conformance/README.md) executes these exported case bodies from selected Maven artifacts. [P6 evidence](p6-gate.md) records the final source and artifact results independently of the thin private source-test wrappers.
