import sbt._

object Dependencies {

  object Versions {
    val cats       = "1.6.0"
    val catsEffect = "1.3.0"
    val fs2        = "1.0.4"
    val lettuce    = "5.1.6.RELEASE"
    val log4cats   = "0.3.0"

    val betterMonadicFor = "0.3.0"
    val kindProjector    = "0.9.10"

    val scalaTest  = "3.0.7"
    val scalaCheck = "1.14.0"
  }

  object Libraries {
    def log4cats(artifact: String): ModuleID = "io.chrisdavenport" %% s"log4cats-$artifact" % Versions.log4cats

    lazy val redisClient = "io.lettuce"    % "lettuce-core" % Versions.lettuce
    lazy val catsEffect  = "org.typelevel" %% "cats-effect" % Versions.catsEffect
    lazy val fs2Core     = "co.fs2"        %% "fs2-core"    % Versions.fs2

    lazy val log4CatsCore  = log4cats("core")
    lazy val log4CatsSlf4j = log4cats("slf4j")

    // Compiler plugins
    lazy val betterMonadicFor = "com.olegpy"     %% "better-monadic-for" % Versions.betterMonadicFor
    lazy val kindProjector    = "org.spire-math" %% "kind-projector"     % Versions.kindProjector

    // Scala test libraries
    lazy val catsLaws    = "org.typelevel"  %% "cats-core"    % Versions.cats
    lazy val catsTestKit = "org.typelevel"  %% "cats-testkit" % Versions.cats
    lazy val scalaTest   = "org.scalatest"  %% "scalatest"    % Versions.scalaTest
    lazy val scalaCheck  = "org.scalacheck" %% "scalacheck"   % Versions.scalaCheck
  }

}
