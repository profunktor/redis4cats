resolvers += Classpaths.sbtPluginReleases
resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.7")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.7")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.0")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.2.0")

addSbtPlugin("com.lucidchart" %  "sbt-scalafmt" % "1.16")

addSbtPlugin("com.47deg"  % "sbt-microsites" % "0.9.4")

addSbtPlugin("org.tpolecat" % "tut-plugin" % "0.6.12")

addSbtPlugin("com.scalapenos" % "sbt-prompt" % "1.0.2")
