import sbt._

object Dependencies {

  object Versions {
    val catsEffect = "1.0.0-RC2"
    val fs2        = "1.0.0-M1"
    val lettuce    = "5.1.0.M1"

    val slf4j      = "1.7.25"
    val logback    = "1.1.3"

    val scalaTest  = "3.0.1"
    val scalaCheck = "1.13.4"
  }

  object Libraries {
    lazy val redisClient  = "io.lettuce"    % "lettuce-core" % Versions.lettuce
    lazy val catsEffect   = "org.typelevel" %% "cats-effect" % Versions.catsEffect
    lazy val fs2Core      = "co.fs2"        %% "fs2-core"    % Versions.fs2

    lazy val slf4j   = "org.slf4j"      % "slf4j-api"       % Versions.slf4j
    lazy val logback = "ch.qos.logback" % "logback-classic" % Versions.logback

    // Scala test libraries
    lazy val scalaTest  = "org.scalatest"  %% "scalatest"  % Versions.scalaTest  % "test"
    lazy val scalaCheck = "org.scalacheck" %% "scalacheck" % Versions.scalaCheck % "test"
  }

}
