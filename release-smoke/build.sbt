import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.7.3"

val releaseVersion = "0.3.0-M2"

ThisBuild / resolvers := Seq(Resolver.mavenLocal)

lazy val jvm = project
  .in(file("jvm"))
  .settings(
    Compile / run / fork := true,
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_3"        % releaseVersion,
      "org.sigilaris" % "sigilaris-node-common_3" % releaseVersion,
      "org.sigilaris" % "sigilaris-node-jvm_3"    % releaseVersion,
    ),
  )

lazy val js = project
  .in(file("js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_sjs1_3"        % releaseVersion,
      "org.sigilaris" % "sigilaris-node-common_sjs1_3" % releaseVersion,
    ),
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  )
