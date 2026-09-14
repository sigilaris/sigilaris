# Phase 1 core conformance gate

This gate covers the additive core contract in [plan 0033](../plans/0033-application-execution-conformance-and-v2-activation-plan.md).
The V2 API is `org.sigilaris.core.application.protocol.v2`; published protocol-1 codecs and APIs retain their original definitions.
Core source verification, immutable public historical artifacts, and the later V2 staged-artifact gate are separate evidence classes.

## Implemented boundaries

- Complete manifest-bound resolved inputs, independently committed eligible mutation locks, declared and actual read/write footprints.
- Strict canonical codecs and explicit version/tag rejection; protocol witness limits are checked before vector allocation.
- Full source/declaration/classification plan entries, exact body membership, source-id ordering, compatibility isolation and cross-wave conflicts.
- Canonical format-2 empty plans; the existing format-1 validator continues to reject its historical empty plan.
- Complete lock/effect subjects and certificate references, historical signer membership, actual signatures and optional consensus locks.
- Signed maintenance opening envelopes and immutable conversion preparation, including lock-free bootstrap and normal handover certificate rules.
- Canonical complete reservation witnesses, one metadata-bearing empty chunk, authenticated segmentation and complete reassembly.

`InputDerivation.derive` is structural. Authentication additionally verifies every signed field, manifest, precondition and authority; immutable
and opaque fields receive canonical empty transport proof rather than escaping signed-field verification. Actual access instrumentation includes
precondition/authority reads, so an unsuccessful reducer cannot omit those identities from reservations.

`EntryAuthentication`, `InputAuthentication`, `ArtifactAuthentication` and maintenance authorization are configured trusted adapters.
Core checks their returned bindings and commitments; public runtime construction and revalidation belong to Phases 2–5. The opening helper
prepares working state only. It does not establish live parent/finality, reservation, expiry, canonical publication or deployment activation.

## Maintained verification

The exported [historical consumer](historical-protocol-vectors.md) checks original M1/M2 artifacts and immutable hashes on JVM, Scala.js fast
linking and fully optimized Node. Both immutable profiles passed their independent public runs on 2026-09-11.
The source gate compiles the same [V2 consumer](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2CoreConformance.scala)
and [real certificate consumer](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2CertificateConformance.scala)
inside both core test targets. These sources are also used unchanged by the later independent Maven consumer.

Private module suites supplement those exported inputs with input authentication, source/statement substitution, read protection,
real maintenance signatures and opening execution negatives. Four public fixture keys test real 3-of-4 certificate cryptography;
this is not four independent HotStuff runtimes or finality evidence.

On **2026-09-11**, the reviewed source completed these gates:

| Command / evidence | Actual result |
| --- | --- |
| `coreJVM/test` | 526 passed, zero failures/errors; 13:31 KST (default JVM heap) |
| `sbt -J-Xmx4G coreJS/test` | 525 passed, zero failures/errors; 13:32 KST |
| `coreJVM/testOnly *V2PublicCoreConformanceSuite` | 2 passed after the final exported negative-case additions; 13:33 KST |
| `coreJS/testOnly *V2PublicCoreConformanceSuite` | 2 passed with the same 16 byte vectors and fixed signature/digest assertions; 13:33 KST |
| `scalafmtCheckAll`, `scalafmtSbtCheck` | Passed at 13:34 KST |

Six maintained new suites contain 43 test cases per platform: 16 input, 8 plan, 6 certificate, 7 opening, 4 Low-S signing/recovery and 2 shared
public-consumer wrappers. The latter execute the exported positive/negative cases and [16 literal vectors](v2-public-vectors.md).
The first full Scala.js link exhausted the launcher's default 1 GiB heap; a fresh 4 GiB invocation passed. This was a build-process capacity
failure, not a candidate-validation result. Existing build/plugin formatting was normalized to pass the build-definition formatting gate.

## Review iterations and lessons

1. Corrected signed literal-field authentication and added rehashed tampering rejection.
2. Included Exact precondition/authority reads in actual reservation coverage, including no-write results.
3. Removed an undocumented nonempty classification proof requirement and certificate/signer limits inherited from unrelated witness limits.
4. Added protocol-bound decoding before allocating advertised element counts and a byte-limit case whose element count remains permitted.
5. Corrected exported signature serialization to the selected 72-byte representation and compared actual cryptographic recovery.
6. Preserved typed decode failures and the actual diagnostic message instead of depending on Throwable rendering.
7. Actual Scala.js certificate execution exposed missing Low-S normalization in the existing JavaScript signer. The correction preserves
   signature framing and historical verification while making new signer output satisfy the same canonical contract as JVM. This functional
   defect is separate from the release notes' dependency-security remediation; passing these cases does not complete that work.

After these corrections, independent reviews of inputs/plans and certificates/opening reported **No finding**. Every discovered execution failure was corrected and the affected gate rerun successfully; review alone was not treated as completion. Remaining-phase lessons are incorporated directly into
the plan, including public source-fixture reuse, authenticated runtime capabilities, actual-read reservations and opening publication boundaries.

## Remaining evidence

Durable pre-sign voting, reservations and application recovery are Phase 2. Exact execution and canonical historical lookup are Phase 3.
Ordinary HotStuff integration, actual four-validator finality and idle progress are Phase 4. Historical activation schedules, bootstrap and
handover are Phase 5. Candidate staging, public export and final all-module checks are Phase 6. No artifact publication or deployment was performed.
