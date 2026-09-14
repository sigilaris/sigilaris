# P6 public conformance and candidate readiness gate

Status: complete on 2026-09-11. Final source, exported consumer, candidate artifacts and final public export identity gates passed. Source and candidate evidence remain separate from future public publication.

## Reproducible boundaries

The selected development version is `0.3.0-M3-SNAPSHOT`. All five Maven coordinates are published only to an explicitly selected local Maven staging directory for this gate. No public release, remote publication, deployment or existing-chain migration is performed.

The public export removes every `src/test` tree. Exported shared main fixtures contain the exact assertions reused by thin source wrappers, including canonical application recovery, ancestry, complete groups, persistent journals, key-owning controllers, transition inventories, opening validation, original issuance, initial bootstrap and ordinary historical handover. The standalone build resolves selected artifacts and has no parent source-project dependency.

```sh
sbt scalafmtAll scalafmtCheckAll \
  'coreJVM/test' 'coreJS/test' 'nodeCommonJVM/test' 'nodeCommonJS/test' 'nodeJvm/test'

# In the source tree: this explicit override is required; do not use the default publish destination.
sbt 'set ThisBuild / publishTo := Some(Resolver.file("candidate", file("/tmp/sigilaris-0033-p6-maven"))(Resolver.mavenStylePatterns))' \
  'coreJVM/publish' 'coreJS/publish' 'nodeCommonJVM/publish' 'nodeCommonJS/publish' 'nodeJvm/publish'

# In the exported tree, with only its standalone build and selected Maven artifacts:
cd release-conformance
python3 tools/verify-transition-vectors.py
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/tmp/sigilaris-0033-p6-maven
```

The consumer script isolates Coursier and sbt configuration, forbids Maven Local and Ivy Local, checks exactly three JVM and two Scala.js artifacts, and runs JVM, Scala.js fast output and fully optimized Node.js output. JavaScript dependencies are pinned by `package-lock.json`: `elliptic` 6.5.4 and `js-sha3` 0.8.0. The validation environment uses Scala 3.7.3, sbt 1.11.5, Temurin Java 23.0.1, Node.js 26.7.0 and Python 3.14.6.

## Actual results and identity

The final source gates passed `scalafmtCheckAll`, core JVM **550**, core JS **549**, node-common JVM **134**, node-common JS **133** and JVM node **958** cases: **2,324 passing tests** across all five targets. The final full node recheck finished with zero failures at **22:56:37 KST**, in **1669 seconds**. Its actual four-node Gossip scenario passed in **1601.624 seconds**; the earlier full-run scenario had also passed in 1716.210 seconds. The standalone candidate script has completed with **exit 0**, including every JVM group and both JS outputs. The selected-artifact ordinary four-node Gossip handover has passed, including actual B6/B7/B8 quorums, B6 finality and canonical application on all four nodes (**1677.520 seconds**). Its direct controller/lock/restart/source-loss gate also passed (**248.498 seconds**).

The actual standalone compiler resolved exactly five artifacts from the explicit file Maven repository. All five SHA-256 values match the [candidate artifact inventory](p6-candidate-artifacts.sha256). The consumer successfully compiled **48 JVM** and **11 Scala.js** Scala source files from the public export, without the parent build or private test classes. JVM execution finished at 22:11:07 KST, JS fast execution at 22:11:13, and full optimization plus direct optimized Node.js execution completed at 22:11:20. All three executions passed the 16 core vectors, 55 transition literals, eight transition schema families, seven production opening cases and real signature/certificate validation. JVM additionally passed all **27** runtime/storage scenario groups.

The compiled candidate source/configuration/fixture bytes are preserved in commit `adf074c`, following P5 commit `34cae52`. Use that reachable commit and the manifest below to reproduce this archived gate; the original temporary candidate tree was not a cloneable commit. The [public source/configuration/fixture manifest](p6-source-and-fixtures.sha256) covers **376** files and has SHA-256 `46909c74dc9e186f6d176b9d99ab0da2f0f7b45f57e33a9429b41080651b9549`. Documentation and the private diagnostic helper correction were added after the candidate execution and are included in `adf074c`. Final public export verification passed with identical code/configuration/fixture bytes; the private helper is removed by export and is covered by the final 958-case source recheck.

The candidate public export passed **261** local file-reference checks with no missing target and independently verified all **55** transition literals. From an exported root, `shasum -a 256 -c docs/conformance/p6-source-and-fixtures.sha256` verifies its compiled-source and consumer input identity. The final full JVM node target also passed, as documented below. No selected artifact or fixture changed during the candidate execution.

## Executed JVM candidate scenarios

