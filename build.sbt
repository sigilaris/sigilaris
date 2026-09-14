val V = new {
  val Scala      = "3.7.3"
  val ScalaGroup = "3.7"

  val catsEffect       = "3.6.3"
  val tapir            = "1.11.44"
  val sttp             = "4.0.11"
  val openApiCirceYaml = "0.11.10"
  val circe            = "0.14.15"
  val iron             = "3.2.0"
  val scodecBits       = "1.2.4"
  val fs2              = "3.12.2"

  val bouncycastle = "1.86"
  val armeria      = "1.41.1"
  val netty        = "4.2.18.Final"
  val nettyNative  = "2.0.84.Final"
  val sway         = "0.16.2"
  val shapeless    = "3.5.0"

  val scribe          = "3.17.0"
  val hedgehog        = "0.13.0"
  val munitCatsEffect = "2.2.0-RC1"

  val scalaJavaTime = "2.3.0"
  val jsSha3        = "0.8.0"
  val elliptic      = "6.6.1"
  val bnJs          = "4.12.5"
  val typesElliptic = "6.4.18"
}

val Dependencies = new {

  lazy val core = Seq(
    libraryDependencies ++= Seq(
      "org.typelevel"      %%% "cats-effect"         % V.catsEffect,
      "io.circe"           %%% "circe-generic"       % V.circe,
      "io.circe"           %%% "circe-parser"        % V.circe,
      "io.github.iltotore" %%% "iron"                % V.iron,
      "io.github.iltotore" %%% "iron-circe"          % V.iron,
      "org.scodec"         %%% "scodec-bits"         % V.scodecBits,
      "co.fs2"             %%% "fs2-core"            % V.fs2,
      "org.typelevel"      %%% "shapeless3-typeable" % V.shapeless,
    ),
  )

  lazy val coreJVM = Seq(
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcprov-jdk18on" % V.bouncycastle,
      "com.outr"        %% "scribe-slf4j"   % V.scribe,
    ),
  )

  lazy val coreJS = Seq(
    libraryDependencies ++= Seq(
      "com.outr"          %%% "scribe"          % V.scribe,
      "io.github.cquiroz" %%% "scala-java-time" % V.scalaJavaTime,
    ),
    Compile / npmDependencies ++= Seq(
      "js-sha3"         -> V.jsSha3,
      "elliptic"        -> V.elliptic,
      "bn.js"           -> V.bnJs,
      "@types/elliptic" -> V.typesElliptic,
    ),
    Compile / npmDevDependencies ++= Seq(
      "@types/node" -> "18.19.33",
    ),
    // Share the same NPM deps with Test to avoid duplication
    Test / npmDependencies    := (Compile / npmDependencies).value,
    Test / npmDevDependencies := (Compile / npmDevDependencies).value,
  )

  lazy val tests = Def.settings(
    libraryDependencies ++= Seq(
      "qa.hedgehog"   %%% "hedgehog-munit"    % V.hedgehog        % Test,
      "org.typelevel" %%% "munit-cats-effect" % V.munitCatsEffect % Test,
      // TestControl must match the cats-effect core version on the classpath.
      "org.typelevel" %%% "cats-effect-testkit" % V.catsEffect % Test,
    ),
    Test / fork := true,
  )

  lazy val nodeJvm = Seq(
    libraryDependencies ++= Seq(
      "com.linecorp.armeria"         % "armeria"                   % V.armeria,
      "com.softwaremill.sttp.tapir" %% "tapir-armeria-server-cats" % V.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client4"        % V.tapir,
      "com.softwaremill.sttp.client4" %% "armeria-backend-cats" % V.sttp,
      "com.softwaremill.sttp.client4" %% "armeria-backend-fs2"  % V.sttp,
      "com.softwaremill.sttp.tapir"   %% "tapir-openapi-docs"   % V.tapir,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % V.openApiCirceYaml,
      "com.typesafe" % "config" % "1.4.3",
      ("io.swaydb"  %% "swaydb" % V.sway).cross(CrossVersion.for3Use2_13),
    ),
    // Keep the TLS fix in published POMs as well as the source build. Armeria
    // 1.41.1 still requests Netty 4.2.16, before the fragmented ClientHello fix.
    libraryDependencies ++= Seq(
      "netty-transport",
      "netty-codec-haproxy",
      "netty-codec-http2",
      "netty-resolver-dns",
      "netty-transport-native-unix-common",
      "netty-handler",
      "netty-handler-proxy",
      "netty-transport-native-epoll",
      "netty-transport-native-kqueue",
      "netty-resolver-dns-native-macos",
      "netty-transport-native-io_uring",
    ).map("io.netty" % _ % V.netty),
    libraryDependencies ++= Seq(
      "netty-tcnative-boringssl-static",
      "netty-tcnative-classes",
    ).map("io.netty" % _ % V.nettyNative),
    excludeDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-collection-compat_2.13",
      "org.scala-lang.modules" % "scala-java8-compat_2.13",
      "org.typelevel"          % "cats-core_2.13",
      "org.typelevel"          % "cats-kernel_2.13",
      "org.typelevel"          % "cats-effect_2.13",
    ),
  )
}
lazy val verifyJvmCrypto = taskKey[Unit](
  "Reject stale or mixed JVM crypto providers before tests and packaging",
)
lazy val verifyJsCrypto = taskKey[Unit](
  "Verify the actual Scala.js test crypto runtime, including nested dependencies",
)
val cryptoJvmChecks = Seq(
  verifyJvmCrypto := {
    val providers = (Compile / externalDependencyClasspath).value
      .map(_.data)
      .filter(_.getName.startsWith("bcprov-"))
    val expected = s"bcprov-jdk18on-${V.bouncycastle}.jar"
    require(
      providers.size == 1 && providers.head.getName == expected,
      s"Unexpected crypto providers ${providers.map(_.getName)}; expected $expected. Clean and resolve after changing provider coordinates.",
    )
    val nettyJars = (Compile / externalDependencyClasspath).value
      .map(_.data)
      .filter(_.getName.startsWith("netty-"))
    nettyJars.foreach { jar =>
      val version =
        if (jar.getName.startsWith("netty-tcnative-")) V.nettyNative
        else V.netty
      require(
        jar.getName.contains(s"-$version.jar") || jar.getName.contains(
          s"-$version-",
        ),
        s"Unexpected TLS dependency ${jar.getName}; expected $version",
      )
    }
    streams.value.log.info(s"JVM crypto dependency PASS: $expected")
  },
  Test / test      := (Test / test).dependsOn(verifyJvmCrypto).value,
  Test / testOnly  := (Test / testOnly).dependsOn(verifyJvmCrypto).evaluated,
  Test / testQuick := (Test / testQuick).dependsOn(verifyJvmCrypto).evaluated,
  Compile / packageBin := (Compile / packageBin)
    .dependsOn(verifyJvmCrypto)
    .value,
)
val cryptoJsChecks = Seq(
  verifyJsCrypto := {
    val npmRoot = (Test / npmUpdate).value
    val root    = (LocalRootProject / baseDirectory).value
    val script  = root / "release-conformance/tools/verify-crypto-runtime.cjs"
    val output  = target.value / "crypto-runtime.json"
    val exit    = scala.sys.process
      .Process(
        Seq(
          "node",
          script.getAbsolutePath,
          npmRoot.getAbsolutePath,
          output.getAbsolutePath,
        ),
        root,
      )
      .!
    require(
      exit == 0,
      "Unexpected Scala.js crypto runtime; inspect resolved npm dependencies",
    )
  },
  Test / test      := (Test / test).dependsOn(verifyJsCrypto).value,
  Test / testOnly  := (Test / testOnly).dependsOn(verifyJsCrypto).evaluated,
  Test / testQuick := (Test / testQuick).dependsOn(verifyJsCrypto).evaluated,
)
Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / organization      := "org.sigilaris"
ThisBuild / version           := "0.3.0-M3"
ThisBuild / scalaVersion      := V.Scala
ThisBuild / semanticdbEnabled := true

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage      := Some(url("https://github.com/sigilaris/sigilaris"))
ThisBuild / licenses      := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html"),
)
ThisBuild / developers := List(
  Developer(
    id = "sungkmi",
    name = "Heungjin Kim",
    email = "contact@sigilaris.org",
    url = url("https://github.com/sungkmi"),
  ),
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/sigilaris/sigilaris"),
    "scm:git@github.com:sigilaris/sigilaris.git",
  ),
)
ThisBuild / sonatypeCredentialHost := "central.sonatype.com"

