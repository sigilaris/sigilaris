# V2 durable voting and application integration

The additive runtime is `org.sigilaris.node.jvm.runtime.application.v2`. Its records use the
[runtime schema](v2-runtime-schema.md); the original protocol-1 stores and voters retain their historical contract.
Use one `JournalSafetyStore` for every voter, reservation and canonical application writer in one installed domain.
Independent stores do not jointly protect one signing authority.

## Configuration and authenticated requests

Configure `ApplicationRequestVerifier.authenticated` with the complete manifest, input/creation/declaration/scope authentication,
transaction signature verification, historical artifact authority, and the actual application/proposal execution repositories.
The verifier constructs its capabilities internally. Serialized subjects, commitments, caller-provided footprints, or a successful
structural decode cannot be converted directly into a verified request.

Input authentication covers every signed field. The execution repository instruments actual reads, mutations and creation, executes
entries against their respective pre-state roots, and returns complete normalized results. Creation declarations require absence
proofs even if the reducer ultimately creates nothing. Such precondition observations remain reads in the complete witness;
actual writes subsume reads of the same identity. Existing exact-mutation locks still contain only the eligible existing subset.
Scope authentication resolves the admission-base or candidate/entry/branch evidence behind its digest.

`JournalSafetyStore.open(anchor, journal, publication, profile, recoveryAuthentication, ordering, capacity)` starts with recovery.
`SafetyProfile` binds the manifest and historical cryptographic authority to the authenticated `ApplicationAnchor`.
`SafetyPublication` supplies authenticated finalized height and rechecks effect state or consensus parent/branch immediately before
claiming and signing. `ReservationOrdering` must authenticate any permitted overlap across distinct proposals; `isolated` refuses it.
Same-plan overlap requires ordered entries of that complete verified plan. Read/read reservations can coexist.

`SafetyRecoveryAuthentication.voting(requests)` verifies retained vote requests and explicitly refuses application, expiry, exact and
transition records. An application deployment uses `combine(VotingRecoveryAuthentication.authenticated(requests), applicationVerifier)`.
The original lock request, effect request, or complete proposal/plan is retained in an immutable blob keyed by its intent digest before
that intent becomes durable. Recovery reruns the configured request verifier and compares every retained owner's complete witness.
It also rechecks complete state/finality/nonapplication evidence as well as
original acquisition order, complete witnesses and actual certificate signatures. Missing historical evidence keeps startup fenced.
These adapters must use their underlying read-only repositories rather than recursively calling the same safety store while its gate is held.

Create `DurableApplicationVoting.fromStore` with that store, its validator signer, and the same trusted artifact authority. Its public
lock/effect vote operations claim before invoking the signer. Consensus preparation stores the exact existing HotStuff signing bytes
and every plan owner's witness in one transaction; an empty proposal has a real consensus intent and zero application owners.
The signing operation verifies the signer identity, canonical signature and historical membership before returning a vote.

An effect request validates its input-lock certificate before any effect certificate exists. Full certified-fast plan validation later
requires the actual effect quorum. Consensus sources use no application effect certificate, and use an input-lock certificate exactly
when their derived eligible subset is nonempty. A certificate import preserves a certificate and its subject; it does not invent a local vote.

## Durable boundary and storage profile

`MemoryDurableJournal` models the same append ordering and unknown-outcome contract as `FileApplicationJournal`.
Use the file backend's `Resource` for persistence. It holds an exclusive process/file lock for the resource lifetime and rejects stale
handles after closure. The supported deployment prerequisite is a trusted local filesystem on which the JDK's file force, atomic move
and directory synchronization operations provide their documented durability. Remote filesystems require a separately established
storage contract. Fault-injection results do not claim to reproduce hardware power loss.

Each logical operation has one complete `Prepared` record followed by the matching `Committed` record at the same sequence.
Sequences start at one. A record binds its canonical payload and predecessor; a separate durable HEAD binds the committed tip.
Append success requires full writes and `force(true)`. HEAD selection uses an atomic replacement and directory synchronization.
Immutable witness, state and evidence blobs are fully written and forced before their referencing operation can commit.
The library refuses unsupported atomic operations or failed synchronization rather than weakening this boundary.

The physical journal format is independent of the protocol wire:

| File content | Framing |
| --- | --- |
| Journal frame | ASCII `SGAJNL01`, I64 format 1, I64 record byte length, canonical record, frame hash, ASCII `SGAJEND1` |
| HEAD | ASCII `SGAHEAD1`, I64 format 1, I64 committed sequence, committed digest, HEAD hash |
| Immutable blob | ASCII `SGABLOB1`, I64 byte length, bytes, blob hash |

