# M3 crypto review corrections (2026-09-14)

Status: complete. Base revision: `24c02ef`. Focused checks completed on 2026-09-14 at 10:15 KST. This review corrects test entrypoint coverage and evidence wording; production crypto/TLS code, dependency versions, lockfiles and the previously recorded candidate JAR/POM identities remain unchanged.

## Review disposition

- **R1 — guard coverage:** `Test / testOnly` and `Test / testQuick` now depend on the same provider/npm verification as `Test / test` in all five source projects. The input tasks retain their parsers through `.evaluated`. Even an empty selection or an incremental run with nothing to retest must validate dependencies. JVM packaging keeps its existing guard.
- **R2 — inconsistent-key semantics:** name and describe the fixture as private scalar zero paired with the public key for one. The assertion requires `CryptoOps.sign` to return a typed `Left`. On JVM, private-scalar validation can fail; on JS, elliptic can sign and the recovered public key fails the `CryptoOps` consistency check. Unsupported hash lengths are checked before either path. The test makes no common-backend-rejection claim.
- **I1 — synthetic PEM provenance:** document the fixture's origin and localhost-only purpose beside the certificate/key. No scanner configuration or allowlist is checked into the repository, so no speculative suppression was added. Any future exception should identify the exact fixture finding or file. The private test tree remains excluded from public export.
- **I2 — legacy JVM provider:** prominently state in the M2 release notes and consumer README that immutable M1/M2 JVM profiles retain `bcprov-jdk15on 1.70`, which matched five advisories in the September 13 snapshot. Compatibility passes and M3's provider update do not remediate those releases or determine each advisory's deployment applicability.

## Evidence scope

The [September 13 execution record](m3-crypto-security-2026-09-13.md) remains archived at reachable commit `24c02ef`, with its [original 389-input inventory](m3-crypto-sources-2026-09-13.sha256) preserved verbatim. Its full source/runtime results and dependency-audit snapshot remain dated evidence. This correction does not repeat that full matrix or refresh vulnerability databases.

This correction is archived at reachable commit `ea47ed8`. The [release preparation gate](m3-release-preparation-2026-09-14.md) records the later final-version build.

The [archived inventory](m3-crypto-review-sources-2026-09-14.sha256) contains **389** discovered public inputs, with SHA-256 `0eb52dfbadd667bead30ebc97d7d188d4b4b5eceff0cdd99a0a22d42ddc9b1a9`. Only `build.sbt` and the public crypto fixture differ from the September 13 input list. New compiler ledgers record [51 JVM inputs](compiler-inputs/m3-crypto-review-2026-09-14/v2-jvm-sources.sha256) and [12 JS inputs](compiler-inputs/m3-crypto-review-2026-09-14/v2-js-sources.sha256) without rewriting previous ledgers. The existing [five candidate JAR/POM pairs](m3-crypto-artifacts-2026-09-13.sha256) remain the artifact baseline.

## Validation

All five projects passed their selected `testOnly` suite and printed the corresponding actual dependency guard result:

| Source project | Selected suite | Passing cases |
| --- | --- | --- |
| core JVM | `CryptoDependencySuite` | 2 |
| core JS | `CryptoDependencySuite` | 2 |
| node-common JVM | `TxPipelineIdentityStrategySuite` | 2 |
| node-common JS | `TxPipelineIdentityStrategySuite` | 2 |
| node JVM | `TlsDependencySuite` | 2, JDK and OpenSSL; no skips |

Each project's subsequent `testQuick` selected zero cases after the successful run and still executed its guard. Ten deliberate failure controls (five projects × two entrypoints) temporarily replaced the corresponding verification task with a uniquely identified failure. Every command exited **1** at that guard, before the empty test selection could complete. These are entrypoint-wiring controls; the underlying dependency validators remain unchanged and were exercised by the positive runs.

Suite filters and supported framework arguments were preserved. JVM `--tests=.*hash.*` ran just the hash-boundary case; JS `--include-tags=CryptoGuardArgumentProbe` filtered out the suite, and a normal JS run immediately afterward passed both cases. Both filtered `testQuick` commands still invoked the guard. The pinned MUnit JS runner does not support the JVM `--tests` option, so JS forwarding was checked with its supported tag filter.

The exported V2 consumer selected the original five staged library JARs, verified the expected JVM/TLS provider set and npm runtime, compiled the current JVM/JS fixtures, and passed fast and optimized JS execution. Each output executed the crypto fixture and typed recovery probe exactly once. This correction reran **JVM compilation and focused source tests**, not the 28 long JVM consumer runtime groups. Those groups retain their September 13 evidence for unchanged production code. M1/M2 artifact and profile behavior were not rerun or modified.

`scalafmtCheckAll` and `scalafmtSbtCheck` passed. The final source/consumer/export audit checked all 389 current input hashes, the two fresh compiler ledgers, the original five JAR/POM pairs, unchanged actual crypto dependency identities and the archived input inventory at reachable `24c02ef`. Both independent references passed (six crypto vectors and 55 transition vectors). Documentation links resolved, whitespace checks passed, and private test resources were absent from the export.

Local logs are `/tmp/sigilaris-m3-crypto-review-focused-20260914.log`, `/tmp/sigilaris-m3-crypto-review-other-targets-20260914.log`, `/tmp/sigilaris-m3-crypto-review-filter-arguments-20260914.log`, `/tmp/sigilaris-m3-crypto-review-guard-controls.log`, `/tmp/sigilaris-m3-crypto-review-consumer-20260914.log` and `/tmp/sigilaris-m3-crypto-review-final-audit.log`.

To repeat a normal guarded run and its incremental follow-up:

```sh
sbt 'coreJVM/testOnly *CryptoDependencySuite' \
  'coreJS/testOnly *CryptoDependencySuite' \
  'coreJVM/testQuick *CryptoDependencySuite' \
  'coreJS/testQuick *CryptoDependencySuite'
```

An example deliberate failure control follows; its expected exit code is 1. The setting affects only that sbt process. Repeat for either entrypoint and the other project IDs, using `verifyJsCrypto` for JS:

```sh
sbt 'set LocalProject("coreJVM") / verifyJvmCrypto := sys.error("EXPECTED_GUARD_FAILURE")' \
  'coreJVM/testOnly __CryptoGuardNoSuchSuite__'
```

## Lessons and self-review

Validate the actual entrypoints developers use, including filtered and incremental runs. Passing `test` cannot establish that `testOnly` or `testQuick` is guarded. Prove that intentional guard failure rejects the command even when there are no matching tests, and preserve test filtering and framework arguments.

Describe negative cases at their asserted API boundary. A typed failure does not prove that two cryptographic backends reject the input at the same stage. Separate updated JS compatibility from historical JVM-provider advisories. These lessons are also recorded in [plan 0035](../plans/0035-scala-js-build-dependency-security-plan.md).

The final review checked all ten new guarded entrypoints, incremental empty-selection behavior, argument forwarding, the fixture's unchanged assertions, legacy-advisory wording, synthetic-key provenance and separation of archived/current evidence. **No finding in these corrections.** The previously recorded unpatched elliptic Low advisory and build-tool/operational backlogs remain open.
