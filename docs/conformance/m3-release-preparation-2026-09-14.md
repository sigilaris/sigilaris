# M3 release preparation (2026-09-14)

Status: complete for local release preparation; publication pending. No release tag, remote push,
Central upload or public artifact verification has been performed.

The reviewed starting revision is `ea47ed8`. Its
[389-input inventory](m3-crypto-review-sources-2026-09-14.sha256) is preserved
verbatim, and the [guard review](m3-crypto-review-corrections-2026-09-14.md)
remains dated evidence at that reachable commit. Release preparation changes
the root and standalone consumer default to `0.3.0-M3`; production sources,
dependency versions and lockfiles remain unchanged.

The [current input inventory](public-conformance-inputs.sha256) freezes **389**
final-version inputs, SHA-256
`ea1aeb9996489a5e276b9c5e1b231b9365572f7ad9c92ead161856778c3c750b`.
Only `build.sbt` and `release-conformance/build.sbt` differ from the archived
input set, each solely in the default artifact version. The
[release notes](../releases/v0.3.0-M3-release-notes.md) describe integration,
migration and the known limitations.

## Source and signed artifact gate

The September 13 full source matrix remains dated evidence: **2,348** distinct
passing cases (554 core JVM, 553 core JS, 134 node-common JVM, 133 node-common
JS, 972 node JVM plus the two subsequent TLS cases). This preparation does not
claim a new execution of that full private suite. The September 14 guard
corrections additionally checked selective/incremental entrypoints and failure
controls, as recorded in the archived review.

The final-version build passed `scalafmtCheckAll`, `scalafmtSbtCheck` and
`nodeJvm/Test/compile`. Fresh focused runs passed **10** cases: both core
`CryptoDependencySuite` targets, both node-common `TxPipelineIdentityStrategySuite`
targets and node JVM `TlsDependencySuite`, two each. All five actual crypto
dependency guards executed. Both JDK and OpenSSL TLS cases ran without skips,
accepting the trusted localhost name and rejecting `127.0.0.1`.

After changing the version, `coreJVM/publishTo` was inspected and confirmed to
be the filesystem `sonatype-local-bundle` repository. The five scoped
`publishSigned` tasks then wrote only the local bundle at:

```text
target/sonatype-staging/0.3.0-M3
```

All **20** artifacts (five POM/main/sources/Javadoc sets) and **20** detached
signatures verify with fingerprint
`09710A1F5E321DE8829C3F1902F21A461014CD4B`. All **80** MD5/SHA-1 sidecars
match. All five POMs pin internal Sigilaris dependencies to final `0.3.0-M3`
and contain no snapshot or local repository reference. The provider and all
13 direct Netty/native entries have the expected versions; both JS JARs
declare the expected npm runtime versions. The **440** packaged Scala source
entries exactly match the selected shared/platform main source trees, and
all five Javadoc JARs contain their HTML index.

The [20-artifact SHA-256 manifest](../releases/v0.3.0-M3-checksums.sha256)
is relative to that Maven bundle root. Its own SHA-256 is
`e36e8c0c42a1a03defe76280bc00212a2c4352fc52a475fb7cec307a2ee0c65c`.
These final-version bytes supersede the older SNAPSHOT candidate for this
release; the dated SNAPSHOT manifests remain unchanged.

## Exported consumer gate

The reviewed index was exported with `scripts/export-public.sh` to a fresh
directory. The standalone consumer cannot see the parent source project,
Maven Local or Ivy Local and has an isolated Coursier/sbt configuration.
V2 explicitly selects the signed bundle as its Sigilaris Maven origin;
historical profiles select only public Central for immutable M1/M2 artifacts.

M1 and M2 each passed JVM execution, fast-linked JS and fully optimized JS,
including their historical recovery probes. Their ten public JAR identities
match the immutable fixture manifests. These runs use the updated npm runtime
but preserve each legacy JVM provider, as the release notes explain.

[Six fresh compiler ledgers](compiler-inputs/v0.3.0-M3) record the actual
selected sources: 4 JVM/3 JS for each historical profile and 51 JVM/12 JS for
V2, **77 rows / 53 distinct inputs** in total. All selected source hashes match
the current inventory. The three selected JVM crypto/TLS and three npm runtime
identity sets are byte-identical to the archived
[September 13 ledgers](crypto-identities/m3-2026-09-13).

V2 passed all **28** distinct JVM runtime groups, including actual four-node
bootstrap and handover, publication fault recovery and the 100,000-identity
durable reservation. JVM execution completed in 885 seconds. Fast-linked and
fully optimized JS both passed; each output ran the typed recovery probe once.
The fixed crypto fixture ran three times across JVM and the two JS outputs.
All three complete profile commands exited 0; the last V2 linker/Node output
finished on September 14 at 11:51 KST.

