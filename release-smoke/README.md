# M2 Public Artifact Baseline

This standalone build resolves the five immutable `org.sigilaris:0.3.0-M2`
coordinates from Maven Central. It has no source dependency on the parent
repository, application packages, or private test fixtures. The fixture source,
checksums, repository configuration, and runner all live in this directory.

Run from this directory:

```sh
./public-m2-baseline.sh
```

The runner overrides all sbt repositories with public Central, uses a dedicated
Coursier cache and empty global sbt configuration, and cleans both consumer
outputs. Maven Local, Ivy Local, parent sources, and global plugins are not
resolution inputs. It verifies all ten downloaded POM/JAR SHA-256 values against
[the immutable M2 manifest](fixtures/m2-checksums.sha256), then verifies the
actual JVM and Scala.js compiler classpaths against the same JAR identities.
It runs the JVM consumer, Scala.js fast-linked consumer, and fully optimized
Node.js consumer. Dependencies can be cached between runs; remove
`target/public-m2-baseline` to repeat with a fresh cache.

The maintained fixtures are:

- [M2SharedBaseline](shared/src/main/scala/M2SharedBaseline.scala): mixed mutable
  read/write descriptor derivation, historical bytes and commitments, empty
  plan/wave rejection, and the historical compatibility-singleton encoding.
- [M2JvmBaseline](jvm/src/main/scala/M2JvmBaseline.scala): conflicting
  pre-certificate lock validation, reservation supersets, empty-lock exact
  producer/consumer stages in both modes, and historical producer ancestry
  supplied through the public branch-context API.
- The existing [JVM](jvm/src/main/scala/ReleaseSmoke.scala) and
  [Scala.js](js/src/main/scala/ReleaseSmoke.scala) entry points retain manifest
  rejection and runtime factory API checks.

A passing baseline means the observed M2 behavior, including its documented
limitations, was reproduced. It does not mean M2 satisfies the replacement
contract. The [baseline evidence](../docs/conformance/m2-baseline.md) records
outcomes, exact artifact identities, and the boundary between executable
consumer evidence and remaining source/runtime evidence. These checks do not
publish artifacts or activate a deployment.

The npm lock reproduces M2's `elliptic` and `js-sha3` runtime dependencies;
Maven coordinates do not install JavaScript packages. The unresolved Critical
advisory in `elliptic 6.5.4` remains an independent security follow-up. See the
[M2 release notes](../docs/releases/v0.3.0-M2-release-notes.md#dependency-security-follow-up).
