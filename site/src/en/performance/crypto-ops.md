## CryptoOps performance/security guidelines and bench guide

### Invariants
- Endianness/length: big-endian, fixed 32 bytes (scalar) and 64 bytes (x||y)
- Keep Low‑S normalization; use constant-time comparisons for secrets
- Bytes as Source of Truth; JVM-only views are lazy/cached

### Optimization principles (P1–P8)
- Unify math on BigInteger, remove cats/shapeless on hot paths, cache constants/objects, prefer byte paths
- Details: `docs/adr/0001-cryptoops-biginteger-and-bytes-sot.md`, `docs/perf/criteria.md`, `benchmarks/README.md`

### Running JMH
- Example: `sbt "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*"`
- JVM options: default `-Xms2g -Xmx2g` (can be set in @Fork)
- Results: archive to `benchmarks/reports/<timestamp>_<branch>_<sha>_jmh.json`

### Baseline link
- See the latest baseline under `benchmarks/reports/` in the repository.


### Phase 6 — One command local regression guard (Scala)

Run, archive, compare via sbt aliases:

```bash
sbt bench
```

With GC metrics:

```bash
sbt benchGc
```

Recover-only focus:

```bash
sbt benchRecover
sbt benchRecoverGc
```

Notes:
- Defaults: Throughput(ops/s), warmup 5, measurement 10, fork 1, threads 1, JVM `-Xms2g -Xmx2g`.
- Thresholds (env vars): `OPS_DROP_PCT=2`, `BYTES_INCR_PCT=5`, `GC_TIME_INCR_PCT=5`.
- Baseline auto-discovery under `benchmarks/reports/` (fallback to latest if no baseline-named file).

See also `docs/adr/0006-cryptoops-regression-guard-and-ci-bench.md` and `benchmarks/README.md`.


