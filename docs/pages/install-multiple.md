---
title: Multiple kernels
---

Several versions of almond can be installed side-by-side. This is useful e.g. to have kernels
for several Scala versions, or to test newer / former almond versions.

To install several version of the kernel side-by-side, just ensure the different installed versions
have different ids (required) and display names (recommended).

For example, let's install almond for the scala `2.13.0` version,
```bash
$ SCALA_VERSION=2.13.0 ALMOND_VERSION=@VERSION@
$ coursier bootstrap @EXTRA_COURSIER_ARGS@\
    -r jitpack \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    --sources --default=true \
    -o almond
$ ./almond --install
$ rm -f almond
```

This installs almond with the default kernel id, `scala`, and default display name, "Scala".

Now let's *also* install almond for scala `2.12.9`,
```bash
$ SCALA_VERSION=2.12.9 ALMOND_VERSION=@VERSION@
$ coursier bootstrap @EXTRA_COURSIER_ARGS@\
    -r jitpack \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    --sources --default=true \
    -o almond-scala-2.12
$ ./almond-scala-2.12 --install --id scala212 --display-name "Scala (2.12)"
$ rm -f almond-scala-2.12
```

`--id scala211` ensures this kernel is installed along side the former, in a directory
with a different name.

`--display-name "Scala (2.12)"` ensures users can differentiate both kernels, with the latter
appearing in front-ends under the name "Scala (2.12)".
