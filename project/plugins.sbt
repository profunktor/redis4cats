resolvers += Classpaths.sbtPluginReleases
resolvers += "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("com.github.sbt"            % "sbt-ci-release" % "1.5.10")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat"   % "0.1.22")
addSbtPlugin("de.heikoseeberger"         % "sbt-header"     % "5.6.5")
addSbtPlugin("org.scalameta"             % "sbt-scalafmt"   % "2.4.6")
addSbtPlugin("com.47deg"                 % "sbt-microsites" % "1.3.4")
addSbtPlugin("org.scalameta"             % "sbt-mdoc"       % "2.3.0")
addSbtPlugin("com.scalapenos"            % "sbt-prompt"     % "1.0.2")
addSbtPlugin("com.typesafe.sbt"          % "sbt-site"       % "1.4.1")
addSbtPlugin("com.github.sbt"            % "sbt-unidoc"     % "0.5.0")
