---
title: Multiple kernels
---

Several versions of almond can be installed side-by-side. This is useful e.g. to have kernels
for several Scala versions, or to test newer / former almond versions.

To install several version of the kernel side-by-side, just ensure the different installed versions
have different ids (required) and display names (recommended).

For example, let's install almond for the scala `2.12.7` version,
```bash
$ SCALA_VERSION=2.12.7 ALMOND_VERSION=@VERSION@
$ coursier bootstrap \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond
$ ./almond --install
$ rm -f almond
```

This installs almond with the default kernel id, `scala`, and default display name, "Scala".

Now let's *also* install almond for scala `2.11.12`,
```bash
$ SCALA_VERSION=2.11.12 ALMOND_VERSION=@VERSION@
$ coursier bootstrap \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond-scala-2.11
$ ./almond-scala-2.11 --install --id scala211 --display-name "Scala (2.11)"
$ rm -f almond-scala-2.11
```

`--id scala211` ensure this kernel is installed along side the former, in a directory
with a different name.

`--display-name "Scala (2.11)"` ensures users can differentiate both kernels, with the latter
appearing in front-ends under the name "Scala (2.11)".
