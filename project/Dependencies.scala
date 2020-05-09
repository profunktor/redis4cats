import sbt._

object Dependencies {

  object V {
    val cats       = "2.1.1"
    val catsEffect = "2.1.3"
    val fs2        = "2.3.0"
    val log4cats   = "1.0.1"

    val lettuce = "5.3.0.RELEASE"
    val logback = "1.2.3"

    val betterMonadicFor = "0.3.1"
    val contextApplied   = "0.1.4"
    val kindProjector    = "0.11.0"

    val scalaCheck = "1.14.3"
    val scalaTest  = "3.1.1"
  }

  object Libraries {
    def cats(artifact: String): ModuleID     = "org.typelevel"     %% s"cats-$artifact"     % V.cats
    def log4cats(artifact: String): ModuleID = "io.chrisdavenport" %% s"log4cats-$artifact" % V.log4cats

    val catsEffect = "org.typelevel" %% "cats-effect" % V.catsEffect
    val fs2Core    = "co.fs2"        %% "fs2-core"    % V.fs2

    val log4CatsCore  = log4cats("core")
    val log4CatsSlf4j = log4cats("slf4j")

    val redisClient = "io.lettuce"     % "lettuce-core"    % V.lettuce
    val logback     = "ch.qos.logback" % "logback-classic" % V.logback

    // Scala test libraries
    val catsLaws      = cats("core")
    val catsTestKit   = cats("testkit")
    val catsTestKitST = "org.typelevel" %% "cats-testkit-scalatest" % "1.0.1"

    val scalaTest  = "org.scalatest"  %% "scalatest"  % V.scalaTest
    val scalaCheck = "org.scalacheck" %% "scalacheck" % V.scalaCheck
  }

  object CompilerPlugins {
    val betterMonadicFor = compilerPlugin("com.olegpy"     %% "better-monadic-for" % V.betterMonadicFor)
    val contextApplied   = compilerPlugin("org.augustjune" %% "context-applied"    % V.contextApplied)
    val kindProjector = compilerPlugin(
      "org.typelevel" % "kind-projector" % V.kindProjector cross CrossVersion.full
    )
  }

}
