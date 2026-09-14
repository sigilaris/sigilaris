import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.7.3"

val releaseVersion = "0.3.0-M2"

ThisBuild / resolvers := Seq(
  "public-central" at "https://repo.maven.apache.org/maven2",
)

val sharedFixtures = file("shared/src/main/scala").getAbsoluteFile

lazy val verifyM2PublicArtifacts = taskKey[Unit](
  "Verify the actual compiler classpath against immutable public M2 JAR checksums",
)

val publicArtifactChecks = Seq(
  verifyM2PublicArtifacts := {
    val log      = streams.value.log
    val expected = IO
      .readLines(file("fixtures/m2-checksums.sha256"))
      .filter(_.endsWith(".jar"))
      .map(line => {
        val parts = line.split("  "); new File(parts(1)).getName -> parts(0)
      })
      .toMap
    val artifacts = (Compile / dependencyClasspath).value
      .map(_.data)
      .filter(_.getName.startsWith("sigilaris-"))
    val count = if (thisProject.value.id == "js") 2 else 3
    require(
      artifacts.size == count,
      s"expected $count Sigilaris artifacts, got ${artifacts.size}",
    )
    artifacts.foreach { artifact =>
      require(
        artifact.getCanonicalPath.contains(
          "/https/repo.maven.apache.org/maven2/org/sigilaris/",
        ),
        s"artifact is not from the public Central Coursier cache: $artifact",
      )
      val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(IO.readBytes(artifact))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
      require(
        expected.get(artifact.getName).contains(hash),
        s"M2 checksum mismatch: $artifact",
      )
      log.info(s"PUBLIC M2 SHA256 $hash ${artifact.getName}")
    }
  },
)

lazy val jvm = project
  .in(file("jvm"))
  .settings(publicArtifactChecks)
  .settings(
    Compile / run / fork := true,
    Compile / mainClass  := Some("ReleaseSmoke"),
    Compile / unmanagedSourceDirectories += sharedFixtures,
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_3"        % releaseVersion,
      "org.sigilaris" % "sigilaris-node-common_3" % releaseVersion,
      "org.sigilaris" % "sigilaris-node-jvm_3"    % releaseVersion,
    ),
  )

lazy val js = project
  .in(file("js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(publicArtifactChecks)
  .settings(
    Compile / mainClass := Some("ReleaseSmoke"),
    Compile / unmanagedSourceDirectories += sharedFixtures,
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_sjs1_3"        % releaseVersion,
      "org.sigilaris" % "sigilaris-node-common_sjs1_3" % releaseVersion,
    ),
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  )
