---
title: Other
---

## All-included launcher

The launcher above downloads the JARs it needs upon launch. These JARs are downloaded to /
picked from the cache of coursier. You may prefer to have the launcher embed all these JARs,
so that nothing needs to be downloaded or picked from a cache upon launch. Passing
`--standalone` to the `coursier bootstrap` command generates such a launcher,
```bash
$ SCALA_VERSION=@SCALA_VERSION@ ALMOND_VERSION=@VERSION@
$ coursier bootstrap --standalone \
    -r jitpack \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    --sources --default=true \
    -o almond
```
but that launcher won't work fine until something like https://github.com/lihaoyi/Ammonite/pull/850
is merged in Ammonite.

