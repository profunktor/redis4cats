import sbt._

object Dependencies {

  object V {
    val cats       = "2.4.2"
    val catsEffect = "2.4.1"
    val circe      = "0.14.0-M4"
    val fs2        = "2.5.3"
    val log4cats   = "1.2.1"

    val lettuce = "6.0.3.RELEASE"
    val logback = "1.2.3"

    val kindProjector = "0.11.3"

    val munit = "0.7.23"
  }

  object Libraries {
    def cats(artifact: String): ModuleID     = "org.typelevel" %% s"cats-$artifact"     % V.cats
    def log4cats(artifact: String): ModuleID = "org.typelevel" %% s"log4cats-$artifact" % V.log4cats

    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val fs2Core    = "co.fs2"        %% "fs2-core"    % V.fs2

    val log4CatsCore = log4cats("core")

    val redisClient = "io.lettuce" % "lettuce-core" % V.lettuce

    // Examples libraries
    val circeCore     = "io.circe" %% "circe-core" % V.circe
    val circeGeneric  = "io.circe" %% "circe-generic" % V.circe
    val circeParser   = "io.circe" %% "circe-parser" % V.circe
    val log4CatsSlf4j = log4cats("slf4j")
    val logback       = "ch.qos.logback" % "logback-classic" % V.logback

    // Testing libraries
    val catsLaws        = cats("core")
    val catsTestKit     = cats("testkit")
    val munitCore       = "org.scalameta" %% "munit" % V.munit
    val munitScalacheck = "org.scalameta" %% "munit-scalacheck" % V.munit
  }

  object CompilerPlugins {
    val kindProjector = compilerPlugin(
      "org.typelevel" % "kind-projector" % V.kindProjector cross CrossVersion.full
    )
  }

}
