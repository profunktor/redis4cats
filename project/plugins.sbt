ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.github.sbt"    % "sbt-ci-release" % "1.5.10")
addSbtPlugin("org.typelevel"     % "sbt-tpolecat"   % "0.5.0")
addSbtPlugin("de.heikoseeberger" % "sbt-header"     % "5.10.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"   % "2.5.2")
addSbtPlugin("com.47deg"         % "sbt-microsites" % "1.3.4")
addSbtPlugin("org.scalameta"     % "sbt-mdoc"       % "2.5.2")
addSbtPlugin("com.scalapenos"    % "sbt-prompt"     % "1.0.2")
addSbtPlugin("com.typesafe.sbt"  % "sbt-site"       % "1.4.1")
addSbtPlugin("com.github.sbt"    % "sbt-unidoc"     % "0.5.0")
