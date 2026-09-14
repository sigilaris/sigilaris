# Plan 0033 post-implementation review

Status: complete on 2026-09-12. Source, standalone candidate and final public export gates passed; final self and independent reviews report **No finding**. This review starts from `adf074c` and covers the complete implementation range `efe4f45..adf074c`, followed by review of the corrections. The [P6 gate](p6-gate.md) remains the dated evidence for the previous revision; its artifact and source inventories are not identities for this corrected revision.

## Findings and corrections

| Finding | Required correction and evidence |
| --- | --- |
| A structurally valid low-S signature can encode an invalid secp256k1 point. Cryptographic recovery can throw instead of returning its declared failure, escaping transition and controller authentication. A recovered infinity point can also escape as a lazy public key and throw later. | Both implementations evaluate recovery and recovered coordinates inside the typed exception boundary. Preserve their historical interpretation; strict V2 signature shape validation remains at its versioned boundary. Shared public cases cover malformed points, infinity and valid low/high-S signatures; the actual file controller rejects a malformed authority signature with `InvalidSignature`, preserves its snapshot and accepts the subsequent valid request. |
| Some exact signing-lease and last-moment deadline checks exist only in private source tests. | Add public consumer scenarios using actual public authorization and voting APIs, including failure/cancellation cleanup and finality reaching the signed deadline before key use. Keep private capabilities closed. |
| Staging conformance checks artifact names but can accept a missing staged coordinate from the Central fallback resolver. | Require every selected Sigilaris JAR to originate in the explicitly chosen staging repository; test an incomplete staging repository against an already published version. |
| The first correction added global recovery-parameter restrictions that would change historical JVM verification. Published M1/M2 accept recovery-byte aliases such as `v=283` for `v=27`. | Remove the new global restrictions, preserve the original JVM byte normalization and Scala.js interpretation, and retain strict validation at the V2 signature boundary. Add an explicit public historical-compatibility regression before repeating the final gates. |

A suspected blob-recovery durability omission was withdrawn after checking the complete `validateBlobs` implementation: it already forces every namespace directory and the blobs parent. No storage change is required for that suspicion.

## Verification

Before corrections, the independently selected application-voting, automatic-proposal, finalized-application and active-signing-lease suites passed **32** cases. These are a review baseline, not final corrected-revision evidence.

The first correction's focused gates passed **29 JVM**, **29 Scala.js** and **25 file-controller** cases. Its first compile exposed a strict `Nothing` inference warning in the new failure branch, which was corrected before that run. The independent oracle still verifies all **55** unchanged transition vectors.

That intermediate revision also passed **552 core JVM**, **551 core Scala.js**, **134 node-common JVM** and **133 node-common Scala.js** cases, plus the seven new source authorization cases. Further review then reproduced the recovery-byte alias on the actual pinned M1 and M2 JVM artifacts. The in-flight full node and exported candidate runs were intentionally cancelled; their earlier staging identities are superseded and are not final evidence. Final gates use fresh artifacts after the compatibility correction.

The final compatible revision has passed `scalafmtCheckAll` and the complete core/common source matrix again: **552 core JVM**, **551 core Scala.js**, **134 node-common JVM** and **133 node-common Scala.js**. The full JVM-node recheck passed **967** cases with zero failures and errors at **10:19:17 KST** in **1685 seconds**, with **exit 0**. The actual historical transport scenario completed all three quorums and finality/materialization checks in **1666.792 seconds**. Across all five final source targets, **2,337 tests passed**. The standalone exported consumer has completed with **exit 0** across all **28 JVM groups**, Scala.js fast execution and direct fully optimized Node.js execution. Existing public publication and deployment gates remain separately scoped.

