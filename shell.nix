let
  pkgs = import ./nix/pinned.nix {
    overlays = [(import ./nix/overlays.nix)];
  };
in
with pkgs;
mkShell {
  buildInputs = [
    babashka
    clojure
    cljfmt
    clj-kondo
    neil

    # Needed for Playwright (wally) e2e tests on NixOS.
    # Playwright Java ships its own node binary, which is dynamically linked
    # and won't run on NixOS by default; we point Playwright at Nix's node and
    # use Nix's Chromium binary.
    nodejs
    chromium
  ];

  shellHook = ''
    # Playwright Java (via wally) runs on NixOS when we:
    # - prevent Playwright from downloading its own browsers (they're not Nix-patched)
    # - provide a "fake" Playwright browser installation that delegates to Nix's Chromium
    # - ensure a working Node is available

    export PLAYWRIGHT_NODEJS_PATH="${nodejs}/bin/node"
    export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

    # Playwright 1.31.0 expects Chromium revision 1048 at this path.
    export PLAYWRIGHT_BROWSERS_PATH="$HOME/.cache/ms-playwright"
    chromium_dir="$PLAYWRIGHT_BROWSERS_PATH/chromium-1048/chrome-linux"
    mkdir -p "$chromium_dir"

    chromium_wrapper="$chromium_dir/chrome"
    if [ ! -e "$chromium_wrapper" ]; then
      cat > "$chromium_wrapper" <<'EOF'
#!/usr/bin/env bash
exec "${chromium}/bin/chromium" "$@"
EOF
      chmod +x "$chromium_wrapper"
    fi
  '';
}
