# Plan 0033 independent review, second pass (2026-09-12)

Status: review only, no tracked file changed. Revision reviewed: `7fe1cff`, range `cb0f120..7fe1cff` (41 files, 8 main
sources). This pass checks the corrections recorded in [full-review-corrections-2026-09-12.md](full-review-corrections-2026-09-12.md)
against the findings of the [first pass](full-review-2026-09-12.md).

## Result

No safety or correctness defect in the corrected code. The M1, M2, M5, L6, L8 and L9 corrections do what the record
claims, and the documentation corrections D1 to D5 are accurate. One evidence-identity gap (medium-low) and three low or
cosmetic items remain, listed below. M3, M4, L1, L3, L5 and L10 remain open in plan 0034 as stated.

## Verified corrections

- **M1.** `InitialBootstrapParent.validate` now compares only the quorum subject and re-authenticates the supplied
  certificate through `InitialBootstrapConsensus.authenticate`, which checks bundle digest and G identity, canonical
  encoding, subject binding, every vote signature against the installed roster, threshold and duplicate votes. The
  controller path delegates to the same function. No remaining byte-equality gate on the initial QC exists in
  `modules/` or the public fixtures. Public runtime scenario installs two distinct three-vote subsets and one four-vote
  certificate across four nodes and asserts insufficient, duplicate and invalid-signature rejection.
- **M2.** `lifetimeCoverage` folds start-sorted intervals and requires the union to reach `lifetimeEnd` from
  `lifetimeStart` with no gap and non-empty scope; applied to both deployment and signer scopes. Negative cases cover
  empty, leading, trailing and internal gaps; overlap between distinct identities remains accepted.
- **M5.** The observation memo is read and cleared under the finalizer gate, reused only for an identical
  `FinalizedAnchorSuggestion` (proposal plus proof) with no recorded failure and no pending target. Explicit `finalized`
  and `recover` pass no observation and therefore never reuse. `guarded` clears the memo on any `Left`, error or
  cancellation before the gate is released. Every safety-store mutator reachable from consensus runs inside that gate,
  including exact execution via `exactReady` inside the signing block and controlled signing via the finalizer, so a
  store left in `RecoveryRequired` by a failed vote cannot hide behind a reused memo.
- **L6, L8, L9.** The `InvalidLength` branch is now reachable and asserted with exact code and field; ancestry
  negatives assert exact codes and diagnostics with the two deadline cases separated; maintenance identities are
  bounded with stall-not-evict semantics and documented.
- **Docs and tooling.** P1 manifests verify at `b9fa3f0` (12/12 and 19/19 from `git show`), ADR and plan index status are
  consistent, every documented symbol resolves in code, dangling tree hashes appear only in the first-pass report,
  `LegacyRecoveryAlias` compiles into the standalone JVM build and runs in every profile before the main run, 249
  links resolve, and the test-count arithmetic and timeline are coherent.

## Verified in this pass

- `scalafmtCheckAll` passed; core protocol suites **76** and the focused node suites for finalizer, proposal
  assembly, transition evidence, ancestry, bootstrap runtime, signing lease, deadline and authorization **62** passed.
- `full-review-correction-sources.sha256`: 378 OK, 0 failed; self-hash matches the record.

## Remaining findings

| # | Severity | Finding | Location |
| --- | --- | --- | --- |
| R1 | Medium-low | The correction manifest inherits the 378-path set and does not include `release-conformance/js/src/main/scala/org/sigilaris/conformance/PlatformConformance.scala`, the file that carries the new Scala.js recovery-parameter probe, nor the JVM `PlatformConformance.scala` or `export-jvm/V2VectorExporter.scala`. The record's statements that compiled public inputs are frozen and that 19 non-V2 consumer inputs are byte-identical cannot be checked from any current inventory for those files. Pre-existing gap, now material because one of them changed. | `docs/conformance/full-review-correction-sources.sha256`; `full-review-corrections-2026-09-12.md` candidate identity section |
| R2 | Low | The Scala.js probe treats any exception as rejection: `Try(recover(...)).fold(_ => true, _.isLeft)`. Acceptance is excluded, but in the V2 profile the intended outcome is a typed `Left`; a regression back to throwing would pass. | `release-conformance/js/src/main/scala/org/sigilaris/conformance/PlatformConformance.scala:17-20` |
| R3 | Cosmetic | `V2PlatformConformance` (JS) now calls `PlatformConformance.run()` while `V2ConformanceMain` already does, so the V2 JS profile runs the probe twice. | `release-conformance/shared/v2-js/.../V2PlatformConformance.scala:5` |
| R4 | Cosmetic | `v2-contract.md` lists maintenance decisions without `Idle`; `docs/plans/README.md` uses `Done` where the plan says Complete. P1 `legacy-m*-output.txt` fixtures predate the new probe output line; nothing compares them. | `docs/conformance/v2-contract.md:178-179`; `docs/plans/README.md:63` |

## Notes for the open backlog

- Maintenance capacity (L9) stalls every new request identity for the rest of the process once 4096 identities have
  been seen, including identities whose target was reached. This is documented and safe, but eviction of reached
  targets after a bounded retention would remove a permanent liveness cap on long-running nodes.
- The signer-scope lifetime requirement in M2 means a domain whose authorized-signer roster had a keyless interval
  cannot use the never-enabled route. The activation guide states this rule; confirm it matches intended deployments.
