import sbt._

object Dependencies {

  object Versions {
    val cats       = "2.1.0"
    val catsEffect = "2.1.0"
    val fs2        = "2.1.0"
    val log4cats   = "1.0.1"

    val lettuce    = "5.2.1.RELEASE"
    val logback    = "1.2.3"

    val betterMonadicFor = "0.3.1"
    val kindProjector    = "0.11.0"

    val scalaCheck = "1.14.3"
    val scalaTest  = "3.1.0"
  }

  object Libraries {
    def cats(artifact: String): ModuleID = "org.typelevel" %% s"cats-$artifact" % Versions.cats
    def log4cats(artifact: String): ModuleID = "io.chrisdavenport" %% s"log4cats-$artifact" % Versions.log4cats

    lazy val catsEffect  = "org.typelevel" %% "cats-effect" % Versions.catsEffect
    lazy val fs2Core     = "co.fs2"        %% "fs2-core"    % Versions.fs2

    lazy val log4CatsCore  = log4cats("core")
    lazy val log4CatsSlf4j = log4cats("slf4j")

    lazy val redisClient = "io.lettuce" % "lettuce-core" % Versions.lettuce
    lazy val logback = "ch.qos.logback" % "logback-classic" % Versions.logback

    // Compiler plugins
    lazy val betterMonadicFor = "com.olegpy"    %% "better-monadic-for" % Versions.betterMonadicFor
    lazy val kindProjector    = "org.typelevel" %% "kind-projector"     % Versions.kindProjector

    // Scala test libraries
    lazy val catsLaws      = cats("core")
    lazy val catsTestKit   = cats("testkit")
    lazy val catsTestKitST = "org.typelevel" %% "cats-testkit-scalatest" % "1.0.0-RC1"

    lazy val scalaTest   = "org.scalatest"  %% "scalatest"  % Versions.scalaTest
    lazy val scalaCheck  = "org.scalacheck" %% "scalacheck" % Versions.scalaCheck
  }

}
