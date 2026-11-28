import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, StandardCopyOption}
import com.typesafe.tools.mima.core.{
  DirectMissingMethodProblem,
  IncompatibleResultTypeProblem,
  ProblemFilters
}

val scala3Version = "3.3.9"

val mimaBinaryCompatibleVersions = Set("0.2.0")

val catsEffectVersion = "3.7.1"
val fs2Version = "3.14.0"
val log4catsVersion = "2.8.0"
val logbackVersion = "1.6.8"
val grpcVersion = "1.85.1"
val scalatestVersion = "3.2.20"
val scalatestScalaCheckVersion = "3.2.20.0"
val catsEffectTestingVersion = "1.8.0"

ThisBuild / organization := "com.github.bmscomp"
ThisBuild / scalaVersion := scala3Version
ThisBuild / dynverSeparator := "-"
ThisBuild / coverageMinimumStmtTotal := 88
ThisBuild / coverageFailOnMinimum := true
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val commonScalacOptions = Seq(
  "-encoding",
  "utf8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Werror",
  "-Wconf:src=src_managed/.*:silent"
)

lazy val strictMainScalacOptions = Seq(
  "-Wvalue-discard",
  "-Wnonunit-statement"
)

lazy val strictMainSettings = Seq(
  Compile / scalacOptions ++= strictMainScalacOptions,
  Test / scalacOptions ~= (_.filterNot(strictMainScalacOptions.toSet))
)

lazy val publishSettings = Seq(
  crossPaths := false,
  publishMavenStyle := true,
  publishTo := Some(
    "GitHub Packages" at "https://maven.pkg.github.com/bmscomp/raft"
  ),
  credentials += Credentials(
    "GitHub Package Registry",
    "maven.pkg.github.com",
    "bmscomp",
    sys.env.getOrElse("GITHUB_TOKEN", "")
  )
)

def fetchReleaseJar(
    cacheDir: File,
    moduleName: String,
    version: String,
    log: sbt.util.Logger
): File = {
  val targetJar = cacheDir / s"$moduleName-$version.jar"
  if (!targetJar.exists() || targetJar.length() == 0L) {
    Files.createDirectories(cacheDir.toPath)
    val url =
      s"https://github.com/bmscomp/raft/releases/download/v$version/$moduleName-$version.jar"
    log.info(s"Downloading MiMa baseline artifact: $url")
    val client = HttpClient
      .newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
    val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
    val tmpPath =
      Files.createTempFile(
        cacheDir.toPath,
        s"$moduleName-$version-",
        ".jar.tmp"
      )
    try {
      val response =
        client.send(request, HttpResponse.BodyHandlers.ofFile(tmpPath))
      if (response.statusCode() != 200) {
        sys.error(
          s"Failed to download MiMa baseline $url (HTTP ${response.statusCode()})"
        )
      }
      Files.move(tmpPath, targetJar.toPath, StandardCopyOption.REPLACE_EXISTING)
    } finally {
      Files.deleteIfExists(tmpPath)
    }
  }
  targetJar
}

lazy val mimaSettings = Seq(
  mimaFailOnNoPrevious := true,
  mimaCheckDirection := "backward",
  mimaPreviousArtifacts := mimaBinaryCompatibleVersions.map { v =>
    organization.value % name.value % v
  },
  mimaPreviousClassfiles := {
    val log = streams.value.log
    val cacheDir = (ThisBuild / baseDirectory).value / "target" / "mima-cache"
    mimaPreviousArtifacts.value.map { m =>
      m -> fetchReleaseJar(cacheDir, m.name, m.revision, log)
    }.toMap
  }
)

lazy val noMimaSettings = Seq(
  mimaFailOnNoPrevious := false,
  mimaPreviousArtifacts := Set.empty
)

lazy val commonTestDependencies = Seq(
  "org.scalatest" %% "scalatest" % scalatestVersion % Test,
  "org.scalatestplus" %% "scalacheck-1-19" % scalatestScalaCheckVersion % Test
)

lazy val effectTestDependencies = commonTestDependencies ++ Seq(
  "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion % Test,
  "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingVersion % Test,
  "org.typelevel" %% "log4cats-slf4j" % log4catsVersion % Test,
  "ch.qos.logback" % "logback-classic" % logbackVersion % Test
)

