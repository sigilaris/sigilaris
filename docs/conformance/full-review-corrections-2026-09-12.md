# Full review corrections (2026-09-12)

Status: selected corrections complete on 2026-09-12, starting from `cb0f120`; final source and standalone candidate gates passed. The supplied [independent review](full-review-2026-09-12.md) is retained unchanged as the original report. Earlier phase and post-plan results describe their recorded revisions, not this corrected source tree. The storage and lifecycle items explicitly listed below remain open.

## Disposition

| Finding | Action and remaining boundary |
| --- | --- |
| M1 | Accept any canonical, fully authenticated initial QC over the exact installed bundle/G subject. Use the same rule in initial parent validation and controller proposal authentication. Four actual nodes install different 3-vote subsets and a 4-vote certificate, run the opening and descendants, and reopen their individual journals. The public state/parent adapter follows the same rule. |
| M2 | Require both authenticated deployment and signer interval unions to cover the complete attested lifetime. Retain the existing per-identity no-gap/no-overlap checks and signed inventory verification. Negative cases cover empty/start/end/internal gaps for both scopes; overlapping distinct deployments and keys remain valid. |
| M3 | Remains open. Corrupt evidence is retained and fenced; automatic tail discard is not implemented. A local HEAD does not prove absence of previously signed evidence after rollback or corruption. Offline repair, interrupted creation and quarantine require [plan 0034](../plans/0034-v2-recovery-and-lifetime-cost-hardening-plan.md). |
| M4 | Remains open. Full original-evidence reauthentication and physical-history checks remain required before signing. Checkpoint/compaction and operation-count scaling need plan 0034; skipping observer duplicates is not a solution to lifetime replay cost. |
| M5 | Retain one successful complete finality observation under the existing finalizer gate. Identical notifications with no failure or pending target reuse it; changed proofs, pending work, explicit recovery, cancellation and failures require a full drive. Failed/cancelled voting and signing invalidate the memo before releasing the gate, including unknown durable writes and key-use exceptions. Signing still checks current finality and the actual journal. No background worker or asynchronous readiness is introduced. |
| L1 / L3 / L5 / L10 | Durable leader proposal intent, physical validator binding, interrupted-layout repair and structural callback non-reentrancy remain open in plan 0034. Existing claim/fence rules and fail-closed behavior are retained. |
| L2 | Preserve historical JVM/JS interpretation; no transaction or HotStuff verification rewrite. Executable probes now pin the existing divergence, including JS rejection of 283 and 26. A new transaction signature policy would need versioned activation. |
| L4 | Keep the explicitly trusted policy scope. Document that `applicationDomains` must enumerate every historical application domain requiring drain; an empty vector is not an automatic proof that no application ran. |
| L6 | Check the nonempty fast lock subset before optional-lock validation, so the intended `InvalidLength` failure is reachable. Assert its exact code and field publicly. |
| L7 | Preserve the existing `(slot, digest)` binding identity; slot-only uniqueness is not part of the frozen format. Document the distinction rather than silently narrowing accepted manifests. |
| L8 | Assert the exact error code and diagnostic for each ancestry mutation. Separate expired-consumer and mismatched-producer-deadline cases. |
| L9 | Bound remembered maintenance request identities (4096 by default; `boundedWithCapacity` factory). At capacity, new identities stall without evicting previous attempts or permitting rebinding; known requests still observe finality after budget exhaustion. |
| D1 | Keep the two P1 fixture manifests as archived identities at reachable commit `b9fa3f0`; remove claims that they verify against current files. A new correction manifest records this revision. |
| D2 / D3 | Correct ADR-0037 and plan-index status and replace nonexistent symbol names with the actual public names. Match maintenance diagnostics to `Stalled(reason)` and make target reconstruction an explicit embedding-source responsibility; local counters are not durable state. |
| D4 | Use reachable `adf074c` and `cb0f120` plus the corresponding SHA inventories to reproduce archived gates. Remove temporary dangling-tree identities as reproduction instructions. |
| D5 | Compile `LegacyRecoveryAlias.scala` in the standalone JVM build and execute it after selected-artifact verification in every profile. Run JS recovery-rejection assertions in both legacy and V2 JS outputs. |

## Review iterations and validation

The initial focused run exposed a second exact-QC comparison in the controller path and the public bootstrap state adapter. Both now authenticate the proposed certificate over the installed subject; the negative signature/quorum assertions remain. The initial observer change also needed explicit arguments to satisfy the repository's default-argument lint rule.

The subsequent documentation review found that the old maintenance contract overstated diagnostic fields and automatic target retention. The actual API returns a reason string on stalls, and `HotStuffMaintenanceSource` must reconstruct its target from the retained drain command after restart. The contract now states those boundaries and identifies counters/capacity as provider-local state.

