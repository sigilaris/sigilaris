# V2 public core vectors and consumer

The exported [V2CoreConformance](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2CoreConformance.scala)
and [V2CertificateConformance](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2CertificateConformance.scala)
exercise the additive public core API from the frozen [schema](v2-core-schema.md).
The same shared functions run in repository core tests and the standalone
[release-conformance](../../release-conformance/README.md) consumer. No private
fixture or embedding-application package is imported.

## Executed byte vectors

The JVM [V2VectorExporter](../../release-conformance/export-jvm/scala/org/sigilaris/conformance/V2VectorExporter.scala)
executed the public API and wrote the 16 representative
[canonical byte vectors](../../release-conformance/fixtures/v2-core-vectors.txt)
on 2026-09-11 at 13:29 KST. The exporter first ran the complete public core
fixture; it did not calculate expected encodings independently of the library
or copy hypothetical values from the schema. The
[freeze tool](../../release-conformance/tools/freeze-v2-vectors.py) converts that
executed file into [literal shared assertions](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2GoldenVectors.scala),
without recomputing any protocol byte or hash.

| Vector | Encoded bytes |
| --- | ---: |
| `input-manifest` | 396 |
| `input-descriptor` | 270 |
| `footprint` | 18 |
| `classification-statement` | 176 |
| `plan-entry` | 393 |
| `nonempty-plan` | 581 |
| `empty-plan` | 10 |
| `reservation-witness` | 21 |
| `witness-chunk` | 78 |
| `protocol-manifest` | 400 |
| `lock-subject` | 363 |
| `lock-signing-preimage` | 401 |
| `lock-certificate` | 592 |
| `effect-subject` | 358 |
| `effect-signing-preimage` | 398 |
| `effect-certificate` | 587 |

The shared consumer rejects missing/extra/duplicate vector names and byte
mismatches. Representative nonempty vectors cover manifest, complete
input descriptor, footprint, classification, plan entry/plan, lock/effect
subjects and certificates, framed signing preimages and a complete reservation
witness/chunk. It also pins the activated empty plan, while
[historical vectors](historical-protocol-vectors.md) preserve M1/M2 bytes and
validation behavior.

The canonical V2 empty plan is `00000000000000020000`, with root
`f57b8db122206b5093612bebea2ce59ce4c8822f2a003bf6bc506c4751d43f3b`.
Its normal framed preimage was independently checked with the pinned Node.js
Keccak implementation before the source codec comparison. Existing V1 empty
plan bytes remain invalid under their historical validators.

## Meaningful negative coverage

Input cases distinguish full inputs, authority-eligible mutations and complete
read/write/create footprints. They reject missing resolution evidence, wrong
entry-state authentication, forged authority, missing Exact preconditions,
repeated stable identities, omitted actual coverage, hidden eligible
mutations and inconsistent creation/existing classifications. Immutable/opaque
fields are authenticated against signed transaction data with canonical empty
resolution evidence; existing-state evidence remains explicit.

Plan cases reject empty present waves, bad tags, unsupported versions,
trailing bytes, incorrect body membership, declaration/actual digest
substitution, missing/unused classification statements, changed source kind,
noncanonical classification order and mixed compatibility blocks. They compare
ConflictFree entries against other waves and allow Ordered/Ordered overlap
only at the core plan layer. The plan-only test adapter deliberately isolates
those rules; its success does not replace runtime Exact freshness or reservation
authorization.

Witness cases round-trip a complete witness with 2,500 identities across more
than one canonical chunk, rejecting reversed, missing and tampered chunks.
The empty witness remains nine nonempty encoded bytes and one chunk.

Certificate cases create real deterministic signatures from public fixture
scalars 1 through 4. They check 3-of-4 verification, required lock presence for
nonempty eligible subsets, lock-free consensus, fast-source lock/effect
requirements, mismatched certificate references/deadlines/footprints,
insufficient quorum, duplicate/unknown signers, unsorted votes, high-S
malleability, trailing bytes, wrong signer key, chain-domain replay, and
lock-versus-effect signing-domain substitution. Public fixed fixture keys
have no deployment authority. The historical validator membership and finalized
base are trusted neutral facts supplied by test adapters; the tests do not
establish a four-validator HotStuff run or prove actual chain ancestry.

## Source and artifact evidence boundaries

The first JVM source run passed. The first Scala.js run exposed a real signing
inconsistency: Scala.js `CryptoOps.sign` returned unnormalized high-S signatures,
while JVM signing and the documented contract produced low-S signatures.
The V2 validator correctly rejected those signatures. The JVM golden bytes
were retained; the signing implementation was corrected rather than weakening
the canonical signature contract. This is a functional signing correction,
not dependency replacement or closure of the independent security follow-up.

The final source gate on 2026-09-11 passed 526 JVM tests at 13:31 KST and
525 Scala.js tests at 13:32 KST. After the last immutable/opaque authentication
negative cases were added, the shared public wrapper passed both of its tests
on JVM at 13:33 KST and Scala.js at 13:33:58 KST. All 16 canonical byte vectors,
commitment hashes, and deterministic signatures matched on both platforms.
`scalafmtCheckAll` and `scalafmtSbtCheck` passed at 13:34 KST. The archived P1
[source/checksum manifest](../../release-conformance/fixtures/v2-core-sources.sha256)
identifies the consumer, exporter and literal vector inputs at commit `b9fa3f0`. Both P1 manifests verify in that historical checkout; they are not current-tree manifests. These results establish the dated P1 source conformance, and all 16 literal vectors match the documented byte lengths. Current correction identities and validation are recorded in the [review correction gate](full-review-corrections-2026-09-12.md). Final fixture/evidence self-review
found **No finding** after adding signed immutable/opaque substitution cases.

Standalone staging has since passed in the [P6 gate](p6-gate.md) and [post-plan review](post-plan-review.md). Public-Maven publication still requires its own actual compiler artifact identities. The default M3 development
coordinate is `0.3.0-M3-SNAPSHOT`; no publication or availability of that version
is implied. This document claims no runtime durable voting, atomic application,
bootstrap/handover, migration, replay recovery, or deployed-validator evidence.

## Reproduction

Run the standalone V2 consumer against an explicitly staged Maven repository:

```sh
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/absolute/path/to/staged-maven-repository
```

The shared fixture is also callable from core tests as
`V2CoreConformance.run()`, including real certificate checks and all golden
assertions. For reviewed source export, add `release-conformance/export-jvm/scala`
to the JVM test sources and invoke `org.sigilaris.conformance.V2VectorExporter`
with the vector output path. A changed golden byte requires review against the
frozen version/domain contract; successful regeneration alone cannot authorize
a wire-format change.

The [public transition vectors](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/V2TransitionGoldenVectors.scala) add 55 fixed P5 schema bytes, preimages and hashes without replacing these core vectors. The [independent read-only verifier](../../release-conformance/tools/verify-transition-vectors.py) compares both JSON and Scala literals; the [P6 gate](p6-gate.md) records final JVM and Scala.js artifact execution.