Global / useGpgPinentry := false
Global / pgpPassphrase  := sys.env.get("PGP_PASSPHRASE").map(_.toArray)

ThisBuild / publishTo := {
  val snapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at snapshots)
  else sonatypePublishToBundle.value
}

lazy val copyUnidocIntoSite = taskKey[Unit](
  "Copy Scala unidoc output into target/docs/site/api after tlSite generation.",
)

lazy val root = (project in file("."))
  .aggregate(
    core.jvm,
    core.js,
    nodeCommon.jvm,
    nodeCommon.js,
    nodeJvm,
    benchmarks,
    tools,
  )
  .dependsOn(core.jvm)
  .enablePlugins(TypelevelSitePlugin, ScalaUnidocPlugin)
  .settings(
    publish / skip                               := true,
    (ScalaUnidoc / unidoc) / unidocProjectFilter := inProjects(
      core.jvm,
      nodeCommon.jvm,
      nodeJvm,
    ),
    // Map unidoc into site output so preview won't drop it
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(
      ScalaUnidoc / packageDoc / mappings,
      ScalaUnidoc / siteSubdirName,
    ),
    // (CI fallback in workflow handles copying into /api.)
    // Ensure mdoc reads from Typelevel Site convention: site/src (not default docs/)
    mdocIn := baseDirectory.value / "site" / "src",
    // Silence unused warnings only for mdoc (documentation examples)
    mdocExtraArguments ++= Seq(
      "--scalac-options",
      "-Wconf:cat=unused:s",
    ),
    copyUnidocIntoSite := {
      val apiDir         = target.value / "docs" / "site" / "api"
      val unidocMappings = (ScalaUnidoc / packageDoc / mappings).value
      IO.delete(apiDir)
      IO.copy(
        unidocMappings.map { case (source, relativePath) =>
          source -> (apiDir / relativePath)
        },
      )
    },
    tlSite := Def
      .sequential(
        Def.task {
          tlSite.value
        },
        copyUnidocIntoSite,
      )
      .value,
  )