The first full compatible JVM-node run completed **967** cases with **966 passes** and one MUnit timeout at **1800.951 seconds**, ending at **09:49:33 KST**. The historical transport scenario had formed actual quorums at heights 6 and 7 before reaching the private wrapper's 1800-second limit; it did not report a failed protocol assertion. The previous P6 observed success of 1716.210 seconds left less than 5% headroom. Independent review confirmed the fixture has fixed finite replay/relay rounds, with no sleep, retry or unbounded polling loop. Only the private wrapper limit is raised to **3600 seconds**; all protocol assertions, public fixture bytes and staged artifacts remain unchanged. The full **967-case** node target was repeated successfully, and the standalone consumer retained its original artifacts through successful completion. The timed-out run is retained as a resolved failure and is not counted as a passing target.

The final compatible correction passed **29 JVM**, **29 Scala.js** and **26 controller** focused cases. Independent re-review of the minimal crypto change, public authorization observations and staging origin checks reports **No finding**. The authorization scenarios record original-authentication success, the actual authorization result and entry into deliberately failed key use outside the expected-failure callback, so an earlier unexpected failure cannot satisfy the intended negative case.

The maintained [historical recovery probe](../../release-conformance/tools/LegacyRecoveryAlias.scala) passed against the actual published M1 and M2 JVM core/common/node artifacts, with all three JAR hashes checked against each milestone's immutable inventory. Both raw key recovery and `Vote` codec roundtrip, recomputed vote identity and `HotStuffValidator.validateVote` accept the historical `27 → 283` recovery-byte alias. The corrected candidate's public controller family repeats that actual HotStuff path and separately rejects the same noncanonical representation through `ValidatorSignature.validate` for V2 application artifacts.

With Scala CLI installed, the exported probe can also be run directly against those public dependencies:

```sh
scala-cli run --server=false --scala 3.7.3 --dependency org.sigilaris:sigilaris-node-jvm_3:0.3.0-M1 release-conformance/tools/LegacyRecoveryAlias.scala
scala-cli run --server=false --scala 3.7.3 --dependency org.sigilaris:sigilaris-node-jvm_3:0.3.0-M2 release-conformance/tools/LegacyRecoveryAlias.scala
```

Staging provenance checks also passed both controls: an empty file repository with public M2 fallback fails specifically at the selected-origin check, while explicitly selecting the Central HTTPS repository as staging passes all five pinned M2 artifact identities. The final file-staging candidate separately passed the full consumer command below.

## Final candidate identity

The compiled candidate source/configuration/fixture bytes are preserved in reachable commit `cb0f120`. Reproduce the archived candidate from that commit and the inventory below; the original temporary export tree was not a cloneable commit. Its [source/configuration/fixture inventory](review-source-and-fixtures.sha256) contains **378** files and has SHA-256 `082d9e5e3575fdd509866c47a7b47dd2fd12383c26455c325cbf64194b02b9e1`. Documentation and the private test-timeout correction were added after the tested export and included in `cb0f120`; the compiled public source and fixture bytes remain frozen. The corrected private wrapper is removed by export and is covered by the full node recheck.

The standalone compiler selected exactly the five JARs in the [final candidate artifact inventory](review-candidate-artifacts.sha256) from `/tmp/sigilaris-0033-review-final-maven`, and all actual resolved hashes match. Its **49 JVM** and **11 Scala.js** source files compiled using those artifacts without the parent source build. The export has no private `src/test` tree and passes all 378 source checksums, **283** local file references at candidate creation and the **55** unchanged independent transition literals. The two Sep11 P6 inventories and P6 gate remain unchanged archival evidence.

## Completed standalone candidate execution

The candidate command completed with **exit 0** on **2026-09-12**. JVM completed at **10:03:38 KST**, Scala.js fast execution at **10:03:43**, and full optimization at **10:03:50** followed by the direct optimized Node.js completion at **10:03:51**. Closure reported zero errors and warnings. All three executions passed the shared core/certificate/opening cases, historical protocol vectors, eight transition schema families and all 55 independent transition literals, including the new malformed-recovery checks.

All **28** JVM runtime/storage groups passed. The actual ordinary Gossip scenario formed quorums at heights 6, 7 and 8, finalized B6 and applied the execution on all four nodes with no raw-key fallback. Durations below are observations from this gate, not production performance guarantees.

