let
  # unstable packages on May 13th
  pkgs = import (fetchTarball "https://github.com/NixOS/nixpkgs-channels/archive/6bcb1dec8ea.tar.gz") {};
in
  pkgs.mkShell {
    buildInputs = [
      pkgs.jekyll # v4.0.1
    ];
  }
