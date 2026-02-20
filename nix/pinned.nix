let
  inherit (import <nixpkgs> {}) fetchFromGitHub;
in
  import (fetchFromGitHub {
    owner = "NixOS";
    repo  = "nixpkgs";
    rev = "fef9403a3e4d";
    hash = "sha256-pF1quXG5wsgtyuPOHcLfYg/ft/QMr8NnX0i6tW2187s=";
  })
