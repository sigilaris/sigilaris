# M3 publication (2026-09-14)

Status: **published and verified**. All five Central coordinates, public
artifact identities, the complete public consumer and the corrected site
deployment passed.

## Source and release identity

The annotated canonical `v0.3.0-M3` tag selects merge commit
`5aaafe7f842f4af641cd2479b2e326f20e868ae6`, whose tree equals prepared commit
`24a445387f106b3a83214f0a555e1e6b85201687`. The
[preparation record](m3-release-preparation-2026-09-14.md) retains the local
source, signing and staging gates.

The tag was pushed before `main`. Its public export completed successfully,
creating [public tag v0.3.0-M3](https://github.com/sigilaris/sigilaris/tree/v0.3.0-M3)
at commit `a3fb2802379f2c8ad420b138d8f68f7ecb9f15ab`. A fresh clone of that
public tag matched all **651** reviewed exported files and their executable
modes; private test trees and `docs/dev` were absent. The following main export
also succeeded. No concurrent main/tag push was used.

The release's [389-input inventory](public-conformance-inputs.sha256) has
SHA-256 `ea1aeb9996489a5e276b9c5e1b231b9365572f7ad9c92ead161856778c3c750b`.
The public tag is the independently cloneable source for the Maven verification
below. Later site-workflow and publication-document changes do not change
those frozen artifact/compiler inputs or move the release tag.

## Central deployment and artifact identity

`sbt sonatypeBundleRelease` uploaded the previously verified local bundle,
without running the signing or packaging tasks again. Deployment ID:
`2999d1dc-25d5-4506-815b-c317ce0c7730`.

The actual uploaded ZIP contains exactly the **120** frozen bundle files:
20 POM/main/sources/Javadoc artifacts, their 20 detached signatures and 80
MD5/SHA-1 sidecars. ZIP SHA-256:
`a9ebaf4c3d98978115b90f163760f8c258418869661f95431bf9cf6b67f7e85e`.

The [20-artifact manifest](../releases/v0.3.0-M3-checksums.sha256) retains
SHA-256 `e36e8c0c42a1a03defe76280bc00212a2c4352fc52a475fb7cec307a2ee0c65c`.
Release signing fingerprint:
`09710A1F5E321DE8829C3F1902F21A461014CD4B`.

Central reported **PUBLISHED** at 15:08 KST, and a separate authenticated
status request confirmed that state with exactly the five M3 coordinates and
an empty error set. The sbt publication command exited 0.

All **20** public POM/main/sources/Javadoc artifacts and their **20** detached
signatures were downloaded anonymously from `https://repo.maven.apache.org/maven2`.
Every file matches the pre-upload signed bundle byte-for-byte and all detached
signatures authenticate the release fingerprint. The release notes' SHA-256
manifest therefore identifies the actual public artifacts.

## Public Maven consumer

The complete public consumer command exited **0** from the public tag with a
fresh isolated cache and no staging argument. It passed **28** distinct JVM
runtime groups, fast-linked JavaScript and fully optimized JavaScript. The
crypto fixture ran once on JVM and once in each JS output; each JS output
also ran the typed recovery rejection probe exactly once. The six independent
crypto vectors and 55 independent transition vectors passed.

The [three JVM identities](artifact-identities/v0.3.0-M3/v2-jvm-public.sha256)
and [two JS identities](artifact-identities/v0.3.0-M3/v2-js-public.sha256)
record the actual Central-selected JARs. All five cached JAR/POM pairs match
the signed release manifest. Actual compiler selection matches the prepared
[51 JVM/12 JS source ledgers](compiler-inputs/v0.3.0-M3); the crypto/TLS
identities match the archived [runtime ledgers](crypto-identities/m3-2026-09-13).
The current 389-input inventory remains unchanged.

To reproduce from a fresh checkout/cache:

```sh
git clone --depth 1 --branch v0.3.0-M3 https://github.com/sigilaris/sigilaris.git
cd sigilaris/release-conformance
./run-conformance.sh 0.3.0-M3 v2
python3 tools/verify-transition-vectors.py
```

The runner isolates its Coursier/sbt configuration and only permits Central
for the selected Sigilaris coordinates. The public gate verified the
selected JARs, actual compiler inputs, crypto/TLS providers and JVM/JS outputs
against the frozen release evidence. Earlier explicit-staging results remain
separate from this publication gate.

## Site deployment correction

The [first public site run](https://github.com/sigilaris/sigilaris/actions/runs/34811289350)
failed while compiling the node JVM sources with
`java.lang.OutOfMemoryError: Java heap space`. Its actual launch log showed
the sbt runner's default `-Xmx1024m`, followed by sustained GC. This was a
source compilation resource failure during site generation.

Commit `603b503` adds `-J-Xmx4G` to the site command alongside the existing
metaspace setting. Inspection of the exact sbt 2.0.8 distribution runner
confirmed that an explicit heap setting bypasses its default memory reset.
The compiler checks and full `unidoc;tlSite` flow remain enabled. This is a
workflow-only correction after the immutable release tag; all 389 release
inputs and the signed bundle remain unchanged. The
[replacement CI run](https://github.com/sigilaris/sigilaris/actions/runs/34811845715)
passed the full build and GitHub Pages deployment. Its actual JVM launch
includes `-Xmx4G` and `-XX:MaxMetaspaceSize=1G`. The public commit carrying
that correction is `ef5c03d6489afa31a1bf83bf71cef9c803cc41d7`.

## Dependency-alert scope at publication

The first main push displayed an aggregate of 144 open GitHub dependency
alerts. After GitHub refreshed the branch, the read-only Dependabot API
returned **132** open alerts at **14:56 KST** across four npm/Yarn lockfiles,
and the following
push displayed the same totals. These are alert-instance counts, not distinct
advisory counts, and are separate from the dated Yarn/OSV audit.

| Manifest | Critical | High | Moderate | Low |
| --- | ---: | ---: | ---: | ---: |
| `modules/core/js/yarn.lock` | 2 | 36 | 26 | 5 |
| `modules/node-common/js/yarn.lock` | 2 | 29 | 20 | 4 |
| `release-conformance/package-lock.json` | 0 | 0 | 0 | 1 |
| Historical `release-smoke/package-lock.json` | 1 | 0 | 0 | 6 |

The four build-lock Critical instances are webpack `GHSA-hc6q-2mpp-qw7j`
and websocket-driver `GHSA-xv26-6w52-cph6`, each in both source JS builds.
The remaining Critical is the intentionally archived M2 smoke runtime's
elliptic `GHSA-vjh7-7g9h-fjfh`. The M3 standalone runtime retains its previously
documented elliptic Low advisory. These paths remain covered by the
[runtime assessment](m3-crypto-security-2026-09-13.md) and
[build-tool plan](../plans/0035-scala-js-build-dependency-security-plan.md).
This lockfile observation is not a fresh Maven vulnerability audit, a closure
of those findings or a deployment-wide security clearance.

## Review and lessons

Publication review checked the fixed canonical/public tags, the original
signed bundle and actual upload ZIP, Central's state and coordinates, public
artifact/signature bytes, isolated consumer resolution, compiler/provider
identities, complete runtime outputs and current/archived evidence wording.
It corrected README's stable-only release-discovery link for the new milestone
prerelease. **No finding in these publication changes.** The release's known
operational and security backlogs remain open.

Preserve the release tag and exact signed bytes, check the public repository
independently of the canonical source, and verify the runner's actual JVM
arguments when diagnosing a CI memory limit. These lessons and timestamped
alert-scope handling are carried into the remaining build-tool plan.

Local logs are `/tmp/sigilaris-m3-central-publication.log`,
`/tmp/sigilaris-m3-public-artifact-verification.log`,
`/tmp/sigilaris-m3-public-maven-conformance.log`,
`/tmp/sigilaris-m3-public-site-{failure,success}.log` and
`/tmp/sigilaris-m3-publication-final-audit.log`. The public tag, committed
manifests and reproduction command define the publicly repeatable scope.