// Note: tlSite copies ScalaUnidoc packageDoc mappings into target/docs/site/api.

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .settings(Dependencies.core)
  .settings(Dependencies.tests)
  .settings(
    moduleName := "sigilaris-core",
    Test / unmanagedSourceDirectories ++= {
      val repository = (LocalRootProject / baseDirectory).value
      Seq(
        repository / "release-conformance/shared/legacy/scala",
        repository / "release-conformance/shared/v2/scala",
      )
    },
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )
  .jvmSettings(
    Dependencies.coreJVM,
    // JVM: silence infix warning with precise message filter
    scalacOptions ++= Seq(
      "-Wconf:msg=Alphanumeric method .* is not declared infix:s",
    ),
  )
  .jvmSettings(cryptoJvmChecks)
  .jsSettings(Dependencies.coreJS)
  .jsSettings(cryptoJsChecks)
  .jsSettings(
    useYarn := true,
    // Tests run under Node: prefer CommonJS to support require()
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    // Ensure webpack bundles tests so Node resolves NPM deps like 'elliptic'
    Test / webpackBundlingMode := scalajsbundler.BundlingMode
      .LibraryAndApplication(),
    Test / logBuffered := false,
    Test / testOptions += Tests.Argument(TestFrameworks.MUnit, "-v"),
    scalacOptions ++= Seq(
      "-scalajs",
    ),
    Test / fork := false,
    // Do not escalate warnings to errors in JS targets; external typings may emit warnings
    Compile / scalacOptions ~= { opts =>
      opts.filterNot(Set("-Werror", "-Xfatal-warnings"))
    },
    Test / scalacOptions ~= { opts =>
      opts.filterNot(Set("-Werror", "-Xfatal-warnings"))
    },
    // JS: silence warnings via message filters only (avoid colon in pattern)
    Compile / scalacOptions ++= Seq(
      "-Wconf:msg=Alphanumeric method .* is not declared infix:s",
      "-Wconf:msg=package scala contains object and package with same name.*caps:s",
    ),
    Test / scalacOptions ++= Seq(
      "-Wconf:msg=Alphanumeric method .* is not declared infix:s",
      "-Wconf:msg=package scala contains object and package with same name.*caps:s",
    ),
  )
  .jsConfigure { project =>
    project
      .enablePlugins(ScalaJSBundlerPlugin)
  }

lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .dependsOn(core.jvm)
  .settings(
    publish / skip                         := true,
    publishLocal / skip                    := true,
    Compile / publishArtifact              := false,
    Test / publishArtifact                 := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    Test / fork                            := true,
  )

lazy val nodeCommon = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/node-common"))
  .dependsOn(core)
  .settings(Dependencies.tests)
  .jvmSettings(cryptoJvmChecks)
  .jsSettings(cryptoJsChecks)
  .settings(
    moduleName := "sigilaris-node-common",
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )
  .jsSettings(
    // Shared gossip model uses java.time on JS as well.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % V.scalaJavaTime,
    // Pin the actual consumer runtime as well as the core project's metadata.
    Compile / npmDependencies ++= (core.js / Compile / npmDependencies).value,
    Test / npmDependencies := (Compile / npmDependencies).value,
    useYarn                := true,
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / webpackBundlingMode := scalajsbundler.BundlingMode
      .LibraryAndApplication(),
    Test / logBuffered := false,
    Test / fork        := false,
  )
  .jsConfigure { project =>
    project.enablePlugins(ScalaJSBundlerPlugin)
  }

lazy val tools = (project in file("tools"))
  .settings(
    publish / skip                         := true,
    publishLocal / skip                    := true,
    Compile / publishArtifact              := false,
    Test / publishArtifact                 := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "3.3.1",
    ),
  )

lazy val nodeJvm = (project in file("modules/node-jvm"))
  .dependsOn(nodeCommon.jvm)
  .settings(Dependencies.nodeJvm)
  .settings(Dependencies.tests)
  .settings(cryptoJvmChecks)
  .settings(
    moduleName := "sigilaris-node-jvm",
    Test / unmanagedSourceDirectories +=
      (LocalRootProject / baseDirectory).value / "release-conformance/shared/v2-jvm/scala",
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )

// One-command aliases for Phase 6 (Scala-based orchestrations)
// Writes JMH JSON to target/jmh-result.json, then archives and compares via tools/BenchGuard
addCommandAlias(
  "bench",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json",
)

addCommandAlias(
  "benchGc",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --gc",
)

addCommandAlias(
  "benchRecover",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*recover.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --include recover",
)

addCommandAlias(
  "benchRecoverGc",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.*recover.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --gc --include recover",
)
