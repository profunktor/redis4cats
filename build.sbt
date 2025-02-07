import com.scalapenos.sbt.prompt.SbtPrompt.autoImport._
import com.scalapenos.sbt.prompt._
import Dependencies._
import microsites.ExtraMdFileConfig

ThisBuild / scalaVersion := "2.13.15"
ThisBuild / crossScalaVersions := Seq("2.12.20", "2.13.15", "3.3.4")
ThisBuild / evictionErrorLevel := Level.Info
ThisBuild / mimaBaseVersion := "1.7.0"
Test / parallelExecution := false

promptTheme := PromptTheme(
  List(
    text("[sbt] ", fg(105)),
    text(_ => "redis4cats", fg(15)).padRight(" λ ")
  )
)

// publishing
ThisBuild / organization := "dev.profunktor"
ThisBuild / homepage := Some(url("https://redis4cats.profunktor.dev/"))
ThisBuild / licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
ThisBuild / developers := List(
  Developer(
    "gvolpe",
    "Gabriel Volpe",
    "volpegabriel@gmail.com",
    url("https://gvolpe.com")
  )
)

Global / onChangedBuildSource := ReloadOnSourceChanges

def pred[A](p: Boolean, t: => Seq[A], f: => Seq[A]): Seq[A] =
  if (p) t else f

def getVersion(strVersion: String): Option[(Long, Long)] = CrossVersion.partialVersion(strVersion)

val commonSettings = Seq(
  organizationName := "Redis client for Cats Effect & Fs2",
  startYear := Some(2018),
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.ALv2("2018-2021", "ProfunKtor")),
  testFrameworks += new TestFramework("munit.Framework"),
  libraryDependencies ++= Seq(
        Libraries.catsEffectKernel,
        Libraries.redisClient,
        Libraries.catsEffect      % Test,
        Libraries.catsLaws        % Test,
        Libraries.catsTestKit     % Test,
        Libraries.munitCore       % Test,
        Libraries.munitScalacheck % Test
      ) ++ pred(scalaVersion.value.startsWith("3"), t = Seq.empty, f = Seq(CompilerPlugins.kindProjector)),
  resolvers += "Apache public" at "https://repository.apache.org/content/groups/public/",
  scalacOptions ++= pred(
        getVersion(scalaVersion.value) == Some(2, 12),
        t = Seq("-Xmax-classfile-name", "80"),
        f = Seq.empty
      ),
  scalacOptions ++= pred(
        scalaVersion.value.startsWith("3"),
        t = Seq("-source:3.0-migration"),
        f = Seq("-Wconf:any:wv")
      ),
  Compile / doc / sources := (Compile / doc / sources).value,
  Compile / unmanagedSourceDirectories ++= {
      getVersion(scalaVersion.value) match {
        case Some((2, 12)) => Seq("scala-2.12", "scala-2")
        case Some((2, 13)) => Seq("scala-2.13+", "scala-2")
        case _             => Seq("scala-2.13+", "scala-3")
      }
    }.map(baseDirectory.value / "src" / "main" / _),
  Compile / doc / scalacOptions ++= Seq("-groups", "-implicits"),
  autoAPIMappings := true,
  scalafmtOnCompile := true,
  scmInfo := Some(
        ScmInfo(url("https://github.com/profunktor/redis4cats"), "scm:git:git@github.com:profunktor/redis4cats.git")
      )
)

lazy val noPublish = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false,
  publish / skip := true
)

lazy val `redis4cats-root` = project
  .in(file("."))
  .aggregate(
    `redis4cats-core`,
    `redis4cats-effects`,
    `redis4cats-streams`,
    `redis4cats-log4cats`,
    examples,
    tests,
    microsite
  )
  .settings(noPublish)
  .settings(
    ScalaUnidoc / siteSubdirName := "api",
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, ScalaUnidoc / siteSubdirName)
  )
  .enablePlugins(ScalaUnidocPlugin)

lazy val `redis4cats-core` = project
  .in(file("modules/core"))
  .settings(commonSettings: _*)
  .settings(libraryDependencies += Libraries.literally)
  .settings(
    libraryDependencies ++=
        pred(scalaVersion.value.startsWith("3"), t = Seq.empty, f = Seq(Libraries.reflect(scalaVersion.value)))
  )
  .settings(
    isMimaEnabled := true,
    mimaPreviousArtifacts ~= { prev =>
      prev.filter(artifact => VersionNumber(artifact.revision).matchesSemVer(SemanticSelector(">=1.7.1")))
    }
  )
  .settings(Test / parallelExecution := false)
  .enablePlugins(AutomateHeaderPlugin)

