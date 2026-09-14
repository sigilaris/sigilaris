# P4 finalized application integration

`FinalizedApplicationRuntime` bridges an actual HotStuff
`FinalizedAnchorSuggestion` to the existing authenticated application batch and
shared safety journal. It is a closed implementation created by
`FinalizedApplicationRuntime.authenticated`; a status object is not an authority
or a substitute for a verified batch.

## Required material and recovery

The configured `FinalizedApplicationHistory` resolves actual retained or
backfilled finality, full `ExecutionPlan`, and complete state payload bytes.
`latestFinalized` must report the actual consensus tracker/history after restart;
if a known consensus target cannot be reconstructed, it must return unavailable.
The consensus target and the application's materialized frontier are separate.

Each future block is verified by the configured `ApplicationRequestVerifier` and
`ApplicationCommitVerifier`. Catch-up first verifies the complete bounded path
from the target to the actual journal frontier, including parent, height, and
state-root continuity, then prepares and commits blocks in ascending height
order. Every prepare/decision uses `RecoverableApplicationStore` and the same
`JournalSafetyStore`, including its exact outcome and recovery authentication.
Canonical empty blocks preserve the actual parent state root. A nonvoting node
uses this path without manufacturing a local consensus vote.

An already materialized target is accepted only if it matches the original
anchor or a retained canonical application decision. An older target cannot
select another branch. An initial-anchor ancestor is handled before V2 source
interpretation and requires a private `VerifiedInitialAnchorAncestor` capability.
The P4 concrete `InitialAnchorAncestry.legacyEmpty` factory verifies actual
historical finality/QCs, proposal and justification validator sets/signatures,
canonical V1 empty-body and empty-transaction commitments, the complete bounded
parent/height/state-preserving path, and the installed anchor's exact identity.
Nonempty historical profiles and domain ranges require the P5 authenticated
range implementation; a height comparison cannot grant this capability.

## Actual sink and voting boundary

`InMemoryHotStuffArtifactSink.createWithValidationAndFinalizationObserver` runs
its observer after the actual consensus state update and outside the update's
pure closure. Application unavailability or write ambiguity is retained as an
application failure; it does not undo already accepted consensus finality.
Duplicate accepted events retry material availability and forward recovery.
Startup calls explicit recovery before ordinary automatic voting attaches.
Normal observations only recover a safety store that reports
`RecoveryRequired`, preserving already prepared voting and exact permissions.

The bridge retains every authenticated pending target by height and reconciles
the latest actual history under the same gate. Incoming and latest targets are
authenticated before selecting the highest target's full catch-up path. Before
the first preparation, that path must contain every earlier pending target.
Missing higher material also holds a complete lower target; a conflicting branch
cannot partially commit before the pending contradiction is discovered. A forced prepared
batch also remains pending after process restart until its canonical decision is
recovered; losing latest history cannot turn that durable preparation into
readiness. A
successful delayed lower observation cannot erase a higher pending target.
The ordinary voting adapter enters the bridge's internal signing boundary,
which requires the identical safety-store instance, matching actual journal and
reported canonical frontier, no retained failure, and completion of the known
highest target. The gate re-reads actual latest history before a new vote and checks even an
older signed target against original canonical decisions. Verification, durable
claim and signing remain inside that gate, so an observer cannot invalidate
readiness between the check and signature. Actual conflicting finality retains
its original proofs or tracker faults in a fatal latch; ordinary retry/recovery
cannot clear it. Process restart must reconstruct those faults from retained
actual consensus artifacts, rather than select a preferred winner.

## Maintained executable evidence

The exported `V2FinalizedApplicationConformance` fixture and
`V2PublicFinalizedApplicationSuite` exercise:

- Real sink acceptance with unavailable application material, then a duplicate
  event that backfills and applies the same canonical target.
- Ascending materialization, empty-block root preservation, same/older replay,
  and rejection of an actually signed alternate branch.
- Missing or contradictory complete state material and explicit local capacity
  failure before a new application preparation is written.
- Concurrent canonical targets and preservation of an already prepared vote
  after a duplicate finalized observer.
- A pending higher target surviving a delayed lower view; the public ordinary
  voting adapter rejects until backfill completes and cannot borrow readiness
  from another journal even with identical context.
- Different-height pending targets, including a higher fork, a complete lower
  fork while higher material is unavailable, and successful same-branch advance.
- Actual conflicting finality through both direct proofs and the real tracker,
  plus a fresh older fork discovered at the ordinary signing boundary.
- Ambiguous forced writes followed by observer retry and real file reopen at
  both forced prepare/decision frame boundaries.

These are neutral source-level integration fixtures using real secp256k1
signatures and current V2 production APIs. They do not claim four independent voter
journals; that distributed evidence is the separate P4 runtime fixture. Isolated
M3 artifact consumers and their checksums remain the P6 gate.

Final source gate: on 2026-09-11 at 16:42:19 KST, the root-owned sbt run
passed all 25 targeted tests: finalized application 11, actual four-runtime 7,
and ordinary voting integration 7. The finalized suite includes all maintained
cases above, including the distinct-height pending-fork correction. The log is
`/tmp/sigilaris-0033-p4-runtime-final-gate.log`. Formatting and sbt formatting
checks passed at 16:42:22 KST. The separately completed affected legacy/runtime
regression gate passed 116 tests at 16:40:12 KST
(`/tmp/sigilaris-0033-p4-final-legacy-gate.log`).

Self-review and the independent root/core/safety reviews corrected recovery
resetting prepared permissions, mismatched stores, pending target rollback,
fresh target/signing races, durable prepared targets after reopen, persistent
finality faults, older canonical forks, and distinct-height pending forks.
The final complete-path-before-commit and signing boundaries have **No finding**
after those corrections and the final executed regressions. These results are
source/build integration evidence; P6 still owns isolated published-coordinate
execution and artifact identity checks.
