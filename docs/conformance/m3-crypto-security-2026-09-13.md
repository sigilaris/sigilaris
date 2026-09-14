# M3 crypto dependency security (2026-09-13)

Status: complete for the runtime remediation and bounded advisory assessment below. Final source tests, standalone consumers and TLS regression checks passed on 2026-09-13; the last TLS check completed at 02:55 KST. Base revision: `7b60f01`. This is development work for `0.3.0-M3-SNAPSHOT` and does not publish a release or activate a deployment. The unpatched Low advisory and build-tool backlog remain explicitly open.

This execution record is archived at `24c02ef`. The [September 14 review corrections](m3-crypto-review-corrections-2026-09-14.md) document broader test-entrypoint guards and clarify fixture/legacy-provider scope. Counts, artifact hashes and full-run results below belong to the September 13 revision.

## Changes and scope

| Dependency | Previous selection | M3 selection | Action |
| --- | --- | --- | --- |
| JS ECDSA | `elliptic 6.5.4` | `6.6.1` | Apply the malformed-input signing fix and earlier signature validation fixes. |
| JS big integers | core `bn.js 4.12.2`, common `4.12.3` | `4.12.5` | Update the runtime dependency and both source lockfiles; declare the supported version explicitly. |
| JVM crypto provider | `bcprov-jdk15on 1.70` | `bcprov-jdk18on 1.86` | Move to the maintained artifact family and verify that only one provider generation is selected. |
| HTTP/TLS integration | Armeria `1.33.2` selected | `1.41.1` | Refresh the integration runtime and its supported native TLS dependency family. |
| TLS/network handlers | Netty `4.2.4.Final` | `4.2.18.Final` | Pin Armeria's Netty entry modules in published dependencies so hostname and fragmented ClientHello fixes reach consumers. |
| Native TLS | `netty-tcnative 2.0.72.Final` | `2.0.84.Final` | Align with the stable native version used by the selected Netty release. |
| JS Keccak | `js-sha3 0.8.0` | `0.8.0` | Retain the existing hash implementation; current npm/OSV queries report no matching advisory. |

Both implementations now reject a signing hash whose length is not exactly 32 bytes before backend key use. Backend signing errors return a typed `Left`; no exception text or key material is included in the failure. Existing valid signing bytes, Low-S normalization, High-S recovery and historical JVM/JS recovery-parameter interpretations are retained. The new length check applies to new signing; historical recovery is unchanged.

The node-common JS project explicitly takes its npm dependency versions from core. Inspection found that changing core's declaration alone left the node-common test runtime on `elliptic 6.5.4`. The [runtime verifier](../../release-conformance/tools/verify-crypto-runtime.cjs) checks the actually resolved elliptic, the `bn.js` resolved from inside elliptic, and js-sha3, and records their loaded file identities. Standalone JVM checks likewise reject an old or duplicate provider in the V2 profile.

The existing source build also retained `bcprov-jdk15on 1.70` in the node-common/node JVM classpaths after core had selected 1.86. That partial test run was stopped and is not counted as final security evidence. Cleaning the affected build outputs refreshed the transitive resolution. Maintained source-build guards now check JVM provider/TLS versions for `Test / test` and packaging, and actual JS crypto resolution for `Test / test`, so stale caches cannot produce a passing security gate.

## Advisory assessment