lazy val `redis4cats-log4cats` = project
  .in(file("modules/log4cats"))
  .settings(commonSettings: _*)
  .settings(
    isMimaEnabled := true,
    mimaPreviousArtifacts ~= { prev =>
      prev.filter(artifact => VersionNumber(artifact.revision).matchesSemVer(SemanticSelector(">=1.4.3")))
    }
  )
  .settings(libraryDependencies += Libraries.log4CatsCore)
  .settings(Test / parallelExecution := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val `redis4cats-effects` = project
  .in(file("modules/effects"))
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies += Libraries.keyPool
  )
  .settings(
    isMimaEnabled := true,
    mimaPreviousArtifacts ~= { prev =>
      prev.filter(artifact => VersionNumber(artifact.revision).matchesSemVer(SemanticSelector(">=1.7.2")))
    }
  )
  .settings(Test / parallelExecution := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val `redis4cats-streams` = project
  .in(file("modules/streams"))
  .settings(commonSettings: _*)
  .settings(
    isMimaEnabled := true,
    mimaPreviousArtifacts ~= { prev =>
      prev.filter(artifact => VersionNumber(artifact.revision).matchesSemVer(SemanticSelector(">=1.7.2")))
    }
  )
  .settings(libraryDependencies += Libraries.fs2Core)
  .settings(Test / parallelExecution := false)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)

lazy val examples = project
  .in(file("modules/examples"))
  .settings(commonSettings: _*)
  .settings(noPublish)
  .settings(
    libraryDependencies ++= Seq(
          Libraries.catsEffect,
          Libraries.circeCore,
          Libraries.circeGeneric,
          Libraries.circeParser,
          Libraries.log4CatsSlf4j,
          Libraries.logback % "runtime"
        )
  )
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-log4cats`)
  .dependsOn(`redis4cats-effects`)
  .dependsOn(`redis4cats-streams`)

lazy val tests = project
  .in(file("modules/tests"))
  .settings(commonSettings: _*)
  .settings(Test / parallelExecution := false)
  .settings(noPublish)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(`redis4cats-core`)
  .dependsOn(`redis4cats-effects`)
  .dependsOn(`redis4cats-streams`)

lazy val microsite = project
  .in(file("site"))
  .enablePlugins(MicrositesPlugin, SiteScaladocPlugin, ScalaUnidocPlugin)
  .settings(commonSettings: _*)
  .settings(noPublish)
  .settings(
    micrositeName := "Redis4Cats",
    micrositeDescription := "Redis client for Cats Effect & Fs2",
    micrositeAuthor := "ProfunKtor",
    micrositeGithubOwner := "profunktor",
    micrositeGithubRepo := "redis4cats",
    micrositeDocumentationUrl := "/api",
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
    micrositeExtraMdFilesOutput := (Compile / resourceManaged).value / "jekyll",
    micrositeGitterChannel := true,
    micrositeGitterChannelUrl := "profunktor-dev/redis4cats",
    micrositePushSiteWith := GitHub4s,
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    scalacOptions --= Seq(
          "-Werror",
          "-Xfatal-warnings",
          "-Ywarn-unused-import",
          "-Ywarn-numeric-widen",
          "-Ywarn-dead-code",
          "-deprecation",
          "-Xlint:-missing-interpolator,_",
          "-Wconf:any:wv"
        ),
    addMappingsToSiteDir(ScalaUnidoc / packageDoc / mappings, micrositeDocumentationUrl),
    ScalaUnidoc / unidoc / scalacOptions ++= Seq(
          "-doc-source-url",
          scmInfo.value.get.browseUrl + "/tree/master€{FILE_PATH}.scala",
          "-sourcepath",
          (LocalRootProject / baseDirectory).value.getAbsolutePath,
          "-doc-root-content",
          ((Compile / resourceDirectory).value / "rootdoc.txt").getAbsolutePath
        ),
    ScalaUnidoc / unidoc / unidocProjectFilter := inAnyProject -- inProjects(examples)
  )
  .dependsOn(`redis4cats-effects`, `redis4cats-streams`, examples)

// CI build
addCommandAlias("buildDoc", ";++2.13.12;mdoc;doc")
addCommandAlias("buildRedis4Cats", ";+test;buildDoc")
addCommandAlias("buildSite", ";doc;makeMicrosite")
addCommandAlias("publishSite", ";doc;publishMicrosite")
