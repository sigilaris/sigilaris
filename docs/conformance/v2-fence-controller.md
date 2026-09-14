# P5 persistent controller: exact prepublication addition

This is an additive JVM controller boundary. It does not change existing HotStuff,
application vote, fence, journal-operation-9, or bootstrap certificate preimages.
In particular a signed Fence journal record is not evidence that the unsigned
promise was forced before its key was used. The controller supplies that boundary.

## Public integration

`FileFenceController.resource(path, keyPair, signerId, originalInitialEvidence,
authentication, writer, faults, maximumBytes)` acquires one physical path and one
same-process signing-key owner, the OS file lock, and performs complete recovery.
It returns `FenceController`; the private resource object retains the key. All
ordinary signing and canonical writer paths for this key/controller must use it.
Callers should provide an actual canonical physical path: symbolic links in the
selected path or any ancestor are rejected, including macOS `/var` or `/tmp`
aliases; `Path.toRealPath()` on an independently selected existing parent supplies
the physical location. Missing intermediate directories are not manufactured.

`sign(request)` calls the required original-request authenticator to reconstruct
exact context, signing kind, height, and original preimage. It forces the complete
request/material intent, uses the key under the same gate, then forces its actual
72-byte low-S signature before returning. Existing intents are immutable. A
cached signature is not released through `sign` after its scope has been fenced;
read-only audit retains the original signature. A pending signature intent is
conservatively possibly issued and is never automatically re-signed in recovery.
Bootstrap and normal HotStuff adapters use their real canonical vote/proposal
request and original existing preimage here. There is no caller-supplied raw-key
callback or a generic `withSigningPermission` Boolean.

`writeCanonical(request)` forces the exact request/material before calling the
once-installed idempotent durable CAS writer. The writer's original immutable
effect evidence is forced in the completion, reverified on restart, and forward
replayed if its completion was interrupted. Unknown outcomes close the controller.
The fail-closed `ControllerCanonicalWriter.unavailable` is suitable only when no
writes exist; an unresolved write requires the real original writer before Ready.

`prepareFence(intent, context, scope, boundary)` builds a draft from the current
complete signing prefix and conservative watermark. `enforce(intent, promise)`
revalidates the exact configured transition policy and current prefix, forces the
original unsigned `ControllerEnforcement`, installs its permanent guard, then
uses the key for the dedicated existing FencePromise preimage and forces the
SignedFencePromise receipt. An exact pending administrative fence can complete
after fencing; ordinary signatures remain prohibited. An intervening signing
intent invalidates a stale draft. No abort, receipt failure, or restore removes
an unsigned enforcement.

Scope 1 closes application lock/effect issuance for the exact context. Scope 2
closes consensus signing at or above B for the exact context. Scope 3 closes all
ordinary signing and canonical writes for the source/retired **chain id**, across
its contexts. This prevents an epoch/configuration relabel from reopening a
retired chain, while scopes 1/2 permit the separately authenticated new same-chain
profile in a handover.

`withStopped(lease => ...)` holds the same controller gate after durable signing
fences cover every initially installed or subsequently observed context. An independently authenticated fresh key with no contexts or prior history permits the empty stopped set; its first actual signing context then requires its own fence. Public
sign/write/recover operations refuse while this lease is active. The private
lease exposes only a metadata snapshot and `closeWrites(intent, oldContext)`.
The latter authenticates the installed transition and irreversibly forces the
old-context canonical-write closure **before** group capture or future activation
preparation/decision. It does not bind a future decision digest and introduces no
hash cycle. Same-chain target-context writes remain eligible through their own
actual request authentication. The lease drains in-flight private operations
before closing; an escaped lease refuses after the callback ends. Do not
recursively acquire controller/outer runtime gates in callbacks. Complete group
capture must additionally stop old consensus/listener/index mutations through
the actual configured runtime lifecycle scope; this controller does not pretend
that arbitrary independent mutable stores share its semaphore.

`preserveAfterRestore(backup)` verifies that backup is a prefix and returns the
complete **current** snapshot; it never replaces files or deletes newer promises,
watermarks, writes, or write closures. It grants no application-state rollback
permission. Recovery also requires the selected disk history to contain the
already observed live-process prefix. Whole-disk/key-copy rollback after process
loss still needs independently retained enforcement/baseline controls.

