import com.scalapenos.sbt.prompt.SbtPrompt.autoImport._
import com.scalapenos.sbt.prompt._
import Dependencies._
import microsites.ExtraMdFileConfig

name := """fs2-redis-root"""

organization in ThisBuild := "com.github.gvolpe"

version in ThisBuild := "0.1-SNAPSHOT"

crossScalaVersions in ThisBuild := Seq("2.12.6")

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
  addCompilerPlugin("org.spire-math" % "kind-projector" % "0.9.5" cross CrossVersion.binary),
  libraryDependencies ++= Seq(
    Libraries.redisClient,
    Libraries.catsEffect,
    Libraries.fs2Core,
    Libraries.slf4j,
    Libraries.scalaTest,
    Libraries.scalaCheck
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
  //coverageExcludedPackages := "com\\.github\\.gvolpe\\.fs2rabbit\\.examples.*;com\\.github\\.gvolpe\\.fs2rabbit\\.util.*;.*QueueName*;.*RoutingKey*;.*ExchangeName*;.*DeliveryTag*;.*AMQPClientStream*;.*ConnectionStream*;",
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

val CoreDependencies: Seq[ModuleID] = Seq(
  Libraries.logback % "test"
)

val ExamplesDependencies: Seq[ModuleID] = Seq(
  Libraries.logback % "runtime"
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  skip in publish := true
)

lazy val `fs2-redis-root` = project.in(file("."))
  .aggregate(`fs2-redis`, examples, microsite)
  .settings(noPublish)

lazy val `fs2-redis` = project.in(file("core"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= CoreDependencies)
  .settings(parallelExecution in Test := false)
  .enablePlugins(AutomateHeaderPlugin)

lazy val examples = project.in(file("examples"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies ++= ExamplesDependencies)
  .settings(noPublish)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`fs2-redis`)

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
    micrositeGitterChannel := false,
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
  .dependsOn(`fs2-redis`, `examples`)

// CI build
addCommandAlias("buildFs2Redis", ";clean;+coverage;+test;+coverageReport;+coverageAggregate;tut")
