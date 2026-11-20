val scala3Version = "3.3.9"

val catsEffectVersion = "3.7.1"
val fs2Version = "3.14.0"
val log4catsVersion = "2.8.0"
val logbackVersion = "1.6.7"
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
  .settings(publishSettings, strictMainSettings)
  .settings(
    name := "raft-core",
    scalacOptions ++= commonScalacOptions ++ Seq("-java-output-version", "21"),
    libraryDependencies ++= commonTestDependencies
  )

lazy val runtime = project
  .in(file("modules/runtime"))
  .dependsOn(core)
  .settings(publishSettings, strictMainSettings)
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
  .settings(publishSettings, strictMainSettings)
  .settings(
    name := "raft-storage-mmap",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= effectTestDependencies
  )

lazy val transportGrpc = project
  .in(file("modules/transport-grpc"))
  .dependsOn(runtime)
  .settings(publishSettings, strictMainSettings)
  .settings(
    name := "raft-transport-grpc",
    Test / parallelExecution := false,
    scalacOptions ++= commonScalacOptions,
    coverageExcludedFiles := ".*src_managed.*",
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
