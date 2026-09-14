import org.scalajs.linker.interface.ModuleKind

ThisBuild / scalaVersion := "3.7.3"

val artifactVersion =
  sys.props.getOrElse("sigilaris.conformance.version", "0.3.0-M3")
val fixtureProfile = sys.props.getOrElse("sigilaris.conformance.profile", "v2")
val repositoryMode = sys.props.getOrElse("sigilaris.conformance.mode", "public")
val central        = "https://repo.maven.apache.org/maven2"
val stagingRepository           = sys.props.get("sigilaris.conformance.staging")
val validateConfiguration: Unit = {
  require(
    fixtureProfile != "legacy-m1" || artifactVersion == "0.3.0-M1",
    "legacy-m1 selects immutable M1 artifact provenance",
  )
  require(
    fixtureProfile != "legacy-m2" || artifactVersion == "0.3.0-M2",
    "legacy-m2 selects immutable M2 artifact provenance",
  )
  require(
    Set("legacy-m1", "legacy-m2", "v2").contains(fixtureProfile),
    "unknown conformance profile",
  )
  require(
    Set("public", "staging").contains(repositoryMode),
    "unknown artifact evidence mode",
  )
  require(
    repositoryMode != "staging" || stagingRepository.exists(url =>
      url.startsWith("https://") || url.startsWith("file:/"),
    ),
    "staging requires an explicit HTTPS or absolute file Maven repository URL",
  )
  require(
    repositoryMode != "public" || stagingRepository.isEmpty,
    "public mode cannot include a staging repository",
  )
}

ThisBuild / resolvers := (if (repositoryMode == "staging")
                            Seq("explicit-staging" at stagingRepository.get)
                          else Seq.empty) :+
  ("public-central" at central)
ThisBuild / externalResolvers := (ThisBuild / resolvers).value

val legacySources   = file("shared/legacy/scala").getAbsoluteFile
val runnerSources   = file("shared/runner/scala").getAbsoluteFile
val v2RunnerSources = file("shared/v2-runner/scala").getAbsoluteFile
val v2JvmSources    = file("shared/v2-jvm/scala").getAbsoluteFile
val v2JsSources     = file("shared/v2-js/scala").getAbsoluteFile
val v2Sources       = file("shared/v2/scala").getAbsoluteFile
val conformanceMain = "org.sigilaris.conformance." + (fixtureProfile match {
  case "legacy-m1" => "LegacyM1Conformance"
  case "legacy-m2" => "LegacyM2Conformance"
  case "v2"        => "V2ConformanceMain"
})

lazy val verifyArtifactIdentities = taskKey[Unit](
  "Record exactly selected public/staged compiler artifact identities",
)
lazy val verifySourceIdentities = taskKey[Unit](
  "Verify and record every actual compiler source against the public input inventory",
)
lazy val verifyCryptoIdentities = taskKey[Unit](
  "Verify the selected JVM crypto provider and reject mixed provider generations",
)
val commonSettings = Seq(
  Compile / mainClass := Some(conformanceMain),
  Compile / unmanagedSourceDirectories ++= Seq(
    legacySources,
    runnerSources,
  ) ++ (if (fixtureProfile == "v2") Seq(v2Sources, v2RunnerSources)
        else Seq.empty),
  verifySourceIdentities := {
    val root =
      (LocalRootProject / baseDirectory).value.getParentFile.getCanonicalFile
    val lines = IO.readLines(
      root / "docs/conformance/public-conformance-inputs.sha256",
    )
    val expected = lines.map { line =>
      val parts = line.split("  ", 2)
      require(
        parts.length == 2 && parts(0).matches("[0-9a-f]{64}"),
        "invalid public input inventory",
      )
      parts(1) -> parts(0)
    }.toMap
    require(
      expected.size == lines.size,
      "duplicate public input inventory paths",
    )
    val selected = (Compile / sources).value.map(_.getCanonicalFile)
    require(
      selected.nonEmpty && selected.distinct.size == selected.size,
      "compiler sources must be nonempty and unique",
    )
    val identities = selected
      .map { source =>
        require(
          source.toPath.startsWith(root.toPath),
          s"compiler source is outside the exported root: $source",
        )
        val relative = root.toPath
          .relativize(source.toPath)
          .toString
          .replace(java.io.File.separatorChar, '/')
        require(
          expected.contains(relative),
          s"compiler source missing from public input inventory: $relative",
        )
        val hash = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(IO.readBytes(source))
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
        require(
          expected(relative) == hash,
          s"compiler source checksum mismatch: $relative",
        )
        relative -> s"$hash  $relative"
      }
      .sortBy(_._1)
      .map(_._2)
    val platform = thisProject.value.id
    IO.writeLines(
      file(s"target/identities/$fixtureProfile-$platform-sources.sha256"),
      identities,
    )
    streams.value.log.info(
      s"$fixtureProfile $platform compiler source identities PASS: ${identities.size}",
    )
  },
  verifyArtifactIdentities := {
    val log      = streams.value.log
    val platform = thisProject.value.id
    val names    =
      if (platform == "js")
        Set("sigilaris-core_sjs1_3", "sigilaris-node-common_sjs1_3")
      else
        Set(
          "sigilaris-core_3",
          "sigilaris-node-common_3",
          "sigilaris-node-jvm_3",
        )
    val expected = names.map(name => s"$name-$artifactVersion.jar")
    val jars     = (Compile / dependencyClasspath).value
      .map(_.data)
      .filter(_.getName.startsWith("sigilaris-"))
    require(
      jars.map(_.getName).toSet == expected && jars.size == expected.size,
      s"selected coordinates differ from expected $expected",
    )
    val pinnedHistorical =
      if (fixtureProfile.startsWith("legacy-"))
        IO.readLines(file(s"fixtures/$fixtureProfile-artifacts.sha256"))
          .map { line =>
            val parts = line.split("  ")
            parts(1) -> parts(0)
          }
          .toMap
      else Map.empty[String, String]
    val identities = jars.sortBy(_.getName).map { jar =>
      val path = jar.getCanonicalPath
      require(
        !path.contains("/.m2/") && !path.contains("/.ivy2/local/"),
        s"local publication cache forbidden: $path",
      )
      if (repositoryMode == "public")
        require(
          path.contains("/https/repo.maven.apache.org/maven2/org/sigilaris/"),
          s"public artifact must resolve from Central: $path",
        )
      else {
        val repository   = new java.net.URI(stagingRepository.get).normalize()
        val artifactName = jar.getName.stripSuffix(s"-$artifactVersion.jar")
        val relative     =
          s"org/sigilaris/$artifactName/$artifactVersion/${jar.getName}"
        val matches = repository.getScheme match {
          case "file" =>
            val root = new java.io.File(repository).getCanonicalFile
            jar.getCanonicalFile == new java.io.File(
              root,
              relative,
            ).getCanonicalFile
          case "https" =>
            require(
              repository.getHost != null && repository.getRawUserInfo == null &&
                repository.getRawQuery == null && repository.getRawFragment == null,
              "HTTPS staging origin must have a host and no userinfo, query or fragment",
            )
            // Match Coursier CachePath.localFile's escaping of the complete URL,
            // including percent-encoded repository paths and explicit ports.
            val ascii  = new java.net.URI(repository.toASCIIString)
            val origin =
              s"https/${ascii.getRawAuthority}${ascii.getRawPath.stripSuffix("/")}/$relative"
            val escaped = origin.flatMap { character =>
              if (" %$&+,:;=?@<>#".contains(character))
                f"%%${character.toInt}%02X"
              else character.toString
            }
            path.endsWith("/" + escaped)
          case _ => false
        }
        require(
          matches,
          s"staging artifact must resolve from the selected repository $repository: $path",
        )
      }
      val hash = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(IO.readBytes(jar))
        .map(byte => f"${byte & 0xff}%02x")
        .mkString
      if (pinnedHistorical.nonEmpty)
        require(
          pinnedHistorical.get(jar.getName).contains(hash),
          s"immutable historical artifact checksum mismatch: $jar",
        )
      log.info(
        s"$repositoryMode $fixtureProfile SHA256 $hash ${jar.getName} $path",
      )
      s"$hash  ${jar.getName}"
    }
    IO.writeLines(
      file(
        s"target/identities/$fixtureProfile-$platform-$repositoryMode.sha256",
      ),
      identities,
    )
  },
)

