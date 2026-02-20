self: super:
{
  jdk = super.jdk25;

  clojure = super.clojure.override {
    jdk = self.jdk;
  };

  cljfmt = super.cljfmt.overrideAttrs (_old: rec {
    version = "0.16.0";
    src     = super.fetchurl {
      url = "https://github.com/weavejester/cljfmt/releases/download/${version}/cljfmt-${version}-standalone.jar";
      hash = "sha256-56llKSnJJzjv9mf33ir7b3gk8Jp+jxyuax6vEXj0xDk=";
    };
  });
}
