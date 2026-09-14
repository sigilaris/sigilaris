# P2 public request and durable voting evidence

The exported JVM fixtures use only public core and node-jvm APIs:

- [V2RequestConformance](../../release-conformance/shared/v2-jvm/scala/org/sigilaris/conformance/V2RequestConformance.scala)
  verifies signed source, input authority, exact values, declared/actual accesses,
  complete creation absence and reservation ownership before creating a capability.
- [V2VotingConformance](../../release-conformance/shared/v2-jvm/scala/org/sigilaris/conformance/V2VotingConformance.scala)
  calls the actual journal-backed voter, with independent stores for individual
  validators, real deterministic signatures and controlled failure boundaries.

Repository wrappers are `V2PublicRequestConformanceSuite`,
`V2PublicVotingConformanceSuite`, `V2PublicOrderedVotingSuite` and
`V2PublicReservationScaleSuite`. Their shared fixture functions are callable
without importing private repository tests. The files live in
`release-conformance/shared/v2-jvm/scala`; standalone staged/public artifact
execution remains the P6 gate. Source tests do not establish publication of
`0.3.0-M3-SNAPSHOT` or conformance of an unrelated installed binary.

## Authenticated request boundary

`ApplicationRequestVerifier.authenticated` requires the selected manifest and
configured transaction, input, declaration, declared-creation, artifact and
scope authenticators plus execution/history repositories. These dependencies
resolve actual trusted material. A descriptor, witness or authorization digest
alone cannot produce a `Verified*` value. Capability constructors are private
to the verifier object, with no public factory, `copy`, decoder or Boolean
promotion path.

Lock verification checks real source signatures, signed manifest/declaration/
dependency/deadline binding, recomputed execution identity, finalized admission
base, exact input values and authority, complete full-input commitment and the
independently derived eligible lock subset. Imported lock certificates also
resolve that source closure after quorum verification.

Effect verification uses a distinct `ExecutedEffect` before an effect quorum
exists. It requires the matching authenticated lock certificate and independently
executed result/state/access trace, with no completed fast-source plan or effect
certificate dependency. The fixture's effect repository deliberately reports
unavailable; a valid first effect request still succeeds. Complete plan sources
are checked separately when verifying a proposal.

Every declared creation target is resolved and authenticated, including targets
unused by the reducer. Absence precondition checks enter the complete actual
footprint as reads; real creation events retain write access and subsume those
reads. Existing-state access to a proven-absent target rejects. The fixture
checks a valid unused target, its read reservation, and missing proof or
contradictory existing-read rejection.

The fixture's neutral immutable interpreter records accesses at its read,
mutation and creation operations. The verifier independently compares the
trace, declared commitments, normalized result, post-state, absent-target
proofs and complete witness. Changes to a consensus-only mutation, read,
creation proof, result, owner or certificate reference are rejection cases.

Scope authorization resolves a retained evidence artifact and checks its
actual commitment and signatures. Fast ownership binds the admitted finalized
base; consensus ownership binds the entry, plan and authenticated parent/QC.
Matching scope coordinates with a forged authorization digest reject.
Proposal verification also checks the existing HotStuff signature/QC, exact
body and plan membership, sequential pre/post states, complete reservations
and exact unsigned vote. Private capabilities retain the authenticated
pre-state/parent and normalized results for later publication/application checks.

## Durable voting scenarios

| Scenario | Required observation |
| --- | --- |
| Simultaneous conflicting lock requests released by a barrier | Exactly one succeeds; the loser remains rejected after reopening. |
| Same-subject retry | Identical signature and no duplicate journal transition. |
| Signer exception or cancellation | Complete intent/eligible claim remains; conflicting retry rejects after recovery. |
| Unknown journal write before signer invocation | No signer call; runtime fences and recovery conservatively preserves the promise. |
| Split honest votes plus Byzantine double signing | Four separate stores; one 3-of-4 quorum and one insufficient 2-of-4 set; honest voters cannot supply the missing conflicting vote. |
| Certificate import | Verified source/quorum is retained without manufacturing a local vote intent or effect reservation; a v1–v3 certificate coexists with v4’s unfinished conflicting minority, retaining both eligible protections after restart. |
| Complete effect reservations | Reads, consensus-only writes and creation targets are present; two disjoint writers may share a read identity. |
| Reservation/lock acquisition in both orders | A lock-free consensus reservation rejects a conflicting fast lock, and a prior fast lock rejects that reservation; both decisions survive reopening. |
| State/deadline changes after verification | Old effect and prepared consensus capabilities cannot publish; live claims are retained. |
| Prepared consensus restart | Exact legacy vote preimage is retained; the old token requires renewed verification/preparation before signing. |
| Persistent file reopen | Real lock/effect signatures, intents, claims and rebuilt indexes match after closing and reopening the file journal. |

