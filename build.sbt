val scala3Version = "3.3.7"

lazy val root = project
  .in(file("."))
  .settings(
    name := "raft",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

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

    // Dependencies
    libraryDependencies ++= Seq(
      // Effect system
      "org.typelevel" %% "cats-effect" % "3.6.3",

      // Streaming
      "co.fs2" %% "fs2-core" % "3.12.2",

      // Logging
      "org.typelevel" %% "log4cats-slf4j" % "2.7.1",
      "ch.qos.logback" % "logback-classic" % "1.5.27" % Runtime,

      // Testing
      "org.scalatest" %% "scalatest" % "3.2.19" % Test,
      "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.7.0" % Test
    )
  )
