---
title: Installation
---

Create a launcher via [coursier](http://get-coursier.io) with
```bash
$ SCALA_VERSION=@SCALA_VERSION@ ALMOND_VERSION=@VERSION@
$ coursier bootstrap @EXTRA_COURSIER_ARGS@\
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond
```

See the available versions of almond [here](https://github.com/jupyter-scala/jupyter-scala/releases),
adjust `ALMOND_VERSION` and `SCALA_VERSION` at your convenience (not all combinations are guaranteed
to be available, see the available combinations [here](install-versions.md)).

Run the launcher like
```bash
$ ./almond --install
```
to install the kernel. See `./almond --help` for the available options. Once the kernel is
installed, the generated launcher can then be safely removed, with `rm -f almond`.

