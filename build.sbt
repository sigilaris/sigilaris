val V = new {
  val Scala      = "3.7.3"
  val ScalaGroup = "3.7"

  val catsEffect = "3.6.3"
  val tapir      = "1.11.44"
  val sttp       = "4.0.11"
  val circe      = "0.14.15"
  val iron       = "3.2.0"
  val scodecBits = "1.2.4"
  val fs2        = "3.12.2"

  val bouncycastle = "1.70"
  val sway         = "0.16.2"

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
      "com.outr" %%% "scribe" % V.scribe,
    ),
    Compile / npmDependencies ++= Seq(
      "js-sha3"         -> V.jsSha3,
      "elliptic"        -> V.elliptic,
      "@types/elliptic" -> V.typesElliptic,
    ),
    Compile / npmDevDependencies ++= Seq(
      "@types/node" -> "18.19.33",
    ),
  )

  lazy val tests = Def.settings(
    libraryDependencies ++= Seq(
      "qa.hedgehog"   %%% "hedgehog-munit"    % V.hedgehog        % Test,
      "org.typelevel" %%% "munit-cats-effect" % V.munitCatsEffect % Test,
    ),
    Test / fork := true,
  )
}
Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / organization      := "org.sigilaris"
ThisBuild / version           := "0.0.1-SNAPSHOT"
ThisBuild / scalaVersion      := V.Scala
ThisBuild / semanticdbEnabled := true

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / homepage      := Some(url("https://github.com/sigilaris/sigilaris"))
ThisBuild / licenses := List(
  "AGPL-3.0" -> url("https://www.gnu.org/licenses/agpl-3.0.en.html"),
)
// (optional) If needed in the future, consider forcing Scala version overrides
// scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true)))
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
  else Some("releases" at "https://central.sonatype.com/api/v1/publisher")
}

lazy val root = (project in file("."))
  .aggregate(
    core.jvm,
    core.js,
  )
  .enablePlugins(TypelevelSitePlugin)
  .settings(
    publish / skip := true,
  )

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("modules/core"))
  .settings(Dependencies.core)
  .settings(Dependencies.tests)
  .settings(
    scalacOptions ++= Seq(
      "-Wconf:msg=Alphanumeric method .* is not declared infix:s",
    ),
    Compile / compile / wartremoverErrors ++= Warts
      .allBut(Wart.SeqApply, Wart.SeqUpdated),
  )
  .jvmSettings(Dependencies.coreJVM)
  .jsSettings(Dependencies.coreJS)
  .jsSettings(
    useYarn := true,
    Test / scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
    },
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
    // Selectively silence mixed stdlib 'caps' warning in JS compile (escape colon required)
    scalacOptions += "-Wconf:msg=package scala contains object and package with same name\\: caps:s",
  )
  .jsConfigure { project =>
    project
      .enablePlugins(ScalaJSBundlerPlugin)
  }