`audit` returns actual Ready snapshot, canonical configuration/record artifacts
and their `InventoryEntry`s, every possibly issued signing intent, actual signed
receipts, enforced unsigned promises, signed fence receipts, and write closures.
These are evidence, not capabilities. A historical fence's signing-history
binding references the prefix immediately before its Enforcement record; later
allowed low-height signatures remain in the current snapshot and never rewrite
the old promise's watermark. Auditors can locate that record and take its strict
prefix, then recompute `ControllerSigningHistoryBinding.digest` and the matching
scope's maximum height. For an externally imported initial-history fence, its
independent original seed proof must establish the pre-controller prefix.

`ControllerOperationAuthentication` is mandatory. It parses original proof bytes
and independently proves precise historical profile/manifest context, authorized
requests, complete prior key use, and installed transition/old-writer closure
policy. Reopening verifies these originals again. The completed seed format may
not drop unresolved preexisting intentions or invent signatures; those require
original controller recovery or rejection. There is no permissive default
implementation. `ControllerCanonicalWriter.verify` verifies actual immutable
effect evidence, including superseded writes. The neutral tests implement real
signed original authority requests and independently retained seed rosters,
plus a real physical file CAS writer; they do not certify any production binary,
configuration, filesystem, key roster, or deployment.

## Exact auxiliary canonical schemas

Use the existing v2 codec primitives: Long=I64BE; enum=U8 tag; Hash=H32;
Text/Bytes=BigNat byte length then UTF-8/raw bytes; vectors=BigNat count then
ordered products; Option=0/1 then optional product; Height=canonical BigNat.
Every codec rejects trailing bytes. Field order below is authoritative for this
prepublication addition. These are local evidence contracts, not new HotStuff
signing domains.

| Product | Fields in order |
| --- | --- |
| ControllerSigningMaterial | context:DomainContext, kind:ControllerSigningKind, height:Option[Height], canonicalPreimage:Bytes |
| ControllerSigningIntent | request:Bytes, material:ControllerSigningMaterial |
| ControllerSignature | intentDigest:Hash, signature:Bytes |
| ControllerWriteMaterial | context:DomainContext, namespace:Text, key:Text, priorDigest:Option[Hash], payload:Bytes |
| ControllerWriteIntent | request:Bytes, material:ControllerWriteMaterial |
| ControllerWriteReceipt | intentDigest:Hash, effect:Bytes |
| ControllerEnforcement | intent:TransitionIntent, promise:FencePromise |
| ControllerFenceSignature | enforcementDigest:Hash, promise:SignedFencePromise |
| ControllerObservedSignature | intent:ControllerSigningIntent, signature:Bytes |
| ControllerObservedWrite | intent:ControllerWriteIntent, effect:Bytes |
| ControllerObservedFence | enforcement:ControllerEnforcement, promise:SignedFencePromise |
| ControllerWriteClosure | transition:TransitionIntent, context:DomainContext |
| ControllerInitialHistory | contexts:Vector[DomainContext], signatures:Vector[ControllerObservedSignature], writes:Vector[ControllerObservedWrite], fences:Vector[ControllerObservedFence], writeClosures:Vector[ControllerWriteClosure] |
| ControllerConfiguration | format:Long=1, signerId:Text, publicKey:Bytes, initialEvidence:Bytes, initialHistory:ControllerInitialHistory |
| ControllerRecord | format:Long=1, sequence:Long, previousDigest:Hash, kind:ControllerEventKind, payload:Bytes |
| ControllerSnapshot | format:Long=1, configuration:ControllerConfiguration, records:Vector[ControllerRecord] |
| ControllerSigningHistoryBinding | format:Long=1, snapshotDigest:Hash, context:DomainContext, scope:FenceScope |

Signing-kind tags are ApplicationLock=1, ApplicationEffect=2, Consensus=3. Event
payload tags are Enforcement=1/ControllerEnforcement, FenceSignature=2/
ControllerFenceSignature, SigningIntent=3/ControllerSigningIntent, SigningResult=4/
ControllerSignature, WriteIntent=5/ControllerWriteIntent, WriteResult=6/
ControllerWriteReceipt, WriteClosure=7/ControllerWriteClosure. There is exactly
one payload codec per tag. Sequence starts at one with previous H32 zero, then
increases by one and hashes the entire preceding canonical ControllerRecord.
Duplicate intent/completion identities and unexpected completion relationships
fail; initial observed identities are unique. Logical hashes are the existing
`Commitment.hash(domain, canonicalProductBytes)`.

Domain prefix is `sigilaris.application.controller.` with suffixes:
`sign-intent.v1`, `write-intent.v1`, `enforcement.v1`, `write-closure.v1`,
`record.v1`, `configuration.v1`, `snapshot.v1`, `signing-history.v1` for the named
products. `write-content.v1` hashes raw canonical payload content. `file.v1`
hashes canonical file payload bytes for physical checksums. Signature bytes are
existing 72-byte v:I64BE + r:H32 + s:H32 with existing range/low-S validation.

