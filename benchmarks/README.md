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