lazy val core = project
  .in(file("modules/core"))
  .settings(publishSettings, strictMainSettings, mimaSettings)
  .settings(
    name := "raft-core",
    scalacOptions ++= commonScalacOptions ++ Seq("-java-output-version", "21"),
    // Source-compatible default-valued field additions required for P0 consensus
    // safety fixes (PreCandidate.votedFor, ReadIndex/LeaseRead heartbeatRound &
    // minRound correlation, and UnstableLog snapshot overlay flag).
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.KernelState.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.KernelState.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.KernelState.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.UnstableLog.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.UnstableLog.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.kernel.UnstableLog.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesRequest.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesRequest.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesRequest.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesResponse.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesResponse.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.message.RaftMessage#AppendEntriesResponse.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#Leader.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#Leader.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#Leader.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#PreCandidate.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#PreCandidate.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.NodeState#PreCandidate.apply"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.PendingRead.this"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.PendingRead.copy"
      ),
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.state.PendingRead.apply"
      )
    ),
    libraryDependencies ++= commonTestDependencies
  )

lazy val runtime = project
  .in(file("modules/runtime"))
  .dependsOn(core)
  .settings(publishSettings, strictMainSettings, mimaSettings)
  .settings(
    name := "raft-runtime",
    Test / parallelExecution := false,
    scalacOptions ++= commonScalacOptions ++ Seq("-java-output-version", "21"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.typelevel" %% "log4cats-core" % log4catsVersion
    ) ++ effectTestDependencies
  )

lazy val storageMmap = project
  .in(file("modules/storage-mmap"))
  .dependsOn(runtime)
  .settings(publishSettings, strictMainSettings, mimaSettings)
  .settings(
    name := "raft-storage-mmap",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= effectTestDependencies
  )

lazy val transportGrpc = project
  .in(file("modules/transport-grpc"))
  .dependsOn(runtime)
  .settings(publishSettings, strictMainSettings, mimaSettings)
  .settings(
    name := "raft-transport-grpc",
    Test / parallelExecution := false,
    scalacOptions ++= commonScalacOptions,
    coverageExcludedFiles := ".*src_managed.*",
    // Wire-compatible proto3 optional field additions (heartbeat_round) on
    // ScalaPB-generated classes in src_managed.
    mimaBinaryIssueFilters ++= Seq(
      ProblemFilters.exclude[DirectMissingMethodProblem](
        "raft.transport.grpc.proto.*"
      ),
      ProblemFilters.exclude[IncompatibleResultTypeProblem](
        "raft.transport.grpc.proto.*"
      )
    ),
    Compile / PB.targets := Seq(
      scalapb.gen(
        grpc = true,
        flatPackage = true
      ) -> (Compile / sourceManaged).value / "scalapb"
    ),
    libraryDependencies ++= Seq(
      "io.grpc" % "grpc-netty-shaded" % grpcVersion,
      "io.grpc" % "grpc-protobuf" % grpcVersion,
      "io.grpc" % "grpc-stub" % grpcVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    ) ++ effectTestDependencies
  )

lazy val jepsen = project
  .in(file("modules/jepsen"))
  .dependsOn(runtime)
  .settings(noMimaSettings)
  .settings(
    name := "raft-jepsen",
    publish / skip := true,
    coverageEnabled := false,
    scalacOptions ++= commonScalacOptions,
    Test / test / testOptions += Tests
      .Argument(TestFrameworks.ScalaTest, "-l", "Jepsen"),
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % scalatestVersion,
      "org.typelevel" %% "cats-effect-testing-scalatest" % catsEffectTestingVersion % Test,
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion % Test,
      "ch.qos.logback" % "logback-classic" % logbackVersion % Test
    )
  )

lazy val examples = project
  .in(file("modules/examples"))
  .dependsOn(core, runtime, storageMmap, transportGrpc)
  .settings(noMimaSettings)
  .settings(
    name := "raft-examples",
    publish / skip := true,
    coverageEnabled := false,
    Test / parallelExecution := false,
    Compile / run / fork := true,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-classic" % logbackVersion
    ) ++ effectTestDependencies
  )

lazy val itest = project
  .in(file("modules/itest"))
  .dependsOn(core, runtime, storageMmap, transportGrpc, jepsen)
  .settings(noMimaSettings)
  .settings(
    name := "raft-itest",
    publish / skip := true,
    coverageEnabled := false,
    Test / fork := true,
    Test / javaOptions += "--enable-native-access=ALL-UNNAMED",
    Test / parallelExecution := false,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= effectTestDependencies
  )

lazy val root = project
  .in(file("."))
  .aggregate(core, runtime, storageMmap, transportGrpc, jepsen, examples, itest)
  .settings(noMimaSettings)
  .settings(
    name := "raft-root",
    publish / skip := true,
    coverageEnabled := false
  )

addCommandAlias(
  "jepsenSmoke",
  "jepsen/testOnly raft.jepsen.checker.LinearizabilityCheckerSpec raft.jepsen.JepsenSpec"
)
addCommandAlias(
  "jepsenStress",
  "jepsen/testOnly raft.jepsen.JepsenStressSpec"
)
