import Dependencies._

ThisBuild / scalaVersion     := "2.13.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "temporal-scala-zio-poc",
    libraryDependencies ++= Seq(
      // ZIO
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-logging" % "2.1.16",
      "dev.zio" %% "zio-logging-slf4j" % "2.1.16",

      // Temporal
      "io.temporal" % "temporal-sdk" % temporalVersion,
      "io.temporal" % "temporal-testing" % temporalVersion % Test,

      // JSON serialization
      "dev.zio" %% "zio-json" % "0.6.2",

      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",

      // Testing
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),

    // Java options for Temporal
    fork := true,
    javaOptions ++= Seq(
      "-Xms512m",
      "-Xmx2g"
    )
  )