All 27 groups passed using the selected Maven JARs. Durations are observations from this gate, not throughput or production-latency guarantees. The 100000-input case persisted **16,749,354 bytes in 256 chunks**, rebuilt the complete index across restart, held on local capacity failure and rejected limit+1.

| Public scenario group | Passed in seconds |
| --- | --- |
| requests | 0.200 |
| durable voting | 1.629 |
| recoverable canonical application | 3.916 |
| canonical ancestry | 1.125 |
| exact execution modes | 7.108 |
| exact mixed success | 1.059 |
| exact mixed failure | 0.898 |
| exact boundary binding | 1.107 |
| exact finalized application | 5.890 |
| ordered voting | 0.041 |
| finalized application runtime | 12.468 |
| four actual voting runtimes | 62.847 |
| complete stopped consistency groups | 13.099 |
| key-owning fence controller and fault recovery | 1.852 |
| file journal force and corruption boundaries | 2.087 |
| initial bootstrap and retired originals | 10.961 |
| four initial bootstrap runtimes across all source forms | 59.192 |
| historical signed source and safety recovery | 27.690 |
| four original stopped handover groups | 23.969 |
| four direct handover runtimes | 248.498 |
| four ordinary handover gossip runtimes | 1677.520 |
| historical canonical publication fault | 99.595 |
| original deployment/key inventory negatives | 0.013 |
| pinned historical profile boundaries | 0.004 |
| original enabled issuance and late old quorum | 32.101 |
| three authenticated original drain routes | 24.495 |
| 100000-id durable reservation | 77.724 |

## Full source failure and correction

The first full JVM node target executed **958** cases: **957 passed**, and one existing diagnostic fixture failed before it could inject its intended sink read error. Its reflective constructor list omitted P4's new `finalizedApplicationObserver` argument. The private test helper now supplies that argument and retains every original failure-isolation assertion. The complete affected `HotStuffRuntimeServiceSuite` then passed **18** cases, and `scalafmtCheckAll` passed again. Production and public fixture bytes did not change; all 376 manifest entries and all five artifacts remain identical.

The final full `nodeJvm/test` was repeated with two independent forked groups, without filtering out any test: one contains the long four-node historical transport suite, and the other contains every remaining suite. This preserves the same 958 cases and their bodies while allowing the independent temporary-store scenarios to execute concurrently. The exact additional sbt settings are:

```sh
sbt \
  'set Global / concurrentRestrictions := Seq(Tags.limitAll(4), Tags.limit(Tags.ForkedTestGroup, 2))' \
  'set nodeJvm / Test / testGrouping := { val tests = (nodeJvm / Test / definedTests).value; val (transport, remaining) = tests.partition(_.name.endsWith(".V2HistoricalTransportSuite")); require(transport.size == 1); val options = (nodeJvm / Test / forkOptions).value; Seq(Tests.Group("historical-transport", transport, Tests.SubProcess(options)), Tests.Group("remaining-node-suites", remaining, Tests.SubProcess(options))) }' \
  'nodeJvm/test'
```

Final recheck: **958 passed, zero failures, zero errors; exit 0**, completed at 22:56:37 KST in 1669 seconds. The corrected diagnostic failure-isolation case passed in the complete run. The earlier 957/958 run is retained as a resolved failure, not described as a successful full target.

## Review and scope

The [acceptance map](v2-evidence-map.md) identifies executable public families for AC1–AC11. Earlier phase gates retain their actual source and historical artifact results. Independent extraction, oracle, latest signature-cache and public API reviews reported **No finding** after the documented corrections. The actual exported compiler run confirms no private helper/test dependency, and all five resolved artifacts match their recorded checksums. Final export verification after the documentation update passed **273** local file references, all **376** code/configuration/fixture checksums, all **five** actual resolved artifact checksums and the **55** independent literals. No private `src/test` tree survived export. Final self review of the diagnostic correction, execution records, acceptance mapping and export identity: **No finding**. All required implementation/conformance work in plan 0033 is complete.

Candidate readiness does not establish public availability. After a separately executed release, repeat `run-conformance.sh` with a fresh cache and only the public resolver, recording its new artifact checksums and actual result. That future public-Maven check remains pending.

Operational deployment additionally requires the independent [plan 0032 hardening work](../plans/0032-application-safety-and-exact-pipeline-operational-hardening-plan.md), the existing [M2 dependency-security/cryptography follow-up](../releases/v0.3.0-M2-release-notes.md#dependency-security-follow-up) and operator-supplied deployment evidence. The supported original handover adapter must have been installed before original key use and retain full safe-vote/issuance history. Missing original promises cannot be reconstructed from default legacy stores, current snapshots, empty inventories or a new version label. The implementation exposes authenticated restore eligibility; it does not execute a destructive rollback or authorize key replacement/reverse handover without a separately verified procedure.
