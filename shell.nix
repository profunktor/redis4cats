let
  nixpkgs = fetchTarball {
    name   = "NixOS-unstable-13-05-2020";
    url    = "https://github.com/NixOS/nixpkgs-channels/archive/6bcb1dec8ea.tar.gz";
    sha256 = "04x750byjr397d3mfwkl09b2cz7z71fcykhvn8ypxrck8w7kdi1h";
  };
  pkgs = import nixpkgs {};
in
  pkgs.mkShell {
    buildInputs = with pkgs; [
      haskellPackages.dhall-json # 1.6.2
      jekyll # 4.0.1
      openjdk # 1.8.0_242
      sbt # 1.3.10
    ];
  }
