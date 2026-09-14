# 0035 - Scala.js Build Dependency Security

## Status

Proposed — build-tool follow-up from the [M3 crypto dependency review](../conformance/m3-crypto-security-2026-09-13.md). This plan does not change the immutable M1/M2/M3 release baselines or claim that the broader dependency-security gate is complete.

## Background

The source Scala.js test builds use sbt-scalajs-bundler 0.21.1 with webpack 5.24.3, webpack-cli 4.5.0 and webpack-dev-server 3.11.2. Auditing the actual lockfile retains Critical findings for webpack and websocket-driver plus lower-severity build dependencies after the signing runtime is updated. The standalone conformance build directly links Scala.js and does not use that webpack/dev-server toolchain. Record these scopes explicitly; neither passing crypto tests nor Yarn's aggregate `devDependencies` count identifies the vulnerable execution paths.

The [dated dependency audit](../conformance/m3-crypto-dependency-audit-2026-09-13.json) already preserves both generated npm configurations, lock hashes and each remaining advisory path. Use it as the initial inventory, then refresh the database results and complete execution-path applicability review; do not repeat inventory discovery from an inherited incomplete list.

## Phases

1. **Inventory and applicability.** Bind both JS projects' actual npm configuration and lockfiles to an audit snapshot. Trace each finding to its top-level runtime, type or development dependency. Review generated bundle exposure separately from a running development server, and document any supported use of the server.
2. **Choose a supported toolchain.** Assess the maintained sbt plugin or replacement workflow and compatible webpack, CLI, source-map and server versions. Determine Node support and configuration/API migration requirements. Do not force incompatible transitive major versions through blanket overrides or disable representative JS tests to remove audit entries.
3. **Implement and verify.** Update both source build lockfiles and relevant instructions, check actually loaded versions and repeat JVM/JS compatibility, normal/optimized linking and required transport tests. Exercise the source/API site build with the actual CI runner and record its effective JVM limits. Preserve old archived fixtures and public artifact identities.
4. **Freeze release evidence.** Audit the final selected dependency graph, close or explicitly assess each finding, run the exported consumer, and record fresh source, package, compiler and artifact identities. Keep historical and current audit results separate.

## Acceptance criteria

- [ ] Critical build-tool findings are remediated or have a specific, reviewed applicability disposition supported by execution-path evidence.
- [ ] All remaining audit results identify the actual dependency path and supported exposure.
- [ ] Both source JS targets and standalone outputs run with the intended versions without dropping tests or silently falling back to old packages.
- [ ] The public build instructions describe any required Node/toolchain migration.
- [ ] Fresh final inventories and executable evidence are retained; old release archives remain reproducible.

## Lessons from M3 crypto remediation

Check consumer resolution as well as top-level declarations. Clear stale source-build resolution after coordinate changes and fail the test/package gate on unexpected providers. Security changes to published transitive dependencies must appear in the POM; a local override alone does not protect downstream consumers. Treat unpatched advisories and bounded applicability assessments explicitly, without claiming a clean whole-project audit.

The [September 14 guard review](../conformance/m3-crypto-review-corrections-2026-09-14.md) extends the lesson to every supported test entrypoint: cover `test`, `testOnly` and `testQuick`, including empty selections. Test selection and incremental skipping must not bypass dependency validation. Describe negative fixture outcomes at the public API boundary unless every backend rejects them at the same stage. Preserve the distinct legacy JVM advisory status when reporting updated JS compatibility.

The [M3 publication record](../conformance/m3-publication-2026-09-14.md) adds two execution lessons. Inspect the runner's actual JVM launch arguments: the public source/API build exhausted its default 1 GiB heap even though local artifact gates passed. Explicit `-J-Xmx4G` preserved the requested heap/metaspace settings and the complete CI build/deployment passed. Keep compiler checks enabled and bind source-build evidence to the tested runner configuration.

Record dependency-alert timestamps, manifest paths and counting conventions. The first push's aggregate refreshed from 144 to 132 open GitHub alert instances; the later API snapshot still identified the known build-tool and archived smoke Critical paths. This is not a new Maven audit or a closure of those advisories. Future remediation must retain the published M3 identities and produce new release coordinates for changed artifacts.
