let
  pkgs = import ./nix/pinned.nix {
    overlays = [(import ./nix/overlays.nix)];
  };
  inherit (pkgs.stdenv.hostPlatform) system;
  throwSystem = throw "Unsupported system: ${system}";
  suffix =
    {
      x86_64-linux = "linux64";
      aarch64-linux = "linux-arm64";
      x86_64-darwin = "mac64";
      aarch64-darwin = "mac-arm64";
    }.${system} or throwSystem;
in
with pkgs;
mkShell {
  nativeBuildInputs = [
    playwright-driver.browsers
  ];
  buildInputs = [
    babashka
    clojure
    cljfmt
    clj-kondo
    neil
  ];
  PLAYWRIGHT_BROWSERS_PATH = "${pkgs.playwright-driver.browsers}";
  PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH = "${pkgs.playwright-driver.browsers}/chromium_headless_shell-${pkgs.playwright-driver.browsersJSON.chromium-headless-shell.revision}/chrome-headless-shell-${suffix}/chrome-headless-shell";
  PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS = true;
  PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD = 1;
  PLAYWRIGHT_HOST_PLATFORM_OVERRIDE = "ubuntu-24.04";
  PLAYWRIGHT_NODEJS_PATH="${pkgs.nodejs}/bin/node";
}
