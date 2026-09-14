# Plan 0033 independent review, third pass (2026-09-12)

Status: review only, no tracked file changed. Revision reviewed: `9a8ebf9`, range `7fe1cff..9a8ebf9` (23 files; no
`modules/` change). This pass checks the R1 to R4 corrections recorded in
[public-input-review-corrections-2026-09-12.md](public-input-review-corrections-2026-09-12.md).

## Result

**No finding** in the R1 to R4 corrections. Two cosmetic notes are listed at the end.

## Verified

- **R1 inventory.** `verify-source-inventory.py` passes with 386 entries; `shasum -c` reports 386 OK; the inventory
  self-hash is `045e959f…d50d7a` as recorded. The three previously omitted files (JS and JVM `PlatformConformance.scala`,
  `V2VectorExporter.scala`) are present. All six compiler ledgers (75 rows, 52 distinct paths) match the inventory by
  path and hash. Discovery is independent of the previous list: it walks the library main trees, standalone consumer
  trees, tools, fixtures and configuration, and excludes only generated directories and `metals.sbt`.
- **R1 verifier controls, rerun in an isolated export copy.** A byte change, an added source, a deleted source, a
  duplicated manifest row and a hidden stray file each fail with exit 1 and a specific diagnostic; a file under a
  generated `target` directory is ignored; the intact export passes.
- **R1 sbt task.** `jvm/verifySourceIdentities` and `js/verifySourceIdentities` for the V2 staging profile report
  50 and 11 compiler sources, and the written ledgers are byte-identical to the recorded ones. A stray source added to a
  compiled directory fails with "missing from public input inventory"; a one-byte change to a compiled source fails with
  "checksum mismatch". The task resolves canonical paths, requires every source under the exported root, rejects
  duplicate inventory rows, and runs in the script after artifact verification and before any compile or run, so an
  sbt failure aborts the remaining batch.
- **R2.** The Scala.js `runV2` asserts a typed `Left` for recovery values 283 and 26 without a `Try`; `run` keeps the
  exception-tolerant legacy contract. `V2ConformanceMain` calls `runV2` on both platforms; the JVM `runV2` delegates to
  the JVM probe, consistent with the preserved JVM interpretation and the separately executed alias probe.
- **R3.** The JS `V2PlatformConformance.run` only prints; the probe is dispatched once per execution.
- **R4.** `Idle` is listed in the contract; the plan index says `Complete`; the README labels the P1 output text as
  archived evidence; the activation guide states the keyless-interval limitation and plan 0034 carries both
  operational questions.
- **Records.** The earlier 378-entry manifest still reproduces at `7fe1cff` from `git show` bytes and its record now
  states the coverage limitation. Relative links in the changed documents resolve; `git diff --check` is clean; the
  working tree is clean after the controls above.

## Cosmetic notes

- The Python verifier's path-mismatch message names sets from the manifest's perspective: an added file on disk is
  reported under `missing` and a deleted file under `unexpected`. Operators reading it from the disk perspective may
  invert the meaning. `release-conformance/tools/verify-source-inventory.py:71-74`.
- The two platform `PlatformConformance.scala` files are not part of the root build's scalafmt scope. They are
  formatted, but nothing enforces it.
