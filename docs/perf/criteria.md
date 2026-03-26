# Performance Acceptance Criteria (CryptoOps)

- Metrics: ops/s, ns/op (p50, p99), bytes/op, GC time (%), CPU usage
- Targets (minimum):
  - Phase 1: p50 ns/op or bytes/op >= 10% improvement for sign/recover/fromPrivate
  - Phase 3: bytes/op >= 20% reduction; GC time reduced
  - Phase 5: no regression +/-2% after security hardening
- Bench scope: sign, recover, fromPrivate, keccak256
- Environment fixed: JDK version, JVM opts, hardware note, threads=1
- Artifacts: JMH JSON, optional JFR/async-profiler flamegraphs
- Guardrails: integrate in CI, threshold alerts on key benches
