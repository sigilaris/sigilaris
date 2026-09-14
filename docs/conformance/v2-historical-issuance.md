# Original historical issuance adapter

This is a supported deployment adapter that must exist at the original source's birth. It does not infer these records from an unmodified M1/M2 release, a later empty lock map, a transition checkpoint, or the existence of an old certificate. `HistoricalIssuanceSafety.scala` preserves a real M1 lock subject before its key can sign and authenticates the original evidence again during recovery.

## Canonical auxiliary products

The field order below is the wire order. `Long` is signed I64 big endian; `Hash` and `ExecutionId` are exactly 32 bytes; `Height` is canonical BigNat; `Text` is BigNat UTF-8 byte length and bytes; `Bytes` is BigNat length and bytes; vectors are BigNat count followed by entries; options are `00` or `01` followed by the value. `DomainContext` uses the shared core schema. All public codecs reject trailing bytes, noncanonical primitives and invalid structure. These are explicit prepublication P5 additions and do not alter the legacy codec or signed domain.

| Product | Exact field order |
|---|---|
| `HistoricalIssuancePolicy` | `format:Long=1, context:DomainContext, signerId:Text, publicKey:Bytes, lockIssuance:IssuanceCapability, effectIssuance:IssuanceCapability, maxLifetime:Long, baseUpperBound:Height, originalDeploymentEvidence:Bytes` |
| `HistoricalLockFields` | `protocolVersion:Long=1, configurationDigest:Hash, epoch:Long, validatorSetHash:Hash, executionId:ExecutionId, dependencyPlanDigest:Hash, lastInclusionHeight:Height, inputIds:Vector[InputId]` |
| `HistoricalIssuanceRequest` | `domain:Text, format:Long=1, context:DomainContext, baseHeight:Height, subject:HistoricalLockFields, sourceEvidence:Bytes` |
| `HistoricalIssuanceRecord` | `format:Long=1, sequence:Long, previousDigest:Hash, policyDigest:Hash, request:Bytes, terminalEvidence:Option[Bytes]` |
| `HistoricalIssuanceArchive` | `policy:HistoricalIssuancePolicy, records:Vector[Bytes]` |

`IssuanceCapability` retains shared tags Unavailable=1, ContinuouslyDisabled=2, Enabled=3, Unknown=4. This adapter supports protocol 1 and positive maximum lifetime. Effect issuance must be Unavailable; it has no effect-signing branch. A lock record requires Enabled, nonempty unique sorted lock inputs, the exact policy context, `baseHeight <= baseUpperBound`, and `baseHeight < signedDeadline <= baseHeight + maxLifetime`. A different subject for the same execution cannot replace an earlier record. New claims cannot conflict with retained live claims.

All new digests use the core `Commitment.hash(domain, canonicalBytes)` framing (Text domain then Bytes payload, Keccak-256). Their domains are:

- `sigilaris.application.historical-issuance.policy.v1`
- `sigilaris.application.historical-issuance.request.v1`
- `sigilaris.application.historical-issuance.record.v1`
- `sigilaris.application.historical-issuance.archive.v1`

The request's first `domain` field must itself equal the request domain above; this strictly distinguishes the original issuance request from consensus/initial controller requests. Its outer digest framing retains the same domain, intentionally covering the complete request, including that discriminator.

`HistoricalLockFields` is encoded field for field identically to the existing `ApplicationLockVoteSubject`. Encoding a request checks this equality against the actual legacy encoder. Key use signs exactly `ApplicationLockVoteSubject.signingPreimage(subject)`: the legacy Text `sigilaris.application.lock.vote.v1` followed directly by the canonical M1 subject. It does **not** add the new request's framing, chain identifier or a new signature prefix. The M1 signature has no chain identifier; the original installed policy, request and configured source interpreter select its full historical context. This adapter makes no claim that a legacy signature alone authenticates a chain.

A record with `terminalEvidence=None` is the original pre-sign claim. A record with `Some(originalProofBytes)` is the one terminal expiry of the same complete request. Sequence starts at 1, predecessor starts at zero, and every subsequent row binds its predecessor and the immutable original policy digest. Repeated successful issuance returns the existing identical claim; it cannot append a new deadline. A terminal transition never removes the original claim or its signed deadline.

## Durable ownership and current permission

`HistoricalIssuanceSafetyStore.resource(path, policy, authentication, maximumBytes, faults)` first authenticates the original policy. Its **complete canonical policy bytes**, rather than a selected later configuration digest, become the forced `CanonicalAppendLog` IDENTITY. Canonical rows use that backend's existing physical frame/HEAD checksums, complete observed-prefix preservation, file identity, barriers, exclusive ownership and poison-on-unknown contract. Recovery replays the entire original archive and repeats mandatory policy/source/finality authentication before readiness.

