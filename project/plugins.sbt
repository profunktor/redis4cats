ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("com.typesafe"              % "sbt-mima-plugin" % "1.1.3")
addSbtPlugin("com.github.sbt"            % "sbt-ci-release"  % "1.5.10")
addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat"    % "0.4.4")
addSbtPlugin("de.heikoseeberger"         % "sbt-header"      % "5.10.0")
addSbtPlugin("org.scalameta"             % "sbt-scalafmt"    % "2.5.2")
addSbtPlugin("com.47deg"                 % "sbt-microsites"  % "1.3.4")
addSbtPlugin("org.scalameta"             % "sbt-mdoc"        % "2.5.3")
addSbtPlugin("com.scalapenos"            % "sbt-prompt"      % "1.0.2")
addSbtPlugin("com.github.sbt"            % "sbt-site"        % "1.7.0")
addSbtPlugin("com.github.sbt"            % "sbt-unidoc"      % "0.5.0")