Frame and HEAD hashes use `sigilaris.application.storage.journal-frame.v1` and
`sigilaris.application.storage.journal-head.v1`. The blob hash uses `sigilaris.application.storage.blob.v1` and binds the
namespace, logical content identifier, framing and bytes. Physical namespaces are bounded lowercase ASCII path components.
Protocol witness and application content identifiers keep their separate selected domains. Voting evidence is bound to its durable
intent identifier, with the original canonical request preserved across same-intent retries even when an alternative valid proof exists.

The original SwayDB 0.16.2 public `put` API does not expose an explicit pre-sign force contract; it is not used as the authoritative
V2 journal. Conflict indexes and process projections are derived. A complete witness is stored once; authenticated transport chunks
are reconstructed from those exact bytes. An empty witness still has one canonical chunk.

Any uncertain write fences the shared store. A failed or canceled signer retains its already durable claim. Recovery validates the
entire physical history and referenced evidence, rebuilds indexes, and completes an intact pending operation forward. It never treats
a malformed/partial tail or lost HEAD-referenced history as an empty store. Corrupt bytes are retained for diagnosis.
A full rollback of both journal and HEAD needs the independent backup/fencing evidence in the activation contract; local checksums
alone cannot prove that an externally visible promise never existed.

## Canonical application and expiry

Configure `ApplicationCommitVerifier.authenticated` with the installed anchor, request verifier, historical validator lookup and
the embedder's actual state decoder/reducer. It verifies the existing HotStuff three-chain finality proof, candidate identity,
complete state payload, recomputed root and every normalized result before producing `VerifiedApplicationBatch`.
`RecoverableApplicationStore.journaled` shares the same safety store.

Prepare retains three immutable content classes: the state payload; the complete finality evidence; and an inventory binding the
batch, payload, finality evidence and plan. `ApplicationPrepare` atomically binds this inventory and every selected owner/witness.
Only this authenticated canonical application path can proceed despite an incompatible minority local vote. It creates no new vote
or fast certificate, and preserves the other execution's claims and deadlines. This distinction is necessary for a node that did not
vote for the block to materialize actual finality.

Commit atomically selects the prepared state/results, records every applied execution, and terminalizes all live owners and locks of
those executions. Owners selected by the batch occur first in plan order; any additional owners of the same execution follow in
digest order. Owners of other executions remain protected. `canonicalPayload` reads only the selected committed decision, so inactive
preparations are never canonical state. Repeated preparation/commit must reproduce the retained immutable identity.

Application is inclusive of the transaction's signed inclusion deadline. Later materialization or finality does not retroactively
invalidate a block included within that bound. New vote issuance requires that a future inclusion still remains. Expiry requires
authenticated same-domain finalized height strictly beyond the deadline and a complete, contiguous, verified canonical history
proving nonapplication from the installed anchor. Local clocks, abandoned candidates, canceled signers and a fresh domain do not expire claims.

The embedder must route every canonical application read through the selected decision or an equivalent decision-fenced materialization.
Copying prepared payload bytes into an independently visible mutable database before commit violates this integration boundary.
The immutable payload format is application-owned, but its configured authenticator must recompute state closure, root and ordered results;
echoing caller-supplied values is not an implementation. Restart must retain the same authenticated anchor and the proof/state repositories
needed to reproduce each decision.

## Capacity and later integration

Protocol limits are common validity rules: 100,000 witness identities, 16 MiB encoded witness bytes, 256 chunks of at most 65,536 bytes,
and at most 256 bytes per identity. `SafetyCapacity` is a local admission limit and returns a distinct capacity failure. It cannot
truncate witness coverage or change which transactions the protocol permits. Shared witnesses and terminal evidence remain retained;
compaction/retention policy requires a separate authenticated checkpoint design.

Exact registration/lifecycle, automatic HotStuff assembly, activation and migration extend this boundary in the remaining phases of
[plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md). The voting recovery authenticator deliberately
does not claim support for those records. Immutable published M1/M2 fixtures, source tests, candidate Maven staging and a later public
artifact release are distinct evidence; constructing a verifier or opening an empty directory is not deployment activation.

## Recovery and lifetime-cost limits

There is currently no authenticated destructive repair operation for malformed tails, corrupt blobs or interrupted fresh-directory layout. Retain the originals and keep the store fenced; do not truncate, delete a pending file or fabricate HEAD to obtain readiness. Full history and source reauthentication before signing remains proportional to retained history. Verified offline repair, owner binding and authenticated checkpoints are specified as open design work in [plan 0034](../plans/0034-v2-recovery-and-lifetime-cost-hardening-plan.md).