`HistoricalIssuanceAuthentication` is required and has four methods: `policy`, `lock`, `finalizedNonapplication`, and `currentFinalizedHeight`. `lock` must authenticate the original signed transaction, selected input eligibility and original prestate/source. `finalizedNonapplication` returns a height only after actual same-context finality and complete nonapplication evidence for that exact original execution. A number, a proof hash echoed from the request, or a newly assembled quorum is insufficient.

The public coordinated store binds the same actual `FenceController` and `ConsistencyMutationGate` used by the old source's canonical and consensus/listener writers. `issue(request)` holds that lifecycle gate across the forced claim, controller call, and key-use completion. It also holds an exact request/full-original-records temporary lease. The controller's original `authenticateLock` can authenticate historical signatures without a new permission; fresh `authorizeLock` additionally requires the actual lease, retained live claim and **current finalized height < signed deadline**. Calling `beforeLock` followed by `controller.sign` directly supplies no fresh lease. The raw uncoordinated store cannot issue a signature.

The controller separately enforces its persistent application issuance fence before any key use. An unknown key outcome retains both the original pre-sign claim and controller intent. Recovery can retry the identical still-live request under a fresh coordinated lease; it cannot invent another request or silently release the claim. The independent old writer closure prevents metadata and canonical writers from resuming after a stopped group is selected.

Expiry requires authenticated **same-context finalized height > signed deadline** and original nonapplication. Finality at the deadline is insufficient. New-context or other-chain evidence cannot release an old-context claim. The archived original deadline and terminal proof survive complete-group capture, activation and reopen. A late old certificate remains cryptographically verifiable when it contains enough genuine old signatures, including a Byzantine key's later contribution; this neither extends its signed deadline nor makes the original issuer live again. Actual V2 admission/materialization and historical replay continue to apply their profile and inclusion rules.

## Public neutral evidence

`V2HistoricalIssuanceFixture` uses original M1 lock input `0b`, existing value 10, signed deadline 2, maximum lifetime 2 and base bound 0. The input differs from the original counter mutation input `02`; original full application replay establishes nonapplication through a real finalized checkpoint. Four independent real controllers retain each signer's own full claim or absence of issuance.

Its independently signed deployment birth file is forced before any old controller, consensus-safety or application-safety store begins. If that birth file is later missing while **any** of those stores exists, installation rejects and does not recreate it. The complete policy also lives in the original issuance journal and captured application-safety namespace. The fixed complete-group layout includes that namespace's exact IDENTITY, HEAD, records and LOCK bytes; semantic validation reauthenticates its archive and actual current original store. No empty namespace is substituted at transition time.

The auxiliary neutral evidence products used by this public fixture are:

| Product | Field order |
|---|---|
| `Birth` | `format:Long=1, context:DomainContext, signerId:Text, publicKey:Bytes, enabled:IssuanceCapability, maxLifetime:Long, baseUpperBound:Height, genesisBlockId:Hash, stateRoot:Hash` |
| `Authorized` | `payload:Bytes, signature:Bytes` |
| `LockCommand` | `format:Long=1, input:InputId, expected:Long, nonce:Long, deadline:Height` |
| `LockSource` | `signedCommand:Authorized, genesisBlockId:Hash, initialPayload:Bytes` |
| `Nonapplication` | `format:Long=1, context:DomainContext, finality:FinalizedAnchorSuggestion, original:Vector[OldMaterial]` |

Birth uses `neutral.historical-issuance.birth.v1`; lock commands use `neutral.historical-issuance.source.v1`; dependency digests use `neutral.historical-issuance.dependency.v1`, each with the normal Commitment preimage. The fixture's real secp256k1 envelopes retain the existing 72-byte low-S `(v:I64,r:H32,s:H32)` signature. `FinalizedAnchorSuggestion` and original counter `OldMaterial` use the historical fixture's existing complete canonical codecs. These formats are neutral test-source adapters, not an asserted schema for unknown deployed stores.

The public `V2HistoricalIssuanceConformance.run()` and thin `V2HistoricalIssuanceConformanceSuite` execute original-before-key, strict F2/F3 expiry, retained late old quorum, four complete activation groups/reopen, controller interruption/retry, and missing-birth rejection. It also rejects transition preparation while actual original claims remain live, rejects the late canonical M1 certificate bytes through the strict V2 certificate decoder, and rejects its old-context or relabeled-new-context original request through the actual V2 request verifier while the activated journal/HEAD and canonical-state HEAD bytes remain unchanged. These JVM auxiliary formats are checked by strict original codec roundtrips, actual physical row decoding and legacy byte identity; they are **not** among the separately frozen 55 shared core transition golden vectors. JVM/JS core schema vectors do not establish physical storage or original deployment completeness.
