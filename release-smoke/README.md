# v0.3.0-M2 Downstream Artifact Smoke

This standalone sbt build intentionally has no source dependency on the parent
repository. It resolves the five `0.3.0-M2` release coordinates from Maven
Local, then compiles and runs one neutral JVM consumer and one neutral Scala.js
consumer.

Both consumers verify that invalid verifier manifest digests return the typed
manifest failure before hashing. The JVM consumer also creates an in-memory
exact pipeline store through its effectful factory, queries a missing request
identity, and counts unjournaled admissions. These checks exercise the post-M1
API through published artifacts. The application protocol remains
`ProtocolVersion.M1`; M2 is the artifact milestone, not a new wire protocol.

From this directory, after staging with `sbt publishM2`, run:

(`publishM2` is sbt's Maven Local publication task.)

```sh
npm ci --ignore-scripts
sbt "jvm/run" "js/fullLinkJS" "js/run"
node js/target/scala-3.7.3/js-opt/main.js
```

The npm lock pins the same `elliptic` and `js-sha3` versions used by the
library build. Maven coordinates do not install JavaScript runtime packages.
The final Node invocation also executes the fully optimized consumer.

The lock reproduces M2's tested dependencies, including an unresolved
Critical advisory in `elliptic 6.5.4`; it is not a production security
recommendation. See the
[dependency security follow-up](../docs/releases/v0.3.0-M2-release-notes.md#dependency-security-follow-up)
before production signing.
