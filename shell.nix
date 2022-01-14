{ jdk ? "jdk17" }:

let
  java = pkgs.${jdk};

  config = {
    packageOverrides = pkgs: rec {
      sbt = pkgs.sbt.overrideAttrs (
        old: rec {
          patchPhase = ''
            echo -java-home ${java} >> conf/sbtopts
          '';
        }
      );
    };
  };

  nixpkgs = fetchTarball {
    name   = "nixos-unstable-2021-10-27";
    url    = "https://github.com/NixOS/nixpkgs/archive/a4bf44345706.tar.gz";
    sha256 = "0zag9yfqsf544vrfccfvn5yjagizqf69adza8fpmsmn5ll8jw8gw";
  };

  pkgs = import nixpkgs { inherit config; };
in
pkgs.mkShell {
  name = "scala-shell";

  buildInputs = with pkgs; [
    gnupg
    jekyll
    java
    sbt
  ];
}
