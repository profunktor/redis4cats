import com.scalapenos.sbt.prompt.SbtPrompt.autoImport._
import com.scalapenos.sbt.prompt._
import Dependencies._
import microsites.ExtraMdFileConfig

name := """redis4cats-root"""

organization in ThisBuild := "dev.profunktor"

crossScalaVersions in ThisBuild := Seq("2.12.8", "2.13.0")

sonatypeProfileName := "dev.profunktor"

promptTheme := PromptTheme(List(
  text("[sbt] ", fg(105)),
  text(_ => "redis4cats", fg(15)).padRight(" λ ")
 ))

def cond[A](condition: Boolean, t: => Seq[A], f: => Seq[A]): Seq[A] =
  if (condition) {
    t
  } else {
    f
  }

def version(strVersion: String): Option[(Long, Long)] = CrossVersion.partialVersion(strVersion)

val commonSettings = Seq(
  organizationName := "Redis client for Cats Effect & Fs2",
  startYear := Some(2018),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  homepage := Some(url("https://redis4cats.profunktor.dev/")),
  headerLicense := Some(HeaderLicense.ALv2("2018-2019", "ProfunKtor")),
  libraryDependencies ++= Seq(
    compilerPlugin(Libraries.kindProjector cross CrossVersion.binary),
    compilerPlugin(Libraries.betterMonadicFor),
    Libraries.redisClient,
    Libraries.scalaCheck % Test
  ) ++ cond(
    version(scalaVersion.value) == Some(2, 12),
    t = Seq(
      Libraries212.catsEffect,
      Libraries212.scalaTest % Test,
      Libraries212.catsLaws % Test,
      Libraries212.catsTestKit % Test,
    ),
    f = Seq(
      Libraries213.catsEffect,
      Libraries213.scalaTest % Test,
      Libraries213.catsLaws % Test,
      Libraries213.catsTestKit % Test,
    )),
  resolvers += "Apache public" at "https://repository.apache.org/content/groups/public/",
  scalacOptions ++= cond(
    version(scalaVersion.value) == Some(2, 12),
    t = Seq("-Xmax-classfile-name", "80"),
    f = Seq.empty
  ),
  scalafmtOnCompile := true,
  publishTo := {
    val sonatype = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at sonatype + "content/repositories/snapshots")
    else
      Some("releases" at sonatype + "service/local/staging/deploy/maven2")
  },
  publishMavenStyle := true,
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  pomExtra :=
      <developers>
        <developer>
          <id>gvolpe</id>
          <name>Gabriel Volpe</name>
          <url>https://github.com/gvolpe</url>
        </developer>
      </developers>
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  skip in publish := true
)

lazy val `redis4cats-root` = project.in(file("."))
  .aggregate(`redis4cats-core`, `redis4cats-effects`, `redis4cats-streams`, `redis4cats-log4cats`, examples, `redis4cats-test-support`, tests, microsite)
  .settings(noPublish)

lazy val `redis4cats-core` = project.in(file("modules/core"))
  .settings(commonSettings: _*)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `redis4cats-log4cats` = project.in(file("modules/log4cats"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++=
    cond(
      version(scalaVersion.value) == Some(2, 12),
      t = Seq(Libraries212.log4CatsCore),
      f = Seq(Libraries213.log4CatsCore)
    )
  )
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val `redis4cats-effects` = project.in(file("modules/effects"))
  .settings(commonSettings: _*)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val `redis4cats-streams` = project.in(file("modules/streams"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++=
    cond(
      version(scalaVersion.value) == Some(2, 12),
      t = Seq(Libraries212.fs2Core),
      f = Seq(Libraries213.fs2Core)
    )
  )
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val examples = project.in(file("modules/examples"))
  .settings(commonSettings: _*)
  .settings(noPublish)
  .settings(libraryDependencies ++=
    cond(
      version(scalaVersion.value) == Some(2, 12),
      t = Seq(Libraries212.log4CatsSlf4j),
      f = Seq(Libraries213.log4CatsSlf4j)
    )
  )
  .settings(libraryDependencies += Libraries.logback % "runtime")
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-log4cats`)
  .dependsOn(`redis4cats-effects`)
  .dependsOn(`redis4cats-streams`)

lazy val `redis4cats-test-support` = project.in(file("modules/test-support"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++=
      cond(
        version(scalaVersion.value) == Some(2, 12),
        t = Seq(Libraries212.scalaTest),
        f = Seq(Libraries213.scalaTest)
      )
  )
  .settings(libraryDependencies += Libraries.scalaCheck)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)
  .dependsOn(`redis4cats-effects`)
  .dependsOn(`redis4cats-streams`)

lazy val tests = project.in(file("modules/tests"))
  .settings(commonSettings: _*)
  .settings(noPublish)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-test-support` % Test)
  .dependsOn(`redis4cats-core`)

lazy val microsite = project.in(file("site"))
  .enablePlugins(MicrositesPlugin)
  .settings(commonSettings: _*)
  .settings(noPublish)
  .settings(
    micrositeName := "Redis4Cats",
    micrositeDescription := "Redis client for Cats Effect & Fs2",
    micrositeAuthor := "ProfunKtor",
    micrositeGithubOwner := "profunktor",
    micrositeGithubRepo := "redis4cats",
    micrositeBaseUrl := "",
    micrositeExtraMdFiles := Map(
      file("README.md") -> ExtraMdFileConfig(
        "index.md",
        "home",
        Map("title" -> "Home", "position" -> "0")
      ),
      file("CODE_OF_CONDUCT.md") -> ExtraMdFileConfig(
        "CODE_OF_CONDUCT.md",
        "page",
        Map("title" -> "Code of Conduct")
      )
    ),
    micrositeGitterChannel := true,
    micrositeGitterChannelUrl := "profunktor-dev/redis4cats",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    fork in tut := true,
    scalacOptions in Tut --= Seq(
      "-Xfatal-warnings",
      "-Ywarn-unused-import",
      "-Ywarn-numeric-widen",
      "-Ywarn-dead-code",
      "-Xlint:-missing-interpolator,_",
    )
  )
  .dependsOn(`redis4cats-effects`, `redis4cats-streams`, `examples`)

// CI build
addCommandAlias("buildRedis4Cats", ";clean;+test;tut")

