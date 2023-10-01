{
  description = "Scala development shell";

  inputs = {
    nixpkgs.url = github:nixos/nixpkgs/nixpkgs-unstable;
    flake-utils.url = github:numtide/flake-utils;
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Setting SBT_OPTS because of this bug: https://github.com/sbt/sbt-site/issues/169
        sbt-overlay = self: super: {
          sbt = super.sbt.overrideAttrs (
            old: {
              nativeBuildInputs = old.nativeBuildInputs or [ ] ++ [ super.makeWrapper ];
              postInstall = ''
                wrapProgram $out/bin/sbt --suffix SBT_OPTS : '--add-opens java.base/java.lang=ALL-UNNAMED'
              '';
            }
          );
        };
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ sbt-overlay ];
        };
        jdk = pkgs.jdk19_headless;
      in
      {
        devShell = pkgs.mkShell {
          name = "scala-dev-shell";
          buildInputs = [
            jdk
            pkgs.gnupg
            pkgs.jekyll
            pkgs.sbt
          ];

          shellHook = ''
            JAVA_HOME="${jdk}"
          '';
        };
      });
}