The [Critical elliptic advisory](https://github.com/advisories/GHSA-vjh7-7g9h-fjfh) is fixed in 6.6.1. A direct backend probe with synthetic key 1 confirms rejection of a negative signing message. The [bn.js advisory](https://github.com/advisories/GHSA-378v-28hj-76wf) affects the prior core lock's 4.12.2; the selected 4.12.5 is outside that range. The [Maven Central provider metadata](https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.pom) identifies the new provider coordinate and version. Version-specific OSV queries matched five advisories for the old provider and none for the new one; this is a dated database result, not a claim that all provider code has been audited.

The TLS review matched five `netty-handler` advisories at 4.2.4, including [hostname verification bypass](https://github.com/advisories/GHSA-c653-97m9-rcg9) and [fragmented ClientHello routing bypass](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5). Armeria 1.41.1 still declares Netty 4.2.16, so upgrading Armeria alone does not cover the latter fixes. The Netty entry modules and native TLS versions are direct dependencies in the generated node POM, with a common selected version checked by both source and standalone gates. Source-only `dependencyOverrides` would not establish the published consumer's protection. Queries for the selected Armeria, Netty handler and native TLS versions report no matching advisories.

**One unpatched Low npm advisory remains:** [GHSA-848j-6mx2-7j84](https://github.com/advisories/GHSA-848j-6mx2-7j84) covers elliptic through 6.6.1. The upstream [reported truncation defect](https://github.com/indutny/elliptic/issues/321) and installed 6.6.1 source were reviewed. For the supported `CryptoOps` path:

- secp256k1 has a 256-bit order and generates a 32-byte nonce candidate. Its integer byte length is at most 32, so the nonce truncation shift `8 * byteLength - 256` is never positive, including a leading-zero nonce.
- The new signing boundary accepts exactly 32 bytes, converted to `Uint8Array`, so message truncation also has a zero shift. The existing reduction for hashes at or above the order remains covered by fixed vectors.
- `CryptoOps` does not accept a caller-supplied curve, nonce callback or message-bit-length override.

This supports a bounded non-applicability assessment for that truncation defect in `CryptoOps`; it does not patch elliptic or clear arbitrary direct facade use. Callers using `facade.EC.byName`, other curves, custom backend options or unsupported hash widths remain outside this assessment. The advisory remains visible in npm audit, and changes to these assumptions require a new assessment or provider migration. No audit suppression or blanket zero-vulnerability claim is introduced.

## Compatibility and release evidence

The [public crypto fixture](../../release-conformance/shared/v2/scala/org/sigilaris/conformance/CryptoDependencyConformance.scala) includes six literal deterministic signatures for zero, order boundaries, the maximum 256-bit hash and a leading-zero nonce. It checks repeated signing, both S forms, unsupported hash lengths, a typed `Left` for an inconsistent key pair and non-invertible recovery components. That pair combines private scalar zero with the public key for one: JVM private-key validation and JS recovered-public-key mismatch may produce the failure at different stages. The [independent reference](../../release-conformance/tools/verify-crypto-vectors.py) recomputes those literals using Python standard-library HMAC-SHA256 and affine secp256k1 arithmetic. It is test tooling and is never used by the runtime signer.

Current standalone M1/M2 checks replay their pinned public JARs with the updated JS runtime as downstream compatibility checks. Their original release artifacts, golden bytes and archived manifests remain unchanged. The separate `release-smoke` directory retains the historical M2 runtime baseline, including its known dependency findings; it is not the M3 runtime configuration. The [September 12 input review](public-input-review-corrections-2026-09-12.md) remains archived at `9a8ebf9`, with its original source list preserved separately.

The [archived input inventory](m3-crypto-sources-2026-09-13.sha256) contains **389** independently discovered public inputs, with SHA-256 `ba8eb37fb744cda5275d1a452a7adc20b6caa61eabc59cee5a97be6b440abc28`. [New compiler ledgers](compiler-inputs/m3-crypto-2026-09-13) record actual source selection: M1 and M2 each use 4 JVM/3 JS sources, and V2 uses 51 JVM/12 JS sources. Previous ledgers remain archived unchanged.

The [artifact manifest](m3-crypto-artifacts-2026-09-13.sha256) records five candidate JARs **and five POMs**, relative to the Maven repository root. Both Scala.js JARs contain the expected `NPM_DEPENDENCIES` entries for compile and test scopes. The node JVM POM declares Armeria plus all 13 Netty/native entry dependencies at the intended versions. [Eight crypto identity ledgers](crypto-identities/m3-2026-09-13) preserve three selected JVM runtime sets, three standalone JS runtime sets and both source JS runtime sets. JS identities include the `bn.js` resolved from inside elliptic, not just a top-level package with that name.

The candidate repository is `/tmp/sigilaris-m3-crypto-20260913-maven`; it is a local staging result. The manifest records its identities, not a downloadable repository. Rebuild under an explicit Maven staging location to reproduce the gate; a later snapshot build may have different JAR hashes. Subsequent public-Maven verification remains a separate publication gate.

Final source results were **554 core JVM, 553 core JS, 134 node-common JVM, 133 node-common JS and 972 node JVM**, all passing. A subsequent focused run compiled and passed **two additional TLS tests**, with no skips: both JDK and OpenSSL clients accepted the trusted `localhost` certificate and rejected the same server through `127.0.0.1`. These use a basic `X509TrustManager` delegating certificate trust checks, so the hostname check exercises the affected Netty wrapper path. The certificate and key are synthetic private-test fixtures. The source total is **2,348** distinct passing cases, including those two added tests. `scalafmtCheckAll`, `scalafmtSbtCheck` and a separate formatting check of the standalone build all passed.

The three complete standalone profile commands exited 0. M1/M2 each passed JVM and both JS outputs; their historical recovery probe appears once per JS output. V2 passed all **28** distinct JVM runtime groups, JVM historical recovery and both JS outputs. The new six-vector crypto fixture ran three times (JVM, fast JS, optimized JS), and each JS output required typed recovery rejection exactly once. The six compiler ledgers contain **77 rows / 53 distinct sources**. After the independent reference's curve-order check was strengthened, final inventory/reference checks were repeated in source, tested consumer and final export, and the V2 compiler/provider checks were repeated; all compiler-selected sources and candidate artifacts remained identical.

Final export review verified all 389 input hashes, six compiler ledgers, eight crypto identity ledgers, five candidate JARs/POMs and ten immutable public M1/M2 JARs. Six archived source inventories still verify at reachable revisions, including the original 386-input gate at `9a8ebf9`. The 55-vector independent transition oracle passed, documentation links resolved, and no private `src/test` tree survived export. Local logs are `/tmp/sigilaris-m3-crypto-final-source-tests.log`, `/tmp/sigilaris-m3-crypto-tls-regression.log`, `/tmp/sigilaris-m3-crypto-{legacy-m1,legacy-m2,v2}.log`, `/tmp/sigilaris-m3-crypto-final-consumer-recheck.log` and `/tmp/sigilaris-m3-crypto-final-audit.log`. They are local run evidence; the committed inputs, ledgers and artifact manifest define the reproducible scope.

## Reproduction

To reproduce this archived gate, first check out `24c02ef` in a separate worktree. The recorded environment uses sbt 1.11.5, Scala 3.7.3, Temurin 23.0.1, Node 26.7.0, npm 11.19.0 and Yarn 1.22.22. From the source checkout:

```sh
sbt scalafmtCheckAll scalafmtSbtCheck \
  coreJVM/test coreJS/test nodeCommonJVM/test nodeCommonJS/test nodeJvm/test
python3 release-conformance/tools/verify-source-inventory.py
python3 release-conformance/tools/verify-crypto-vectors.py
python3 release-conformance/tools/verify-transition-vectors.py
```

After publishing the five library projects to an explicit file/HTTPS Maven staging repository, export the committed source using `scripts/export-public.sh HEAD /tmp/sigilaris-public-export.m3-crypto`. From the exported `release-conformance` directory:

```sh
./run-conformance.sh 0.3.0-M1 legacy-m1
./run-conformance.sh 0.3.0-M2 legacy-m2
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/absolute/path/to/m3-maven
npm audit --json
```

The audit command is expected to return nonzero for the remaining Low advisory; it is not suppressed. Deliberate isolated controls reject stale elliptic metadata, unexpected nested `bn.js` metadata despite a correct top-level version, a mixed JVM provider classpath, an old TLS handler identity, a changed signature literal and a changed Scala curve-order constant. The JVM controls inject inert entries bearing old JAR filenames into the selected classpath and require the identity task's specific rejection. The intact runtime and independent reference pass first.

## Build tooling and lessons

The [dated audit snapshot](m3-crypto-dependency-audit-2026-09-13.json) retains before/after npm results, version-specific OSV queries, all **26** selected TLS/transport Maven modules, both actual source JS configurations and lock hashes, and each remaining build/type finding's dependency paths. The standalone runtime audit retains one Low and no Moderate/High/Critical findings. Yarn's broader source-build results remain **7 Low / 40 Moderate / 46 High / 3 Critical** for core and **4 / 33 / 38 / 3** for node-common; these are Yarn's reported counts, not distinct advisory counts. The signing/hash paths retain only the same elliptic Low. The build/type paths contain 64 distinct advisory IDs in core and 51 in node-common, including Critical webpack and websocket-driver findings.

The webpack/dev-server dependency tree is audited separately from the production signing/hash and TLS dependencies. Its existing findings are not resolved by these runtime updates. Remediation and execution-path applicability review are tracked in [plan 0035](../plans/0035-scala-js-build-dependency-security-plan.md). Yarn's aggregate `devDependencies: 0` does not override the recorded top-level build configuration or establish production reachability.

Verify the actual dependency selected by every consumer, including nested transitive resolution; a changed top-level version and passing signature tests alone do not prove remediation. Audit the final classpath, since a formatted dependency tree can retain an older version along a repeated/cyclic path. Preserve historical evidence at reachable revisions, freeze a new input list after security changes, and distinguish a patched advisory from a bounded applicability analysis.

Self-review corrected stale downstream provider/npm selections, incomplete transitive TLS remediation, an overbroad Armeria filename filter that also matched the STTP adapter, the independent reference's unchecked Scala curve-order constant, and the audit's extra old-version tree entry. The added TLS fixture was corrected for the current Armeria key-pair API and then passed on both providers. The final review checked signing/error compatibility, actual dependency selection, published POM/npm metadata, reference rejection controls, identity completeness and historical evidence preservation: **No finding in these changes**. This finding result does not close the remaining Low advisory, plan 0035's build-tool work, or plans 0032/0034's operational backlog.
