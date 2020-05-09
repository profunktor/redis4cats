let
  pkgs = import (fetchTarball "https://github.com/NixOS/nixpkgs-channels/archive/10100a97c89.tar.gz") {};
in
pkgs.mkShell {
  buildInputs = [
    pkgs.jekyll
  ];
}