| Public scenario group | Passed in seconds |
| --- | --- |
| requests | 0.187 |
| durable voting | 1.643 |
| current signing authorization boundaries | 0.776 |
| recoverable canonical application | 4.675 |
| canonical ancestry | 1.123 |
| exact execution modes | 7.008 |
| exact mixed success | 1.055 |
| exact mixed failure | 0.899 |
| exact boundary binding | 1.109 |
| exact finalized application | 7.484 |
| ordered voting | 0.040 |
| finalized application runtime | 13.072 |
| four actual voting runtimes | 67.191 |
| complete stopped consistency groups | 17.988 |
| key-owning fence controller and fault recovery | 2.891 |
| file journal force and corruption boundaries | 3.143 |
| initial bootstrap and retired originals | 15.542 |
| four initial bootstrap runtimes across all source forms | 73.454 |
| historical signed source and safety recovery | 34.730 |
| four original stopped handover groups | 28.477 |
| four direct handover runtimes | 280.060 |
| four ordinary handover gossip runtimes | 1784.870 |
| historical canonical publication fault | 101.058 |
| original deployment/key inventory negatives | 0.016 |
| pinned historical profile boundaries | 0.004 |
| original enabled issuance and late old quorum | 31.342 |
| three authenticated original drain routes | 24.467 |
| 100000-id durable reservation | 75.692 |

## Reproduction

```sh
sbt 'coreJVM/test' 'coreJS/test' 'nodeCommonJVM/test' 'nodeCommonJS/test'
sbt 'set ThisBuild / publishTo := Some(Resolver.file("candidate", file("/tmp/sigilaris-0033-review-final-maven"))(Resolver.mavenStylePatterns))' \
  'coreJVM/publish' 'coreJS/publish' 'nodeCommonJVM/publish' 'nodeCommonJS/publish' 'nodeJvm/publish'
```

The node target uses the same two independent fork groups documented in the [P6 gate](p6-gate.md#full-source-failure-and-correction), with every discovered suite retained. The public export's standalone consumer runs against the explicit repository above:

```sh
cd release-conformance
python3 tools/verify-transition-vectors.py
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/tmp/sigilaris-0033-review-final-maven
```

## Final review and export check

Final self and independent reviews of the implementation, corrections, historical compatibility, public signing assertions, staging origin validation and execution records report **No finding**. All required follow-up review work is complete.

The final public export passed **285** local file-reference checks with no missing target, all **378** frozen source/configuration/fixture checksums, all **five** actual selected artifact checksums and the **55** unchanged independent transition literals. No private `src/test` directory survived export. At this gate, the original P6 gate and both P6 inventories remained byte-identical in `cb0f120`. Later reproduction-only editorial corrections are recorded in the [full-review correction gate](full-review-corrections-2026-09-12.md); the archived source/artifact inventories retain their original bytes. The completed node recheck covers the private timeout correction added after the tested candidate export.

## Lessons applied to subsequent gates

- Signature shape validation does not establish that a recovery point exists. Include malformed, shape-valid signatures at the actual public authentication boundary on both platforms.
- A documented valid-input range does not establish historical rejection behavior. Compare changed shared primitives against actual pinned historical artifacts before tightening their accepted inputs; preserve old interpretation and keep new restrictions at the versioned boundary.
- Public conformance must exercise the advertised last key-use boundary. Private helper assertions alone cannot establish what an artifact consumer can reproduce.
- Artifact names and versions do not prove repository origin. Verify the selected JAR origin before assigning public or staging evidence labels.
- Inspect complete recovery helpers before concluding that a durability barrier is absent; callers may delegate those barriers.
- Integration test limits need headroom above measured whole-scenario execution, including concurrent gate load. Keep the scenario finite and its assertions unchanged; record a timeout honestly and rerun the full target after changing only the test wrapper limit.
- Retain previous dated gate evidence and record fresh source/artifact identities for every corrected candidate. The future public release must rerun the same consumer against its actual published artifacts.