A later lifecycle review found one additional M5 bug: a failed vote could leave the safety store requiring recovery while the last successful observer memo remained reusable. The new public regression first failed on the unchanged-observation retry assertion after an actually injected durable vote-write failure. Voting/signing now invalidates reuse on every failure or cancellation before releasing the gate. The same case checks successful recovery/retry, an actually entered throwing signer and a cancelled signer. Its first post-fix run exposed a test expectation mismatch (`SignerFailure`, not `StorageUnknown`, is the existing key-use exception contract); the assertion now checks the exact signer code and diagnostic.

Core JVM **552**, core JS **551**, node-common JVM **134** and node-common JS **133** cases passed after the fast-certificate failure correction. The same sbt process then exhausted its default 1 GiB heap compiling node sources; that invocation is not a successful node gate. Node verification was rerun with `-J-Xmx4G`.

The focused node gate passed **61 cases**, including all five four-node bootstrap/restart cases with divergent initial QCs, both new audit-scope cases, observer recovery/conflict cases, ancestry diagnostics, signing leases/deadlines and maintenance capacity. A further assertion exercises pending finality discovered by the signing path; all **12** finalizer cases passed again with that assertion at **13:01:20 KST**. That intermediate candidate passed `scalafmtCheckAll` before its five local artifacts were staged.

Standalone public **M1** and **M2** runs completed successfully. Their final JS optimizations completed at **13:04:07** and **13:04:26 KST**, respectively, followed by successful direct Node.js execution. Each selected all five pinned public JARs, passed the automatic JVM historical recovery/HotStuff alias probe, and passed legacy vectors and the new JS recovery-parameter rejection checks in both fast and fully optimized execution. Their source inputs are unchanged by the final observer correction.

The intermediate V2 candidate completed all **28 JVM groups** at **13:20:25 KST** and both JS outputs (final optimization at **13:20:37**), with **exit 0**. This predates the last observer lifecycle fix and is not the final V2 gate. The concurrent source node run was deliberately stopped after **952** passing cases, with exit **143**, to compile the regression and corrected implementation; it is not a passing full target. The final node and V2 candidate runs use new artifacts and validation identities. All **27** finalizer, signing-lease, authorization and last-moment deadline cases passed after the last fix at **13:28:29 KST**, including the new durable-write, throwing-signer and cancellation retry checks; `scalafmtCheckAll` also passed.

## Final verification

The complete corrected node target passed **972** cases, with zero failures/errors and **exit 0**, at **13:49:58 KST** in **1140 seconds**. Every discovered suite was retained. Combined with the unchanged final core/common source gates, the complete source matrix passed **2,342 tests**:

| Source target | Passed |
| --- | ---: |
| core JVM | 552 |
| core Scala.js | 551 |
| node-common JVM | 134 |
| node-common Scala.js | 133 |
| node JVM | 972 |

The final exported V2 consumer completed with **exit 0**, using only the five selected final Maven artifacts. It compiled **50 JVM** and **11 Scala.js** sources without private tests or the parent source build. All **28 distinct JVM groups** passed, finishing at **13:49:30 KST** in **1041 seconds**. Scala.js fast execution completed at **13:49:37** and full optimization at **13:49:44**, followed by successful direct optimized Node.js execution. Closure reported zero errors and warnings. Both JS outputs include the shared protocol/certificate/opening checks, the unchanged 55 transition literals and the historical recovery-parameter rejection probes.

The actual four-node ordinary Gossip scenario formed all three target quorums and materialized finality in both executions: **264.841 seconds** in the source node target and **256.057 seconds** in the standalone consumer. These are observed integration durations, not a per-vote performance bound; M4 remains open.

## Candidate identity

The [subsequent input review](public-input-review-corrections-2026-09-12.md) found that this gate's 378-path inventory omitted the JVM/JS platform sources and vector exporter. The 378 source and 19 non-V2 input counts below describe the enumerated subset only; they do not establish complete compiler-input coverage. The later gate records independent path discovery and actual compiler-selected input ledgers. This archived inventory remains reproducible at `7fe1cff`.

The [correction source/configuration/fixture inventory](full-review-correction-sources.sha256) covers **378** files and has SHA-256 `bed81f9500108c070fec48b40ec15f632e1acbea0fb518ad5a3249275ae1fcc2`. The [candidate artifact inventory](full-review-correction-artifacts.sha256) records the five JARs published only to `/tmp/sigilaris-0033-second-review-final-maven`. The listed input bytes were frozen during the source and standalone executions; the later review identifies the coverage gap above. No temporary Git tree is used as a cloneable identity.