Both independent standard-library references already pass: **six** fixed
RFC6979/secp256k1 vectors and **55** transition byte/preimage/hash vectors.
The final audit passed across the working tree, both executed consumer exports
and the final documentation export: all 389 discovered inputs, six compiler
ledgers, selected artifacts, unchanged crypto identities and archived provenance
match. It also required every V2 JVM group, all three crypto outputs, exactly
two typed JS recovery probes, all legacy outputs and the 10 focused source cases.
Private test trees and `docs/dev` are absent from public export.

## Reproduction

From a fresh, separate checkout of this prepared source revision, the local
signing command is:

```sh
sbt 'set Global / pgpSigningKey := Some("09710A1F5E321DE8829C3F1902F21A461014CD4B")' \
  scalafmtCheckAll scalafmtSbtCheck nodeJvm/Test/compile \
  coreJVM/publishSigned coreJS/publishSigned \
  nodeCommonJVM/publishSigned nodeCommonJS/publishSigned nodeJvm/publishSigned
```

This requires the corresponding local GPG secret key. Inspect `publishTo`
first: a SNAPSHOT build uses a remote repository. Reproduction should use an
empty separate staging location; preserve the verified release bundle and
do not republish over it. A rebuild need not reproduce the archived JAR hash.

Export the committed source with:

```sh
scripts/export-public.sh HEAD /tmp/sigilaris-public-export.m3-verification
```

From that export's `release-conformance` directory, run:

```sh
./run-conformance.sh 0.3.0-M1 legacy-m1
./run-conformance.sh 0.3.0-M2 legacy-m2
./run-conformance.sh 0.3.0-M3 v2 file:/absolute/path/to/signed-maven-bundle
python3 tools/verify-transition-vectors.py
```

## Publication sequence — not executed

After local preparation and its `--no-ff` merge, the release target is the
merge commit on local `main`. Its tree must equal the prepared release branch
tree. A tree object used for pre-commit export is only a local audit input;
the committed source and eventual release tag establish reachable provenance.

The read-only preflight found both local and remote `main` at
`19011fd6426ceabcd1de45b11cdfc10efea23259`, no M3 tag on either remote and
HTTP 404 for all five final M3 POMs on Central. The signing key was usable;
Central upload/authentication has not been exercised by this local task.

The next release execution must preserve the signed bundle above:

1. Recheck remote `main`, absence of `v0.3.0-M3` on both remotes and absence
   of all five Central coordinates. Confirm the prepared source tree and
   all signed artifact hashes before proceeding; investigate any drift.
2. Create the annotated `v0.3.0-M3` tag on the verified local merge commit.
   Push **only that tag** to `origin` and wait for its public export to finish.
   Verify the public tag and exported tree before pushing `main` to `origin`.
   The current exporter cancels concurrent jobs and exits before tag creation
   when the public snapshot is unchanged, so simultaneous tag/main pushes or
   pushing main first can lose the public tag.
3. Run `sbt sonatypeBundleRelease` to upload/release the already verified local
   bundle, without rebuilding it. Record the Central deployment identity and
   successful publication state.
4. Download the public POMs/JARs/classifiers and signatures and compare them
   with the signed bundle and SHA-256 manifest. Rerun the V2 consumer from the
   public export with a fresh cache using only `./run-conformance.sh 0.3.0-M3 v2`.
   Update publication status and record public evidence only after those gates
   pass; preserve the release tag and artifact inputs.

These are future release actions. This task stops before tag creation, remote
writes, Central upload and public-Maven verification.

## Review and lessons

The first preparation review corrected the remaining M2 coordinates at the
bottom of README, removed a README link to a private-only exported-out document,
identified five historical M1 links to excluded private documentation,
replaced those links with an explicit archive-scope note and public historical
fixture references, replaced stale future-feature claims and clarified that maintenance identity
capacity is configurable and belongs to a provider instance. Current and
archived input inventories are separate, and a local staging pass is never
described as publication.

The final review checked final-version dependency metadata, signature and
source-JAR completeness, actual staged artifact selection, archived/current
provenance, honest test scope, public document availability and the tag-first
publication sequence after these corrections. **No finding in this release
preparation.** Formatting, whitespace and the relative links in the selected
54 release/conformance/plan documents pass. The known runtime, build-tool and
operational backlogs in the release notes remain open.

Local integration uses a `--no-ff` merge into `main`, with the merge's complete
tree checked against the prepared release branch after the record is committed.
The merge commit identity is reported with the task result; it is not embedded
in its own source tree. No artifact rebuild is needed after an identical-tree
merge.

Local evidence logs are `/tmp/sigilaris-m3-release-signed-build.log`,
`/tmp/sigilaris-m3-release-focused-tests.log`,
`/tmp/sigilaris-m3-release-{legacy-m1,legacy-m2,v2}.log`,
`/tmp/sigilaris-m3-release-artifact-audit.log` and
`/tmp/sigilaris-m3-release-final-audit.log`. The committed input, compiler and
artifact manifests define the reproducible scope; local logs do not constitute
public Maven publication evidence.
