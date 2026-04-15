self: super:
{
  jdk = super.jdk25;

  clojure = super.clojure.override {
    jdk = self.jdk;
  };

  cljfmt = super.cljfmt.overrideAttrs (_old: rec {
    version = "0.16.4";
    src     = super.fetchurl {
      url = "https://github.com/weavejester/cljfmt/releases/download/${version}/cljfmt-${version}-standalone.jar";
      hash = "sha256-6GTK/QH0z7Qox5JFGWu4hacGiLvBVmJQHvw0K2sMMAw=";
    };
  });
}
