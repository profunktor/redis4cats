import com.scalapenos.sbt.prompt.SbtPrompt.autoImport._
import com.scalapenos.sbt.prompt._
import Dependencies._
import microsites.ExtraMdFileConfig

name := """fs2-redis-root"""

organization in ThisBuild := "com.github.gvolpe"

crossScalaVersions in ThisBuild := Seq("2.12.7")

sonatypeProfileName := "com.github.gvolpe"

promptTheme := PromptTheme(List(
  text("[SBT] ", fg(136)),
  text(_ => "fs2-redis", fg(64)).padRight(" λ ")
 ))

val commonSettings = Seq(
  organizationName := "Fs2 Redis",
  startYear := Some(2018),
  licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  homepage := Some(url("https://github.com/gvolpe/fs2-redis")),
  headerLicense := Some(HeaderLicense.ALv2("2018-2019", "Fs2 Redis")),
  libraryDependencies ++= Seq(
    compilerPlugin(Libraries.kindProjector cross CrossVersion.binary),
    compilerPlugin(Libraries.betterMonadicFor),
    Libraries.redisClient,
    Libraries.catsEffect,
    Libraries.scribe,
    Libraries.scalaTest % Test,
    Libraries.scalaCheck % Test
  ),
  resolvers += "Apache public" at "https://repository.apache.org/content/groups/public/",
  scalacOptions ++= Seq(
    "-Xmax-classfile-name", "80",
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-feature",
    "-Ypartial-unification",
    "-language:existentials",
    "-language:higherKinds"
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
          <url>http://github.com/gvolpe</url>
        </developer>
      </developers>
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  skip in publish := true
)

lazy val `fs2-redis-root` = project.in(file("."))
  .aggregate(`fs2-redis-core`, `fs2-redis-effects`, `fs2-redis-streams`, examples, `fs2-redis-test-support`, tests, microsite)
  .settings(noPublish)

lazy val `fs2-redis-core` = project.in(file("core"))
  .settings(commonSettings: _*)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `fs2-redis-effects` = project.in(file("effects"))
  .settings(commonSettings: _*)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis-core`)

lazy val `fs2-redis-streams` = project.in(file("streams"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies += Libraries.fs2Core)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis-core`)

lazy val examples = project.in(file("examples"))
  .settings(commonSettings: _*)
  .settings(noPublish)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis-effects`)
  .dependsOn(`fs2-redis-streams`)

lazy val `fs2-redis-test-support` = project.in(file("test-support"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      Libraries.scalaTest,
      Libraries.scalaCheck
    )
  )
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis-core`)
  .dependsOn(`fs2-redis-effects`)
  .dependsOn(`fs2-redis-streams`)

lazy val tests = project.in(file("tests"))
  .settings(commonSettings: _*)
  .settings(noPublish)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis-test-support` % Test)

lazy val microsite = project.in(file("site"))
  .enablePlugins(MicrositesPlugin)
  .settings(commonSettings: _*)
  .settings(noPublish)
  .settings(
    micrositeName := "Fs2 Redis",
    micrositeDescription := "Redis stream-based client",
    micrositeAuthor := "Gabriel Volpe",
    micrositeGithubOwner := "gvolpe",
    micrositeGithubRepo := "fs2-redis",
    micrositeBaseUrl := "/fs2-redis",
    micrositeExtraMdFiles := Map(
      file("README.md") -> ExtraMdFileConfig(
        "index.md",
        "home",
        Map("title" -> "Home", "position" -> "0")
      )
    ),
    micrositeGitterChannel := true,
    micrositeGitterChannelUrl := "fs2-redis/fs2-redis",
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
  .dependsOn(`fs2-redis-effects`, `fs2-redis-streams`, `examples`)

// CI build
addCommandAlias("buildFs2Redis", ";clean;+test;tut")
