{ jdk ? "jdk11" }:

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
    name   = "NixOS-unstable-08-06-2020";
    url    = "https://github.com/NixOS/nixpkgs-channels/archive/dcb64ea42e6.tar.gz";
    sha256 = "0i77sgs0gic6pwbkvk9lbpfshgizdrqyh18law2ji1409azc09w0";
  };

  pkgs = import nixpkgs { inherit config; };
in
  pkgs.mkShell {
    buildInputs = with pkgs; [
      gnupg
      jekyll
      java
      sbt
    ];
  }
