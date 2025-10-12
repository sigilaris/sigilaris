# Benchmarks

## Run

```bash
sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*"
```

- JVM opts are set in the benchmark class via `@Fork`.

## Archive

- Save results as JSON under `benchmarks/reports/<timestamp>_<branch>_<sha>_jmh.json`.
- Large artifacts (JFR, flamegraphs) should be uploaded as CI artifacts or stored via Git LFS if needed.

## Notes

- Ensure `BouncyCastleProvider` is installed in `@Setup` before generating keys.
- Keep inputs deterministic per trial to reduce noise; avoid re-seeding per invocation.


## Phase 6: Local regression guard (run, archive, compare)

This section standardizes how to run JMH locally, archive results, and compare against a saved baseline to guard against performance regressions, following ADR-0006.

### Fixed parameters

- JVM: `-Xms2g -Xmx2g` (configured via `@Fork` in the benchmark class)
- Mode: Throughput (ops/s)
- Warmup: 5 iterations
- Measurement: 10 iterations
- Forks: 1
- Threads: 1

### Commands

Run and write the JMH JSON to a stable path for archiving:

```bash
sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"
```

Optionally include GC profiler metrics (bytes/op, gc time):

```bash
sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json"
```

### Archiving

After the run, archive the JSON into `benchmarks/reports/` using the naming convention used in this repo:

```bash
UTC_TS=$(date -u +"%Y-%m-%dT%H-%M-%SZ")
BRANCH=$(git rev-parse --abbrev-ref HEAD)
SHA=$(git rev-parse --short HEAD)
SUFFIX=_jmh.json
cp target/jmh-result.json benchmarks/reports/${UTC_TS}_${BRANCH}_${SHA}${SUFFIX}
# If GC profiler was used, store a parallel file with -gc suffix for clarity
# cp target/jmh-result.json benchmarks/reports/${UTC_TS}_${BRANCH}_${SHA}_jmh-gc.json
```

Examples already present in this repo:
- `2025-10-12T14-42-40Z_feature-crypto-operations_7d539ce_jmh-gc.json`
- `2025-10-12T14-32-23Z_feature-crypto-operations_7d539ce_jmh.json`

### Baseline management

- Choose a stable baseline JSON under `benchmarks/reports/` (e.g., `baseline_main_YYYYMMDD.json`) or pin one of the stable historical results.
- Compare your new run against the baseline using the following thresholds:
  - Throughput (ops/s): warn on a drop worse than −2% versus baseline
  - Optional GC mode: bytes/op (`alloc.rate.norm`) and total `gc.time`: warn on increases > +5%

### Manual comparison guidance

Open both the baseline and the new result JSONs and compare for each benchmark:
- Primary metric: `score` (ops/s)
- If GC profiled: look for `alloc.rate.norm` (bytes/op) and cumulative `gc.time`

Keep the comparison approach consistent (median vs. mean), and prefer median if you re-run multiple times.

### Tips for reproducibility

- Close background-intensive apps; keep CPU frequency stable.
- Run 2–3 times and use the median to minimize noise.
- Avoid changing JVM/toolchain between baseline and new runs.

For more context and acceptance criteria, see `docs/adr/0006-cryptoops-regression-guard-and-ci-bench.md`.

### One-command quick start (Scala/sbt)

Use sbt aliases (no shell/python required):

```bash
sbt bench
```

GC metrics included:

```bash
sbt benchGc
```

Recover-only:

```bash
sbt benchRecover
sbt benchRecoverGc
```


