import sbt._

object Dependencies {

  object Versions {
    val catsEffect = "1.0.0"
    val fs2        = "1.0.0-M5"
    val lettuce    = "5.1.0.M1"
    val scribe     = "2.6.0"

    val betterMonadicFor  = "0.2.4"
    val kindProjector     = "0.9.7"

    val scalaTest  = "3.0.5"
    val scalaCheck = "1.13.4"
  }

  object Libraries {
    lazy val redisClient  = "io.lettuce"    % "lettuce-core" % Versions.lettuce
    lazy val catsEffect   = "org.typelevel" %% "cats-effect" % Versions.catsEffect
    lazy val fs2Core      = "co.fs2"        %% "fs2-core"    % Versions.fs2
    lazy val scribe       = "com.outr"      %% "scribe"      % Versions.scribe

    // Compiler plugins
    lazy val betterMonadicFor = "com.olegpy"     %% "better-monadic-for"  % Versions.betterMonadicFor
    lazy val kindProjector    = "org.spire-math" %% "kind-projector"      % Versions.kindProjector

    // Scala test libraries
    lazy val scalaTest  = "org.scalatest"  %% "scalatest"  % Versions.scalaTest  % "test"
    lazy val scalaCheck = "org.scalacheck" %% "scalacheck" % Versions.scalaCheck % "test"
  }

}
