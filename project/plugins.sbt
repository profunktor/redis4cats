resolvers += Classpaths.sbtPluginReleases
resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.2")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")

addSbtPlugin("com.lucidchart" %  "sbt-scalafmt" % "1.15")

addSbtPlugin("com.47deg"  % "sbt-microsites" % "0.7.23")

addSbtPlugin("org.tpolecat" % "tut-plugin" % "0.6.8")

addSbtPlugin("com.scalapenos" % "sbt-prompt" % "1.0.2")