The [ordered fixture](../../release-conformance/shared/v2-jvm/scala/org/sigilaris/conformance/V2OrderedVotingConformance.scala)
executes two independently signed consensus stages against consecutive working
states. The first writes a consensus-only counter and the second reads its new
value; the first also reads a cell that the second subsequently mutates. Both owners have different authenticated entry authorization digests but
share one parent, height and ordered plan. One consensus intent retains both
complete overlapping reservations through restart. Missing branch artifacts,
wrong scope classification and a substituted intermediate state reject.

A certificate received for the first time after its signed deadline is archived
with a conservative live eligible claim until authenticated terminal evidence
exists. Import does not grant a new vote, alter the signed deadline or invent a
local effect reservation. Exact retries preserve an existing vote; new conflicting
subjects remain blocked.

The four fixture validator keys are fixed public scalars 1–4, with a separate
transaction key. Each honest runtime owns one key and its own journal. The
Byzantine role deliberately signs both subjects outside durable admission.
This is a deterministic voting safety scenario, not a live transport or
four-process HotStuff finality run.

The retained admission checkpoint is a trusted installed neutral fixture with
a real QC. This fixture does not establish its actual historical three-chain
finality. Application state-payload recovery, expiry/nonapplication evidence,
exact lifecycle history and transition recovery use their separately
authenticated gates. These voting-only restarts select
`SafetyRecoveryAuthentication.voting(recoveryRequests)`, which authenticates retained voting sources and refuses those other histories.

## Original voting evidence and recovery

Before any new intent record, the store forces a canonical immutable
`voting-evidence` blob keyed by the intent digest. Its format-2 envelope selects
lock, effect or consensus request material. Lock material retains the subject,
descriptor, signed transaction and all resolution proofs; effect material retains
the subject, owner and complete witness; consensus material retains the original
canonical proposal bytes and full execution plan. Same-intent retries preserve
the original proof bytes and revalidate their availability.

`VotingRecoveryAuthentication.authenticated(requests)` decodes that material
strictly and calls the actual request verifier again. Repositories must retain
historical signed sources, authenticated state and execution inputs; they must
not call the recovering store whose gate is held. Recovery compares the original
intent key, subject/signing bytes, proposal/body/state/plan roots, ordered owners,
all reservation references and complete witnesses with independent execution.
Imported certificates also repeat source verification and compare any existing
local complete claims. Certificate-only import archives the certificate and
eligible lock without creating an effect vote or a new reservation. Terminal status never permits witness substitution.

The public voting fixture supplies an explicit set of retained independently
signed sources across reopen. Negative cases remove that source set, remove
original request evidence, append noncanonical trailing bytes, and replace a
reservation with a smaller yet canonically valid complete witness that omits a
real read. Each must fail authenticated recovery. Application deployments compose
this verifier with their separate application/finality recovery authenticator.

## Full reservation limits

[V2ReservationScaleConformance](../../release-conformance/shared/v2-jvm/scala/org/sigilaris/conformance/V2ReservationScaleConformance.scala)
adds 99,996 real state scan identities to the four-identity neutral execution.
The signed request therefore covers 100,000 actual identities and 256 canonical
witness chunks below the 16 MiB byte limit. Its test follows public verification,
durable owner/index preparation and signing, then reopens the journal and
compares the complete owner and index. The executed source fixture produced
16,749,354 bytes and passed in 51.427 seconds (2026-09-11 14:34:48 KST); its
stdout records the measured identity, byte and chunk counts.

The same fixture checks owner capacity zero and local byte capacity one byte
below the complete witness, requiring `CapacityUnavailable` without a vote
intent or signer call. One extra actual scan identity must fail the protocol
limit before producing a verified request or any durable vote. Separately,
canonical witness tests pad existing unique identifiers to exactly 16,777,216
bytes, require acceptance at that limit, and reject one additional byte and a
257-chunk shape. Those codec boundary checks complement the complete 100,000
identity runtime path; they are not substitutes for it.

## Gate record

The integrated `nodeJvm/test` gate passed all 776 tests on 2026-09-11 at
15:04:51 KST. `scalafmtCheckAll` and `scalafmtSbtCheck` passed at 15:04:54 KST.
After its final read-then-write assertion edit,
`nodeJvm/testOnly *V2PublicOrderedVotingSuite` passed its one test at
15:06:00 KST. The P2-only request, private capability, durable signer,
original voting evidence and complete reservation review concluded **No finding**.
P3 staged work and later public artifact/network gates are excluded from this
result. The phase gate retains final commands and the separately scoped
application, exact, transport and publication evidence boundaries.
