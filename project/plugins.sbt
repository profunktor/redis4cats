resolvers += Classpaths.sbtPluginReleases
resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.9")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.8.1")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.0")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.3.0")

addSbtPlugin("com.lucidchart" %  "sbt-scalafmt" % "1.16")

addSbtPlugin("com.47deg"  % "sbt-microsites" % "0.9.7")

addSbtPlugin("org.tpolecat" % "tut-plugin" % "0.6.13")

addSbtPlugin("com.scalapenos" % "sbt-prompt" % "1.0.2")
