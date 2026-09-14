# P0 opening reference contract evidence

The maintained [P0OpeningContract](../../release-smoke/shared/src/main/scala/P0OpeningContract.scala)
is an executable policy reference for plan 0033 P0.2. It exercises neutral
opening decisions using public M2 cryptographic primitives. It is **not** the
production V2 opening codec, application runtime, manifest verifier, certificate
verifier, state importer, ancestry verifier, or activation coordinator.
P1 and P5 must reproduce these decisions at the public production boundaries
specified by the [V2 contract](v2-contract.md), [core schema](v2-core-schema.md),
and [runtime schema](v2-runtime-schema.md).

## Reproduction and identities

Run from `release-smoke`:

```sh
./p0-opening-contract.sh
```

The runner uses only `https://repo.maven.apache.org/maven2`, the same dedicated
public Coursier cache and isolated sbt global configuration as the
[M2 public baseline](m2-baseline.md). Maven Local and Ivy Local are excluded.
It verifies the actual JVM and Scala.js compiler JARs against the five public
M2 [artifact checksums](../../release-smoke/fixtures/m2-checksums.sha256), then
executes the oracle with JVM `runMain`, Scala.js fast linking and fully optimized
Node.js. The selected Scala.js main class is changed only for that sbt session;
the checked-in default remains `ReleaseSmoke`.

The application fixture signs its own envelope under
`neutral.application.opening.reference.v1`. M2 `ByteEncoder`, `CryptoOps.sign`,
`CryptoOps.recover`, and Keccak are real published implementations. The fixture
uses deliberately public private scalars 1 and 2 for the authorized/unauthorized
keys. These are reproducibility inputs with no deployment authority. Its
application envelope encoding does not select or replace the production
`OpeningEnvelope` encoding or the `sigilaris.application.opening.v1` domain.

## Maintained policy cases

| Case | Required reference decision |
| --- | --- |
| Authorized neutral conversion | A signed opening increments the existing consensus-only counter and creates a target from a read-only input; repeated execution returns identical output. |
| Signed deadline without locks | Tampering with the signed deadline fails authorization; zero or excessive lifetime fails deadline validation even with no eligible locks. |
| Maintenance authority | Recovering a signature under a different trusted authority rejects the opening. |
| Complete read/write/create coverage | Omitting the absent creation target, omitting the read, or replacing an actual write by a read declaration rejects the opening. The resulting reservation binds the target domain, deadline and full witness. |
| Undeclared existing eligible mutation | Changing the existing counter to lock-eligible without an Exact declaration yields `undeclaredEligibleMutation`. |
| Initial bootstrap | Authenticated anchor base `G=0` and first ordinary descendant height `1` are required. Nonempty eligible subsets fail with either absent or present certificate. An empty subset requires `None`. |
| Ordinary handover | The candidate must be above the authenticated finalized base; an eligible mutation requires a fresh Exact value and matching lock certificate. Absent certificate, stale value and certificate deadline mismatch reject. |
| Inclusion interval | For the fixture maximum lifetime 64, require `B < D <= B + 64` and `B < H <= D`. Equality/below-base heights reject; inclusion at the signed deadline succeeds and inclusion above it rejects. |
| Expiry interval | Reference expiry requires matching domain, finalized height strictly above the signed deadline, and nonapplication. Wrong domain, equality at the deadline and missing nonapplication reject. |

The fixture keeps opening as one deterministic conversion and one complete
claim. It models read/write identities with local ADTs and lock eligibility
with fixture data, and it checks certificate bindings with a local value type.
`baseAuthenticated` and `nonapplication` are supplied reference predicates;
this fixture does not generate their authenticated evidence. Its fixed root,
manifest, classification-reason and parent digests are binding identifiers,
not proofs of complete source-state data or HotStuff ancestry. Actual access
coverage is a declared neutral conversion witness, not production AccessLog
instrumentation. It therefore supplies no durable reservation, atomic commit,
capacity/chunking, persistent recovery, drain/handover, archive-absence,
never-enabled, real quorum, or deployed-validator evidence.

## Review and execution result

The initial review found that the reference validator did not require the
handover candidate height to be above the authenticated base. The correction
adds `actualHeight > base`; maintained equality/below-base regressions also
reject negative heights. The follow-up review found **No finding** within the
reference policy's stated scope. Production implementation and evidence gates
remain in the [requirement-to-evidence map](v2-evidence-map.md).

The corrected `./p0-opening-contract.sh` completed on **2026-09-11 at
12:52 KST**, exit code **0**. JVM, Scala.js fast-linked and fully optimized
Node.js executions all passed; Closure reported zero errors and zero warnings.
The run used the public cache populated by the M2 baseline, and all five actual
compiler artifact hashes were rechecked. Tool versions were sbt 1.11.5,
Scala 3.7.3, Scala.js 1.20.1, Temurin 23.0.1+11, Node.js 26.7.0 and npm 11.19.0.

The [retained output](../../release-smoke/fixtures/p0-opening-output.txt)
records the compiler identities and three observed oracle results. The
[source checksum manifest](../../release-smoke/fixtures/p0-opening-sources.sha256)
binds the maintained oracle, runner, M2 consumer sources and shared build
inputs used for this run. Verify it inside `release-smoke` with
`shasum -a 256 -c fixtures/p0-opening-sources.sha256`.
Scalafmt 3.11.5, shell syntax, document relative links and whitespace checks
also passed. These results close the reference fixture review, not any
production V2 implementation, activation or release gate.

## Lessons for production implementation

Validate both signed lifetime and actual inclusion height relative to the
authenticated base. A signed deadline alone does not establish a valid
candidate height. Derive lock eligibility from authenticated input facts and
complete actual writes before testing certificate presence; omitting an
eligible mutation cannot turn bootstrap into a supported lock-free opening.
Keep the full read/write/create reservation even when the eligible lock subset
is empty. Use real imported-state, manifest, certificate, ancestry and
nonapplication verifiers in production; a passing reference predicate cannot
supply those proofs.
