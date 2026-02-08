val scala3Version = "3.3.7"

lazy val root = project
  .in(file("."))
  .settings(
    name := "raft",
    organization := "com.github.bmscomp",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    publishMavenStyle := true,
    publishTo := Some(
      "GitHub Packages" at s"https://maven.pkg.github.com/bmscomp/raft"
    ),
    credentials += Credentials(
      "GitHub Package Registry",
      "maven.pkg.github.com",
      "bmscomp",
      sys.env.getOrElse("GITHUB_TOKEN", "")
    ),

    // Fork for running examples (avoids Cats Effect warnings)
    Compile / run / fork := true,

    // Compiler options
    scalacOptions ++= Seq(
      "-encoding",
      "utf8",
      "-deprecation",
      "-feature",
      "-unchecked"
    ),

    // Exclude Jepsen-tagged tests from `sbt test`
    Test / testOptions += Tests
      .Argument(TestFrameworks.ScalaTest, "-l", "Jepsen"),

    // Dependencies
    libraryDependencies ++= Seq(
      // Effect system
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Streaming
      "co.fs2" %% "fs2-core" % "3.12.2",
      "co.fs2" %% "fs2-io" % "3.12.2",

      // Logging
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "ch.qos.logback" % "logback-classic" % "1.5.27" % Runtime,

      // gRPC
      "io.grpc" % "grpc-netty-shaded" % "1.72.0",
      "io.grpc" % "grpc-protobuf" % "1.72.0",
      "io.grpc" % "grpc-stub" % "1.72.0",
      "com.thesamet.scalapb" %% "scalapb-runtime" % "0.11.17",
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % "0.11.17",

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.7.0" % Test
    )
  )

// Run only Jepsen verification tests: `sbt jepsen`
addCommandAlias(
  "jepsen",
  """set Test / testOptions := Seq(Tests.Argument(TestFrameworks.ScalaTest, "-n", "Jepsen")); test"""
)
