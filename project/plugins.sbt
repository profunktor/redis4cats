resolvers += Classpaths.sbtPluginReleases

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.typesafe"   % "sbt-mima-plugin" % "1.1.6")
addSbtPlugin("com.github.sbt" % "sbt-ci-release"  % "1.11.2")
addSbtPlugin("org.typelevel"  % "sbt-tpolecat"    % "0.5.7")
addSbtPlugin("com.github.sbt" % "sbt-header"      % "5.11.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"    % "2.6.1")
addSbtPlugin("com.47deg"      % "sbt-microsites"  % "1.4.4")
addSbtPlugin("org.scalameta"  % "sbt-mdoc"        % "2.9.0")
addSbtPlugin("com.github.sbt" % "sbt-site"        % "1.7.0")
addSbtPlugin("com.github.sbt" % "sbt-unidoc"      % "0.6.1")
addSbtPlugin("com.scalapenos" % "sbt-prompt"      % "2.0.0")
