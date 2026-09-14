# Public M2 conformance baseline

This evidence belongs to plan 0033 P0.3. It preserves published M2 behavior;
expected rejection or missing durability is a reproduced limitation, not a
new-profile acceptance result.

## Identity and reproduction

- Observation date: 2026-09-11 (Asia/Seoul).
- Source checkout at fixture preparation: `efe4f4529a1c266e024ea8be74b3c7ad58a08847`.
- Published M2 tag source: `cc9a77631f4e988a3f18fbf8983857aef59be302` (`v0.3.0-M2`).
- Coordinates: `org.sigilaris:{sigilaris-core_3,sigilaris-core_sjs1_3,sigilaris-node-common_3,sigilaris-node-common_sjs1_3,sigilaris-node-jvm_3}:0.3.0-M2`.
- Repository: `https://repo.maven.apache.org/maven2`; application protocol M1,
  execution-plan version 1, existing safety schema version 1.
- Tools: sbt 1.11.5, Scala 3.7.3, Scala.js 1.20.1, Temurin 23.0.1+11,
  Node.js 26.7.0, npm 11.19.0.

Run `./public-m2-baseline.sh` inside [release-smoke](../../release-smoke/README.md).
The checked-in runner downloads and checks ten public POM/JAR objects, then
checks the actual compiler classpath JARs. It uses only the public repository,
a dedicated Coursier cache, isolated global sbt configuration and standalone
source files. No Maven Local, Ivy Local, parent-source dependency, or private
fixture is configured. The five coordinates and all ten SHA-256 identities
are preserved in the [baseline manifest](../../release-smoke/fixtures/m2-checksums.sha256),
identical to the [published M2 manifest](../releases/v0.3.0-M2-checksums.sha256).

## Maintained cases and observed outcomes

| Named fixture | Actual M2 result | Evidence boundary |
| --- | --- | --- |
| `M2SharedBaseline.mixedReadWriteDescriptor` | Both read and write stable identities enter the lock and footprint preimages; domains produce different commitments. Removing a mutable read identity yields `StableConflictIdMissing("read")`. | Public core consumer, JVM and Scala.js. Independently reconstructs the two old preimages and pins bytes/digests. It does not invent authority eligibility in M2. |
| `M2SharedBaseline.historicalEmptyPlan` | V1 empty plan yields `EmptyPlan`; a present empty wave yields `EmptyWave(0)`. | Public core consumer, JVM and Scala.js; pins canonical invalid-plan bytes/root and compatibility-singleton bytes/root. |
| `M2SharedBaseline.historicalEmptyPlan` compatibility check | `compatibilitySingleton(id)` equals an ordinary ordered singleton. | Reproduces the old entry representation without asserting new source/declaration/classification semantics exist. |
| `M2JvmBaseline.conflictingPreCertificateVotes` | Concurrent validations for different executions over one input both return success; complete snapshot and journal remain unchanged; no deadline is recorded. Reconstructed runtime accepts the retry. | Public in-memory runtime, pre-sign validation only. This does not sign votes or claim a four-validator conflicting quorum. Runtime reconstruction is not a persistent process-restart test. |
| `M2JvmBaseline.genericEffectVoteBoundary` | Missing lock yields `lockCertificateInvalid`; missing reservation yields `reservationMissing`; the recorded same-deadline lock plus reciprocal reservation permits two distinct effect result subjects without changing snapshot/journal; altered deadline yields `deadlineMismatch`. | Public generic effect validation. The prior lock certificate records deadline coverage; this read-only method records no new vote subject. |
| `M2JvmBaseline.completeReservationSuperset` | A reservation adding a second identity outside the lock set returns `reservationMissing`; the exact certified set succeeds. | Public safety runtime with deterministic neutral certificate authenticator. |
| `M2JvmBaseline.emptyLockExactStages` (`orderedAtomic`, `certifiedAncestor`) | Exact lock certification and both producer/consumer effect validations fail with `lockCertificateInvalid`. Registration persists the plan deadline while the lock map remains empty. | Public exact store/runtime and safety runtime. Covers each stage in both modes, not a lock-free application success path. |
| `M2JvmBaseline.historicalFinalizedAncestor` | A complete context containing only a newer finalized tip rejects the older producer as `producerNotAncestor`; explicitly retained ancestor membership succeeds; incomplete ancestry yields `producerAncestorUnavailable`. | Public exact runtime with constructed public contexts. The private default branch-context builder itself is not invoked; ordinary runtime reconstruction remains separately mapped source/runtime evidence. |
| Existing `ReleaseSmoke` entry points | Invalid verifier manifest digest returns the typed failure; exact store factory, missing request, and unjournaled admission count retain their published behavior. | Public node-common on JVM/Scala.js and node-jvm. |

Certificate fixtures deliberately use a deterministic callback that accepts the
expected signing preimage as a test signature. They exercise artifact binding
and runtime interlocks through the supported authentication hook; they do not
establish cryptographic signing safety, Byzantine quorum execution, or a
production authenticator.

## Execution record

The final `./public-m2-baseline.sh` run completed on 2026-09-11 at 12:49 KST
with exit code **0**. All ten POM/JAR downloads matched the release checksums;
all five actual compiler JAR identities passed; JVM, Scala.js fast-linked and
fully optimized Node consumers passed. Closure reported zero errors and zero
warnings. The first run used a newly created dedicated cache; the final rerun
reused that public-only cache after the review corrections and cleaned both
consumer outputs.

The [retained output](../../release-smoke/fixtures/m2-baseline-output.txt)
contains the observed checksums, pinned canonical bytes/digests and named
outcomes in execution order. The [fixture source checksum manifest](../../release-smoke/fixtures/m2-baseline-sources.sha256)
fixes the build, runner, package lock and M2 fixture inputs used for this result;
verify it with `shasum -a 256 -c fixtures/m2-baseline-sources.sha256` inside
`release-smoke`. Scalafmt 3.11.5 checks, shell syntax and `git diff --check`
also passed.

Review round 1 corrected evidence overstatement by explicitly distinguishing
synthetic authentication, supplied branch contexts and in-memory runtime
reconstruction. It also added generic effect deadline/reservation/read-only
coverage and pinned the consumer main class. Review round 2 corrected the new
effect fixture's required reciprocal reservation and retained that rejection
as an assertion. Final self-review found **No finding** within this baseline's
stated scope. Remaining production/evidence obligations follow below.

## Remaining evidence and lessons for later phases

The default HotStuff V1-header construction, private ancestry traversal,
transport admission/registration reconciliation, exact application effect-certificate
requirements, and persistent restart boundaries need their own maintained
source/runtime fixtures in the requirement-to-evidence map. A supplied context
can test the public consumer decision but cannot prove an authenticated
historical lookup or default assembly path.

Keep these M2 fixtures pinned when introducing the replacement profile. P6
staging conformance needs a separately selected candidate coordinate and consumer
mode; the immutable M2 baseline and its no-local default must remain intact. Passing
new behavior must not change the historical vectors or turn an expected M2
failure into a silently reinterpreted success. Registration deadline coverage
and pre-certificate subject durability must be asserted independently. Public
artifact verification must check the actual compiler classpath, not merely
separate downloaded copies. The standalone build and fixtures must continue to
work when exported without parent or private sources.

No staging, tag, publication, deployment activation, plan 0032 operational
closure, or dependency-security remediation is claimed by this baseline.