Audit inventories use namespace `controller-configuration`, key signerId, digest
ControllerConfiguration.digest, and namespace `controller-records`, key the
record sequence as a left-zero-padded unsigned H32 hex string, digest
ControllerRecord.digest. Entries sort by unsigned UTF-8 namespace then key, and
feed the previously selected `TransitionInventory` codec/digest. All original
bytes accompany their inventory entries. Audit projection case classes themselves
have no additional wire codec.

## Files and recovery

`IDENTITY` binds the complete immutable ControllerConfiguration. `LEDGER` binds
the complete ControllerSnapshot. Both have ASCII `SIGFENCE1` (9 bytes), H32
`Commitment.hash(controller.file.v1, canonical payload)`, then canonical payload.
`LOCK` is the exclusive OS lock. `pending-<UUID>` files contain complete framed
candidate snapshots. Any other file fails closed. Identity is forced before
initial ledger creation; interrupted identity/layout creation is not treated as a
fresh controller. Every event writes a new complete snapshot to a new pending
file, forces the file, atomically replaces LEDGER, and forces the directory before
success or key/effect admission. Every mutation compares physical history to its
known current snapshot first. The default maximum canonical snapshot size is
268435456 bytes, an explicit local capacity only, not a protocol validity limit.

Complete pending snapshots must form one compatible prefix chain with LEDGER and
the live-process observed snapshot. Recovery verifies canonical checksums/records,
actual signatures and original authentication, forces the selected full snapshot,
then forward-completes original pending writes. Partial or corrupt pending bytes
remain unchanged and fence recovery. Unknown write/key-publication outcomes keep
the controller poisoned. Unsupported filesystem durability barriers do not fall
back to weaker operations. Existing superseded complete prefix files may remain;
no incomplete file is truncated or interpreted as absent history.

## Evidence and review

Standalone Scala 3.7.3 main passes repository-equivalent Wart 3.4.1 `allBut`
SeqApply/SeqUpdated, `-Wunused:all`, and `-Werror`; see `controller-strict.py`.
`controller-test.py` runs the actual file suite. The current 23 cases cover
unsigned and signed record publication at all four physical barriers, actual
pre-sign disk inspection, signature recovery, uncertain issuance watermarks,
old-key seed signatures, source chain fencing across epochs, same-chain target
writes with durable old-context closure, actual CAS interrupted after its effect,
complete forward recovery, partial-file preservation, exact administrative
retries, post-backup safety preservation, forged original requests, stale drafts,
active ledger corruption, same-process valid old-ledger rollback, exclusive key/
path ownership, stale handles, and stopped admission/escaped lease behavior.

Review corrections included: permanent predecision old-context write closure to
avoid a future-decision hash cycle and commit-to-retirement crash gap; lease-local
serialization/draining for escaped asynchronous callbacks; complete live-process
prefix retention on recovery; public typed acquisition failures; and explicit
completed-seed versus unresolved-original-history handling. Initial twelve tests
passed, then signed-completion/negative-history cases expanded the gate to 22.

Current key-use permission is a separate mandatory `ControllerOperationAuthentication.authorizeSigning(request, signerId, publicKey)` check. `signing` authenticates immutable original requests and historical evidence during replay. `authorizeSigning` runs after the full intent is forced and immediately before each actual new or uncertain-retry key use; it may check current finality, ancestry and exact readiness. Recovery and returning an already completed identical signature do not reinterpret that historical signature using current readiness. The 23rd physical-controller regression verifies this separation and counts actual key uses.

`ControllerSnapshot.validateHistory` checks canonical record-chain, receipt, signature and fence ordering for installed namespace decoders. It returns no capability and does not replace original request/authority authentication, physical controller recovery, or current signing authorization.

Pure controller history reconstruction keeps at most 16 successful complete immutable snapshots, each with at most 1 MiB canonical bytes and 4,096 records. These are cache admission limits only: larger valid histories are fully verified without retention. The key includes the full configuration, initial history and every ordered record. Failures are not cached. Physical identity, observed-prefix preservation, original proof authentication and fresh authorization still run independently on every controller recovery or signing operation. Changed-key, record-byte and ordering regressions exercise this boundary.

Malformed pending files remain fenced; the library does not currently expose an authenticated discard/repair operation. Completed superseded snapshots can also accumulate. Receipt-backed quarantine, cleanup and lifetime-cost reduction are open work in [plan 0034](../plans/0034-v2-recovery-and-lifetime-cost-hardening-plan.md); do not remove files to force recovery.
