# P3 exact execution evidence

This evidence uses the public v2/core and node JVM APIs from the source build. The maintained consumer sources live in `release-conformance/shared/v2-jvm/scala/org/sigilaris/conformance/`. A staged Maven execution of the same sources remains the P6 gate; source tests are not published-artifact evidence.

## Maintained public fixtures

- `V2ExactFixture` signs the outer plan and both source transactions with real secp256k1 keys. Signed source bytes bind the domain, admission base, descriptor, declaration and common deadline. The reference derives from the already-signed producer source and output slot, avoiding a circular dependency on the outer plan digest. The two neutral reducers instrument existing reads, mutations and absent-target creation; consumer output consumption is explicitly encoded in its independently replayed result.
- `V2ExactConformance` covers OrderedAtomic and CertifiedAncestor with eligible-lock masks 00, 01, 10 and 11. It uses actual journal admission, private request capabilities, complete reservations, real vote signing, idempotent restart, and the requirement to revalidate exact execution before signing again after recovery. Consensus sources have no effect quorum. Additional cases cover signed-plan/field/reference/deadline rejection, immutable aliases/stage/output ownership, current branch changes, missing lock/history evidence, backfill and deterministic producer/consumer rejection with retained access reservations.
- `V2ExactBoundaryConformance` exercises artifact identity/canonical-byte validation even when a lookup ignores the supplied hint, direct generic voting boundaries, same-subject certificate variants, and the binding between an authentication capability and the actual signed request. The deliberately faulty ownership adapter in this negative fixture preserves real source/input/execution checks while dropping or substituting only the exact binding; the authoritative journal must reject it.
- `V2ExactMixedConformance` replays four entries from two independently signed and admitted pipelines over one shared working state. A common candidate remains unsigned until both exact profiles succeed; a second pipeline's rejection cannot reuse the first pipeline's readiness, and recovery requires both permissions to be earned again.
- `V2ExactApplicationConformance` supplies the separate file-journal, actual application payload, three-chain finality, nonapplication, and canonical application/recovery evidence. Its tests apply finalized execution without requiring the receiving node to have previously voted.

The retained initial checkpoint is explicitly installed fixture trust. Neutral state proofs are deterministic authentication artifacts checked against the retained state map; they are not an implementation of an application-specific production proof format. The fixture's historical QCs contain actual signatures and are always reverified. Four independent durable-voter history collection is separately covered by `V2CanonicalAncestorSuite`; the eight-mask fixture does not claim four independent stores of its own.

## Recorded execution

The final P3 targeted gate passed **81 tests, zero failures**, on 2026-09-11 at **15:50:50 KST** (`/tmp/sigilaris-0033-p3-gate.log`). This includes public exact candidate 12, boundary 4, mixed-pipeline 2, application 6, pure projection 12, ownership 3, genuine ancestry 10, and the P2 application 21 / voting 10 / request 1 regressions. `scalafmtCheckAll` and `scalafmtSbtCheck` also passed in that gate.

The public Ordered read→write voting regression passed separately, **1 test, zero failures**, at **15:52:01 KST** (`/tmp/sigilaris-0033-p3-ordered-gate.log`). Shared exact model/codec tests passed **12 on JVM** at **15:26:36 KST** and **12 on Scala.js** at **15:27:23 KST**. These source checks do not replace the later P6 staged-artifact run.

The two mixed-pipeline tests also passed their first independent run at **15:49:18 KST** (`/tmp/sigilaris-0033-p3-final-regressions-2.log`); the final 81-test gate reran them alongside the completed application profile-rejection cases.

## Review corrections

Review added signed admission lifetime validation even for mask 00, rejected a fast source with no eligible lock subset, and rejected OrderedAtomic's two-stage use of singleton compatibility declarations. Canonical certificate/archive operations preserve the source's immutable common deadline. Supplied artifact hints now match the canonical bytes and independently recomputed identity of a used authenticated artifact.

Verified exact plans retain their configured profile authentication. Candidate verification and nonvoting canonical application use that same profile for producer output and consumer acceptance. Reusing a capability returned for different signed work is rejected at admission, candidate verification and recovery. A deterministic failed execution may keep complete conservative reservations, but it cannot acquire exact signing readiness or be promoted to canonical application through genuine finality alone.

The independent admission mirror used by the source adapter is populated from actual durable admission and is read without reacquiring the shared safety gate. Recovery separately validates the authoritative journal registration and ownership from the original immutable safety projection. This avoids a recursive gate acquisition while preserving proof of actual admission.

## Final self-review

**No finding.** After applying the review corrections, the authenticated main APIs, captured profile, source/capability identity, full actual accesses, canonical prepared-plan/result references, prior canonical producer linkage, independent pipeline signing readiness, failure retention and restart paths were reviewed again. The final targeted tests and format checks above ran after those changes. The staged public Maven consumer execution remains explicitly assigned to P6.
