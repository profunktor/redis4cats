let
  # unstable packages on May 13th
  nixpkgs = fetchTarball {
    url = "https://github.com/NixOS/nixpkgs-channels/archive/6bcb1dec8ea.tar.gz";
    sha256 = "04x750byjr397d3mfwkl09b2cz7z71fcykhvn8ypxrck8w7kdi1h";
  };
  pkgs = import nixpkgs {};
in
  pkgs.mkShell {
    buildInputs = [
      pkgs.jekyll # 4.0.1
      pkgs.openjdk # 1.8.0_242
      pkgs.sbt # 1.3.10
    ];
  }
