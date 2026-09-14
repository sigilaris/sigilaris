# Public input review corrections (2026-09-12)

Status: complete. Corrections to `7fe1cff` passed standalone M1/M2/V2 validation on 2026-09-12; the last V2 output completed at 15:43 KST. The supplied [second-pass review](full-review-second-pass-2026-09-12.md) is retained unchanged. Production library sources and the five [candidate JAR identities](full-review-correction-artifacts.sha256) remain unchanged.

This gate is archived at `9a8ebf9`. Later runtime dependency changes and their new evidence belong to the [M3 crypto security gate](m3-crypto-security-2026-09-13.md); the counts and outcomes below describe the archived revision.

## Findings and changes

- **R1:** Replace inheritance of the old 378-path list with independent discovery of public library main sources, standalone shared/platform sources, exporter/tools/fixtures and build configuration. The archived [public input inventory](public-input-review-sources-2026-09-12.sha256) covers **386** files, including both `PlatformConformance.scala` files and `V2VectorExporter.scala`. It also includes the verifier itself, formatting configuration and both source JS lockfiles. Earlier manifests remain immutable historical subsets; their row counts do not establish complete compiler coverage.
- The maintained [inventory verifier](../../release-conformance/tools/verify-source-inventory.py) rejects added, missing, duplicated or changed paths instead of only hashing an inherited list. Every standalone profile runs it before dependency resolution. sbt's `verifySourceIdentities` separately checks every actual `Compile / sources` entry against that inventory and records per-profile/platform compiler ledgers, before compilation/execution. Inputs outside the exported root or outside the inventory fail. Scala CLI/sbt/editor output directories and editor-only `metals.sbt` are excluded from discovery; an actual selected compiler source still cannot bypass the sbt check.
- **R2:** Legacy JS accepts either an exception or typed rejection for the historical probe. The V2 entrypoint invokes recovery directly and requires `Left`, so an exception fails the execution. Valid recovery remains a prerequisite.
- **R3:** The shared V2 main calls the platform's V2 check once. The JS completion hook only reports completion; it no longer repeats the probe.
- **R4:** Include `Idle` in the maintenance decision list, align the plan index with `Complete`, and label historical output text as archived P1 evidence rather than current complete stdout.

## Validation

The public input inventory has SHA-256 `045e959f64965b29b2a49292a63ddd0afe19bf2c0104bd086be22c2230d50d7a`. All **386** discovered paths and bytes match the source tree and tested public export. The following ledgers are copied from sbt's actual compiler-source checks, rather than derived by filtering the inventory:

| Profile | JVM compiler sources | JS compiler sources |
| --- | --- | --- |
| Public M1 | [4 inputs](compiler-inputs/legacy-m1-jvm-sources.sha256) | [3 inputs](compiler-inputs/legacy-m1-js-sources.sha256) |
| Public M2 | [4 inputs](compiler-inputs/legacy-m2-jvm-sources.sha256) | [3 inputs](compiler-inputs/legacy-m2-js-sources.sha256) |
| Staged V2 | [50 inputs](compiler-inputs/v2-jvm-sources.sha256) | [11 inputs](compiler-inputs/v2-js-sources.sha256) |

The six ledgers contain **75** rows across **52** distinct sources. Their bytes match the actual run output, and every row matches the new inventory. The exporter is retained in the broader inventory as maintenance tooling, separate from those compiler-selected sources.

Public M1 and M2 passed their JVM consumer/recovery probe and both JS outputs. Each JS output reports historical recovery-parameter rejection once. V2 passed its JVM recovery probe, all **28** distinct JVM groups and both fast/optimized JS outputs. Its typed recovery-parameter rejection appears exactly once per JS output, with no legacy exception-tolerant probe call. Each complete profile command exited 0. Local run logs are retained as `/tmp/sigilaris-0033-input-review-{M1,M2,V2}.log`.

Deliberate failure controls passed in isolated exports:

- The Python verifier rejected eight invalid inputs: removing the inventory row or changing bytes for each of the three previously omitted Scala files, adding a new JS source, and duplicating a manifest row. The intact baseline and restored inputs both passed.
- The sbt task independently rejected an omitted JS platform-source row and changed source bytes, each with exit 1 and its specific missing-input or checksum diagnostic.
- A separate JS harness against the pinned public M2 JARs first passed the legacy probe, then invoked the V2 probe. It failed with exit 1 at `Error: The recovery param is more than two bits`; an exception can no longer count as a V2 pass. The harness only supplies a temporary entrypoint and does not replace the crypto implementation.

Production library sources, root build configuration and all five selected V2 JARs are unchanged from `7fe1cff`. The previous source tests remain dated evidence for that library code; this pass does not claim a new full source-matrix run. `scalafmtCheckAll` passed for this correction.

The final public export matches all **386** input identities, the **six** recorded compiler ledgers, **five** selected V2 JAR identities and **ten** pinned public M1/M2 JAR identities. Five historical source inventories verify at their reachable commits with original bytes preserved. The independent oracle passes **55** transition vectors, and **339** local documentation links resolve, including **three** heading anchors. No private `src/test` directory survives export; `git diff --check HEAD` passed. The source/consumer/export audit is retained locally as `/tmp/sigilaris-0033-input-review-audit.log`.

## Self-review

The first review of independent discovery identified local Scala CLI build outputs as generated inputs and excluded those directories before freezing the final inventory. The sbt check still rejects any compiler-selected source that is missing from the inventory. The second review checked path completeness against actual compiler selection, preserved historical manifests, profile-specific exception behavior, single probe dispatch and the documentation changes. **No finding in the R1–R4 corrections**; the operational backlog below remains open.

## Reproduction

Check out `9a8ebf9` into a separate worktree before running these archived commands from its repository or public export root:

```sh
python3 release-conformance/tools/verify-source-inventory.py
shasum -a 256 -c docs/conformance/public-conformance-inputs.sha256
cd release-conformance
./run-conformance.sh 0.3.0-M1 legacy-m1
./run-conformance.sh 0.3.0-M2 legacy-m2
./run-conformance.sh 0.3.0-M3-SNAPSHOT v2 file:/tmp/sigilaris-0033-second-review-final-maven
```

The file repository is the previously verified local candidate repository, not a public M3 release. To create a new input inventory intentionally, run `python3 release-conformance/tools/verify-source-inventory.py --write` from the root after reviewing the changes; the execution script never regenerates it. `target/identities/*-sources.sha256` records the actual compiler-selected inputs for each run. The exporter source is inventoried maintenance tooling and is not a regular standalone compiler source.

## Lessons and remaining work

An unchanged list of passing hashes cannot establish that the list covers the compiler's inputs. Verify both path-set completeness and bytes, then compare with the build tool's actual selected sources. Historical output and historical exception behavior need explicit profile boundaries. Avoid repeating a probe merely to share platform code.

The operational notes about maintenance identity exhaustion and keyless signer-roster intervals are retained in [plan 0034](../plans/0034-v2-recovery-and-lifetime-cost-hardening-plan.md). M3/M4 and L1/L3/L5/L10 remain open; this pass changes conformance evidence and documentation.
