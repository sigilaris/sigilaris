val V = new {
  val Scala      = "3.7.3"
  val ScalaGroup = "3.7"

  val catsEffect = "3.6.3"
  val tapir      = "1.11.44"
  val sttp       = "4.0.11"
  val openApiCirceYaml = "0.11.10"
  val circe      = "0.14.15"
  val iron       = "3.2.0"
  val scodecBits = "1.2.4"
  val fs2        = "3.12.2"

  val bouncycastle = "1.70"
  val sway         = "0.16.2"
  val shapeless    = "3.5.0"

  val scribe          = "3.17.0"
  val hedgehog        = "0.13.0"
  val munitCatsEffect = "2.2.0-RC1"

  val scalaJavaTime = "2.3.0"
  val jsSha3        = "0.8.0"
  val elliptic      = "6.5.4"
  val typesElliptic = "6.4.18"
}

val Dependencies = new {

  lazy val core = Seq(
    libraryDependencies ++= Seq(
      "org.typelevel"      %%% "cats-effect"   % V.catsEffect,
      "io.circe"           %%% "circe-generic" % V.circe,
      "io.circe"           %%% "circe-parser"  % V.circe,
      "io.github.iltotore" %%% "iron"          % V.iron,
      "io.github.iltotore" %%% "iron-circe"    % V.iron,
      "org.scodec"         %%% "scodec-bits"   % V.scodecBits,
      "co.fs2"             %%% "fs2-core"      % V.fs2,
      "org.typelevel" %%% "shapeless3-typeable" % V.shapeless,
    ),
  )

  lazy val coreJVM = Seq(
    libraryDependencies ++= Seq(
      "org.bouncycastle" % "bcprov-jdk15on" % V.bouncycastle,
      "com.outr"        %% "scribe-slf4j"   % V.scribe,
    ),
  )

  lazy val coreJS = Seq(
    libraryDependencies ++= Seq(
      "com.outr"       %%% "scribe"           % V.scribe,
      "io.github.cquiroz" %%% "scala-java-time" % V.scalaJavaTime,
    ),
    Compile / npmDependencies ++= Seq(
      "js-sha3"         -> V.jsSha3,
      "elliptic"        -> V.elliptic,
      "@types/elliptic" -> V.typesElliptic,
    ),
    Compile / npmDevDependencies ++= Seq(
      "@types/node" -> "18.19.33",
    ),
    // Share the same NPM deps with Test to avoid duplication
    Test / npmDependencies := (Compile / npmDependencies).value,
    Test / npmDevDependencies := (Compile / npmDevDependencies).value,
  )

  lazy val tests = Def.settings(
    libraryDependencies ++= Seq(
      "qa.hedgehog"   %%% "hedgehog-munit"    % V.hedgehog        % Test,
      "org.typelevel" %%% "munit-cats-effect" % V.munitCatsEffect % Test,
    ),
    Test / fork := true,
  )

  lazy val nodeJvm = Seq(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-armeria-server-cats" % V.tapir,
      "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs"        % V.tapir,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"      % V.openApiCirceYaml,
      "com.typesafe" % "config" % "1.4.3",
      ("io.swaydb" %% "swaydb" % V.sway).cross(CrossVersion.for3Use2_13),
    ),
    excludeDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-collection-compat_2.13",
      "org.scala-lang.modules" % "scala-java8-compat_2.13",
      "org.typelevel"          % "cats-core_2.13",
      "org.typelevel"          % "cats-kernel_2.13",
      "org.typelevel"          % "cats-effect_2.13",
    ),
  )
}
Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / organization      := "org.sigilaris"
ThisBuild / version           := "0.2.0"
ThisBuild / scalaVersion      := V.Scala
ThisBuild / semanticdbEnabled := true

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage      := Some(url("https://github.com/sigilaris/sigilaris"))
ThisBuild / licenses := List(
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
    publish / skip := true,
    (ScalaUnidoc / unidoc) / unidocProjectFilter := inProjects(
      core.jvm,
      nodeCommon.jvm,
      nodeJvm,
    ),
    // Map unidoc into site output so preview won't drop it
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName),
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
    tlSite := Def.sequential(
      Def.task {
        tlSite.value
      },
      copyUnidocIntoSite,
    ).value,
  )

// Note: tlSite copies ScalaUnidoc packageDoc mappings into target/docs/site/api.

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .settings(Dependencies.core)
  .settings(Dependencies.tests)
  .settings(
    moduleName := "sigilaris-core",
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
  .jsSettings(Dependencies.coreJS)
  .jsSettings(
    useYarn := true,
    // Tests run under Node: prefer CommonJS to support require()
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    // Ensure webpack bundles tests so Node resolves NPM deps like 'elliptic'
    Test / webpackBundlingMode := scalajsbundler.BundlingMode.LibraryAndApplication(),
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
    publish / skip := true,
    publishLocal / skip := true,
    Compile / publishArtifact := false,
    Test / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    Test / fork := true,
  )

lazy val nodeCommon = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/node-common"))
  .dependsOn(core)
  .settings(Dependencies.tests)
  .settings(
    moduleName := "sigilaris-node-common",
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )
  .jsSettings(
    // Shared gossip model uses java.time on JS as well.
    libraryDependencies += "io.github.cquiroz" %%% "scala-java-time" % V.scalaJavaTime,
    useYarn := true,
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
    Test / webpackBundlingMode := scalajsbundler.BundlingMode.LibraryAndApplication(),
    Test / logBuffered := false,
    Test / fork := false,
  )
  .jsConfigure { project =>
    project.enablePlugins(ScalaJSBundlerPlugin)
  }

lazy val tools = (project in file("tools"))
  .settings(
    publish / skip := true,
    publishLocal / skip := true,
    Compile / publishArtifact := false,
    Test / publishArtifact := false,
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
  .settings(
    moduleName := "sigilaris-node-jvm",
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )

// One-command aliases for Phase 6 (Scala-based orchestrations)
// Writes JMH JSON to target/jmh-result.json, then archives and compares via tools/BenchGuard
addCommandAlias(
  "bench",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json"
)

addCommandAlias(
  "benchGc",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --gc"
)

addCommandAlias(
  "benchRecover",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 .*CryptoOpsBenchmark.*recover.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --include recover"
)

addCommandAlias(
  "benchRecoverGc",
  "benchmarks/jmh:run -i 10 -wi 5 -f1 -t1 -prof gc .*CryptoOpsBenchmark.*recover.* -rf json -rff target/jmh-result.json ; tools/run --result benchmarks/target/jmh-result.json --gc --include recover"
)
