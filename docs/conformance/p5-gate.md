# P5 activation implementation gate

Status: passed on 2026-09-11. Implementation, affected source/physical runtime gates and corrected self/independent reviews are complete. P6 final source, selected Maven artifacts and public export remain separate evidence.

## Implemented integration boundaries

- [Historical activation](v2-historical-activation.md) selects independently pinned M1/M2 provenance, preserves complete original consensus safety and separates canonical drain checkpoint F from certified working parent P. Original-profile canonical F→P publication precedes target application preparation, including restart.
- [Initial bootstrap](v2-initial-bootstrap.md) binds complete inherited state and original source/retired evidence before initial signing. Four actual validators form the dedicated initial certificate and execute the signed opening as the first ordinary descendant. G is an internal authenticated ancestry stop, never invented finality.
- [Fence controller](v2-fence-controller.md) owns the actual key and forces intent/enforcement before use. Fresh signing requires exact held permissions; original recovery authenticates original bytes without pretending an incomplete dependency graph is ready.
- [Complete consistency groups](v2-consistency-group.md) preserve every installed namespace and post-backup safety promise. [Activation integration](v2-activation-integration.md) exposes active state only through the authenticated committed decision and the same actual journal/controller. Restore verification grants eligibility, not an automatic rollback operation.
- Partly absent retired archives retain separately tagged present-content and pre-baseline absence evidence. Independently authenticated scope coverage must be disjoint; later content loss cannot change the fixed classification.

## Recorded execution

All runs below used the development source version `0.3.0-M3-SNAPSHOT` on 2026-09-11. They are not public Maven publication evidence.

| Gate | Actual result | Scope and limit |
| --- | --- | --- |
| Four common module targets | core JVM 549, core JS 548, node-common JVM 134, node-common JS 133; all passed | Source run completed 20:21. Later JVM-only changes still require the final combined gate. |
| Selected JVM regressions | 128 passed, zero failures; completed 20:25 | File journal 29, controller 23, group 10, lease/deadline, source profiles, historical proofs/group, bootstrap, finalized application and actual P4 four-runtime cases. Excludes the then-in-progress P5 transport and live-drain additions. |
| Three original drain routes | JournalCovered, InferredHorizon and NeverEnabled passed; actual finality equal to deadline rejects drain | Original birth policy, all four issuance archives and original controller intents are retained; the enabled live-claim scenario remains a separate gate. |
| Latest combined activation regressions | 55 passed, zero failures; completed 20:46 | Controller 24, group 10, basic bootstrap 6, four ordinary bootstrap variants, exact signing leases 4, deadline races 3, original issuance 3 and three-route drain suite 1. Mandatory-opening abandonment and the new lock-base handover addition were still being completed. |
| Direct four-controller handover and original finalized-base lock | Passed in 304.212 seconds: F3→P5→B6, actual four target Lock votes, three-voter import, four full reopen and B7 continuation, same D10 retry, active original-source loss rejection | F3 is independently verified as finalized; P5 is working only. Changed roots, P5 admission substitution and old-domain relabelling reject. Ordinary node/Gossip transport is a separate required gate. |
| Ordinary four-node Gossip handover | Passed in 1676.745 seconds: actual four-node QCs at B6, B7 and B8, three-chain finality of B6 and canonical V2 application on every node | Each node replays original 0..5; new proposals/votes cross authenticated Gossip with real codecs. All runtime raw-key maps are empty. This completed scenario preceded the fixture-only counter-signature memoization; its changed-key/command/signature regressions passed afterward, and P6 reruns the final fixture. |
| Original enabled issuance, live drain and late old quorum | Three public scenarios passed | Actual before-key persistent M1 claim, equality/foreign-domain expiry rejection, strict original F3>D2 expiry, four-node group/activation/reopen, delayed 2-honest+1-Byzantine original certificate, interrupted key use, and missing birth-policy refusal. |
| Historical canonical physical faults | Passed, 110.598 seconds | Actual Prepared force and recovery Committed HEAD-force cuts, repeated four-node reopen, partial-frame/HEAD rollback/original evidence loss rejection. |
| Five ordinary bootstrap runtime cases | All passed: no prior domain, retained retired archive, wholly absent retired archive, partly absent retired archive and abandoned mandatory opening | Actual four-node opening/finality and same journal/controller restart; wrong keys and lease bypass reject. Abandonment retains full signed D12 owners/index across restart, rejects an empty first block and source-authority replay, then resumes real opening finality. |
| Final affected regression and formatting | 37 distinct cases passed across the final targeted run and corrected historical rerun; `scalafmtCheckAll` passed at 21:22 | Controller 24, exact signing leases 4, deadline races 3 and historical 6. The new invalid-point signature negative initially raised an existing crypto exception; the neutral verifier now converts it to rejection, and all six historical cases passed again. |
| Public storage consumer extraction precheck | 63 cases passed: group 10, controller 24, file journal 29 | Executed outside the repository against source class files. P6 must rerun against selected Maven artifacts; the repeated run includes the immutable-history cache changed-key/bytes/order regression. |

## Final review and lessons

Self review and independent reviews found and corrected: initial G leaking into finalized metadata; generic readiness being mistaken for an exact signing permission; incomplete transition prefixes being checked as complete startup; initial-signing intent reuse bypassing current source verification; application key membership checked only after signing; partial retired-domain preservation incorrectly rejecting two distinct evidence kinds; physical journal identity and observed-prefix loss; and complete-group restore digest-domain confusion.

The broad `nodeJvm/test` run begun at 21:08 was stopped when the fixture changed; it is not recorded as a completed gate. P6 reruns all five module targets against the final exported case bodies.

Reviewed pure caches retain only successful complete immutable cryptographic/history inputs. Source, authority, physical identity, canonical ancestry and fresh authorization are independently rechecked. Changed-key, source-loss, record-order and record-byte regressions remain part of the final gate.

Final corrected self review and independent re-review: **No finding**. The ordinary transport scenario finished successfully; its Scala CLI runner also discovered a duplicate copy through the source test classpath, so that redundant execution is stopped rather than claimed as additional evidence. P6 uses the standalone main runner and records the final source and artifact gates separately.

Lessons carried into P6: preserve exact original-evidence and live-key-use boundaries in exported consumers; reuse the actual source assertion bodies; independently recompute fixed vectors without regenerating expected values; inspect the exported tree after private tests are removed; record every selected compiler artifact checksum. Replay retained originals once per recovering node, transport new artifacts normally, and keep source availability/state replay outside bounded pure signature reuse. Do not suppress repeated finalization observations merely because their tracker snapshot is equal: pending proof backfill may need that retry.
