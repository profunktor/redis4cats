{ jdk ? "jdk15" }:

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
    name   = "nixos-unstable-2021-02-21";
    url    = "https://github.com/NixOS/nixpkgs/archive/9816b99e71c.tar.gz";
    sha256 = "1dpz36i3vx0c1wmacrki0wsf30if8xq3bnj71g89rsbxyi87lhcm";
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
