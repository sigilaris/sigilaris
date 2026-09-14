# Public historical protocol vectors

[LegacyProtocolVectors](../../release-conformance/shared/legacy/scala/org/sigilaris/conformance/LegacyProtocolVectors.scala)
is an exported, application-neutral consumer of public M1/M2 core APIs. It
runs independently on JVM and Scala.js and keeps protocol-1 validation
provenance explicit. It complements the [core compatibility inventory](core-compatibility-inventory.md)
and [M2 runtime baseline](m2-baseline.md).

## Immutable artifact provenance

| Selected profile | Public coordinates | Tagged source |
| --- | --- | --- |
| `legacy-m1` | Five `org.sigilaris` coordinates at `0.3.0-M1` | `1c8c2b7c394b923e89800efcced800b7844861ae` |
| `legacy-m2` | Five `org.sigilaris` coordinates at `0.3.0-M2` | `cc9a77631f4e988a3f18fbf8983857aef59be302` |

The five coordinates are `sigilaris-core_3`, `sigilaris-core_sjs1_3`,
`sigilaris-node-common_3`, `sigilaris-node-common_sjs1_3`, and
`sigilaris-node-jvm_3`. Both milestone artifacts use `ProtocolVersion.M1`.
The [M1 JAR manifest](../../release-conformance/fixtures/legacy-m1-artifacts.sha256)
and [M2 JAR manifest](../../release-conformance/fixtures/legacy-m2-artifacts.sha256)
retain the corresponding entries from the original public release checksum
manifests. The runner checks every actual compiler JAR against these identities;
wrong coordinates, historical profile/version combinations, local publication
cache paths and public runs resolving outside Central fail.

From [release-conformance](../../release-conformance/README.md), run:

```sh
./run-conformance.sh 0.3.0-M1 legacy-m1
./run-conformance.sh 0.3.0-M2 legacy-m2
```

The runner resolves only public Central with isolated sbt/Coursier configuration
and no parent-source dependency. Each command executes JVM, Scala.js fast-linked,
and fully optimized Node consumers. Tools are sbt 1.11.5, Scala 3.7.3,
Scala.js 1.20.1, Temurin 23.0.1+11, Node.js 26.7.0 and npm 11.19.0.

## Historical language distinction

For input `"  neutral.family  "`, public M1 stores and encodes the original
nonblank value; public M2 stores its trimmed form:

| Provenance | Encoded family bytes | Repeated stable id on mutable read/write |
| --- | --- | --- |
| M1 | `1220206e65757472616c2e66616d696c792020` | Accepted |
| M2 | `0e6e65757472616c2e66616d696c79` | `DuplicateStableConflictId` rejection |

Both raw vectors are retained as literal bytes. The M1 vector is also checked
against raw `Utf8` encoding, so a new parser is never used to reconstruct the
old spaced value. Selecting protocol integer 1 alone cannot choose historical
validation behavior. Later replay/migration dispatch must use authenticated
artifact/configuration/deployment provenance; the consumer does not implement
that production dispatch.

## Shared canonical vectors and rejection cases

The fixture independently constructs the full resolved-input, lock and
footprint preimages for neutral read identity `aa` and write identity `bb`.
M1 and M2 agree on these distinct-domain commitments:

| Commitment | Hex digest |
| --- | --- |
| Full input | `1f75759b1a09fbfd53e272070320894ca124b03b3f80e3d95347bf2f7b840748` |
| Lock subset | `0f1a270021fbf1582ac9388f75449f8aad654f46533bb97597e5356a801142ef` |
| Footprint | `aa2b6dd77263e934041ad770ead2f6a708ed6c944ac133047784ee89575d804a` |
| Invalid empty V1 plan root | `a57ba92e8039cd7cdafc2efb59ec67938254cf872a46f3accb3fb958e526ac4c` |
| V1 compatibility singleton root | `1992e415b633263a7e62eaf030617ce98851b1d50070c4465361b2e229a20a02` |
| V1 ordered `[01,02]` root | `a06a14cbe90a0894d9c09de125960bfc059740aeaad0914c245ae83b6d2ecbf3` |
| Keccak of neutral encoded lock certificate | `2c4cc13676a4f172f925fe29ce57de7468b3f235054bfc513b7df30c8dc8c9cc` |
| Keccak of neutral encoded effect certificate | `375f651f59f3969a7ef1dd4e5f577e20bae9b61472be3abbcdfd7c8d1b661fb1` |

Complete preimage bytes and signing preimages are pinned in the shared fixture
and retained output. The certificate digests above are explicitly raw Keccak
of encoded certificates, not a newly invented historical certificate-ID domain.
The certificate fixture injects an equality authenticator for the expected
signing preimage. It checks encoding, membership, sorting, quorum and duplicate
rejection; it is not cryptographic-signature or four-validator security evidence.

The fixture additionally checks field-order normalization, ordered-wave root
sensitivity, exact body membership, conflict-free canonical ordering, repeated
execution rejection, empty-plan rejection, vote-order canonical encoding,
insufficient quorum, repeated signer rejection and empty lock-input rejection.
All fixed hashes were obtained by executing public artifacts, then made into
literal assertions; none was guessed from an unexecuted implementation.

## Execution and review evidence

Both immutable profiles completed on **2026-09-11**, each with exit code
**0** for JVM, Scala.js fast linking, and fully optimized Node.js. The final
runs included exact compiler-coordinate checks and the original release JAR
SHA-256 checks. Closure reported zero errors and zero warnings. Retained
[M1 output](../../release-conformance/fixtures/legacy-m1-output.txt) and
[M2 output](../../release-conformance/fixtures/legacy-m2-output.txt) preserve
the observed vectors and results in execution order. The
[source manifest](../../release-conformance/fixtures/legacy-sources.sha256)
fixes the historical build/runner/fixture inputs at commit `b9fa3f0`. In that checkout, run `shasum -a 256 -c fixtures/legacy-sources.sha256` from `release-conformance`. It is an archived inventory and does not verify the subsequently changed build in the current checkout. Current source identities are recorded in the [review correction gate](full-review-corrections-2026-09-12.md).

Review corrected a portability issue with empty Bash arrays under macOS Bash
3.2, rejected historical profile/version mismatches, and pinned observed
compiler JAR hashes against original release identities. It also replaced
platform-dependent Unit rendering in output with explicit accepted/rejected
labels. These corrections preserve the protocol assertions. Final historical
fixture review found **No finding**. V2 source/artifact results are separate
from these completed historical runs.

The new V2 empty plan has a different format and commitment domain. Passing
these historical fixtures must continue alongside the V2 consumer. It does not
permit interpreting old empty-plan bytes as newly valid or applying M2's parser
and duplicate rules to authenticated M1 history. Node block/header, actual
historical ancestry, durable restart and activation evidence remain separately
owned by their phase gates.