lazy val jvm = project
  .in(file("jvm"))
  .settings(commonSettings)
  .settings(
    verifyCryptoIdentities := {
      val expected =
        if (fixtureProfile == "v2") "bcprov-jdk18on-1.86.jar"
        else "bcprov-jdk15on-1.70.jar"
      val providers = (Compile / dependencyClasspath).value
        .map(_.data)
        .filter(_.getName.startsWith("bcprov-"))
      require(
        providers.size == 1 && providers.head.getName == expected,
        s"unexpected JVM crypto providers: ${providers.map(_.getName)}; expected $expected",
      )
      val transport = (Compile / dependencyClasspath).value
        .map(_.data)
        .filter(jar =>
          jar.getName.startsWith("netty-") || jar.getName
            .matches("armeria-[0-9].*\\.jar"),
        )
      if (fixtureProfile == "v2") {
        require(
          transport.exists(_.getName == "armeria-1.41.1.jar"),
          "missing M3 Armeria runtime",
        )
        require(
          transport.exists(_.getName == "netty-handler-4.2.18.Final.jar"),
          "missing patched TLS handler",
        )
        transport.foreach { jar =>
          val version =
            if (jar.getName.startsWith("armeria-")) "1.41.1"
            else if (jar.getName.startsWith("netty-tcnative-")) "2.0.84.Final"
            else "4.2.18.Final"
          require(
            jar.getName.contains(s"-$version.jar") || jar.getName
              .contains(s"-$version-"),
            s"unexpected transport dependency: ${jar.getName}",
          )
        }
      }
      val identities = (providers ++ transport).sortBy(_.getName).map { jar =>
        val hash = java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(IO.readBytes(jar))
          .map(byte => f"${byte & 0xff}%02x")
          .mkString
        s"$hash  ${jar.getName}"
      }
      IO.writeLines(
        file(s"target/identities/$fixtureProfile-jvm-crypto.sha256"),
        identities,
      )
      streams.value.log.info(
        s"$fixtureProfile JVM crypto dependencies PASS: $expected and ${transport.size} transport JARs",
      )
    },
    Compile / run / fork := true,
    Compile / unmanagedSources += file(
      "tools/LegacyRecoveryAlias.scala",
    ).getAbsoluteFile,
    Compile / unmanagedSourceDirectories ++= (if (fixtureProfile == "v2")
                                                Seq(v2JvmSources)
                                              else Seq.empty),
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_3"        % artifactVersion,
      "org.sigilaris" % "sigilaris-node-common_3" % artifactVersion,
      "org.sigilaris" % "sigilaris-node-jvm_3"    % artifactVersion,
    ),
  )

lazy val js = project
  .in(file("js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.sigilaris" % "sigilaris-core_sjs1_3"        % artifactVersion,
      "org.sigilaris" % "sigilaris-node-common_sjs1_3" % artifactVersion,
    ),
    Compile / unmanagedSourceDirectories ++= (if (fixtureProfile == "v2")
                                                Seq(v2JsSources)
                                              else Seq.empty),
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)),
  )