The final candidate export matches all **378** source hashes, all **five** selected V2 artifact hashes and all **ten** pinned public M1/M2 artifact hashes. All **19** non-V2 consumer inputs remain byte-identical to the successful historical runs. The final observer fix changed only the node JAR; the four core/common JARs retain their earlier hashes and source-test evidence. The independent transition oracle passes **55** literal vectors, and no private `src/test` tree survives export. The candidate documentation check resolves **322** local links, including **three** heading anchors. The final public export, with the completed execution record, passed the same source/artifact, legacy-input, archive, oracle and documentation checks; `git diff --check HEAD` also passed.

Archived checksums were verified against reachable commits using the bytes returned by `git show`: P1 legacy **12/12** and P1 V2 **19/19** at `b9fa3f0`, P6 **376/376** at `adf074c`, and the prior review **378/378** at `cb0f120`. All these source manifests and both archived candidate artifact manifests retain their original bytes. Only reproduction/status prose is corrected in the earlier gate documents.

## Reproduction

Use the commit containing this correction record for the corrected source and export. Check the correction inventory from the repository or exported root; the older P1 inventories use paths relative to `release-conformance` at their archived commit.

```sh
shasum -a 256 -c docs/conformance/full-review-correction-sources.sha256
sbt -J-Xmx4G scalafmtCheckAll \
  'coreJVM/test' 'coreJS/test' 'nodeCommonJVM/test' 'nodeCommonJS/test'
sbt -J-Xmx4G \
  'set Global / concurrentRestrictions := Seq(Tags.limitAll(4), Tags.limit(Tags.ForkedTestGroup, 2))' \
  'set nodeJvm / Test / testGrouping := { val tests = (nodeJvm / Test / definedTests).value; val (transport, remaining) = tests.partition(_.name.endsWith(".V2HistoricalTransportSuite")); require(transport.size == 1); val options = (nodeJvm / Test / forkOptions).value; val ordered = remaining.sortBy(_.name).zipWithIndex; Seq(Tests.Group("historical-transport", transport, Tests.SubProcess(options))) ++ (0 until 2).map(index => Tests.Group("remaining-node-" + index, ordered.collect { case (test, position) if position % 2 == index => test }, Tests.SubProcess(options))) }' \
  'nodeJvm/test'
sbt -J-Xmx4G \
  'set ThisBuild / publishTo := Some(Resolver.file("candidate", file("/tmp/sigilaris-0033-second-review-final-maven"))(Resolver.mavenStylePatterns))' \
  'coreJVM/publish' 'coreJS/publish' 'nodeCommonJVM/publish' 'nodeCommonJS/publish' 'nodeJvm/publish'
scripts/export-public.sh HEAD /tmp/sigilaris-public-export.0033-review-reproduction
cd /tmp/sigilaris-public-export.0033-review-reproduction/release-conformance
python3 tools/verify-transition-vectors.py
./run-conformance.sh 0.3.0-M1 legacy-m1
./run-conformance.sh 0.3.0-M2 legacy-m2
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/tmp/sigilaris-0033-second-review-final-maven
```

Every discovered node suite is retained in three fork groups, with at most two running concurrently. The historical transport suite has its own group; the remaining suites, sorted by name, are split by alternating index. No suite or assertion is filtered out. Candidate publication is explicitly local; these results do not establish a public M3 release or operational activation.

## Final review

The selected corrections were re-reviewed after the signing-failure regression and documentation fixes: **No finding** in the changed code, regression assertions, historical compatibility boundaries and corrected documentation. The review checked complete initial-QC authentication, independent audit lifetime coverage, exact observer proof identity, pending-work handling, failure/cancellation cleanup before gate release and maintenance identity retention. All final required source and consumer checks passed. This verdict does not close M3/M4 or L1/L3/L5/L10; their storage and lifecycle design work remains explicitly open in plan 0034.

## Lessons and remaining work

- A QC authenticates a subject; multiple canonical vote subsets can prove it. Exercise independently assembled initial certificates through real runtime and original-recovery paths.
- Coverage against an adapter's scope does not prove scope completeness. Validate lifetime coverage independently, while permitting legitimate overlap among different identities.
- Observer reuse is an optimization of an already successful notification, not a signing or recovery capability. Preserve pending finality, exact proof identity and current key-use authentication; failures and cancellation in the signing path must also invalidate reuse before gate release so an identical observer can drive recovery.
- Bound local bookkeeping without silently resetting attempts or rebinding identifiers. Capacity failure is local availability, not invalid finality.
- Keep historical manifests verifiable at reachable commits. Record new identities for changed source; do not relabel old runs as evidence for the latest implementation.
- A focused correction pass can finish with no new findings in its changed code while the explicitly listed storage and lifecycle design backlog remains open. Do not describe the whole system as free of findings.
