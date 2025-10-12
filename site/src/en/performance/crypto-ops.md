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


