addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.8")
addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.2.0")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.2.1")

libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.21"
