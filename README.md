# almond

*almond* is a [Scala](https://scala-lang.org) kernel for [Jupyter](https://jupyter.org).

It mostly wraps the [Ammonite](http://ammonite.io) Scala shell in a Jupyter kernel,
with the addition of custom Jupyter-specific APIs. It is formerly known as
*jupyter-scala*.

It also provides libraries allowing one to write custom Jupyter kernels
in Scala.

## Quick start

Create a launcher via [coursier](http://get-coursier.io) with
```
$ SCALA_VERSION=2.12.6 ALMOND_VERSION=0.1.7
$ coursier bootstrap \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond
```

See the available versions of almond [here](https://github.com/jupyter-scala/jupyter-scala/releases),
adjust `ALMOND_VERSION` and `SCALA_VERSION` at your convenience (not all combinations are guaranteed
to be available, see the available combinations [here](docs/versions.md)).

Run the launcher like
```
$ ./almond --install
```
to install the kernel. See `./almond --help` for the available options. Once the kernel is
installed, the generated launcher can then be safely removed, with `rm -f almond`.


## All-included launcher

The launcher above downloads the JARs it needs upon launch. These JARs are downloaded to /
picked from the cache of coursier. You may prefer to have the launcher embed all these JARs,
so that nothing needs to be downloaded or picked from a cache upon launch. Passing
`--standalone` to the `coursier bootstrap` command generates such a launcher,
```
$ SCALA_VERSION=2.12.6 ALMOND_VERSION=0.1.7
$ coursier bootstrap --standalone \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond
```
but that launcher won't work fine until something like https://github.com/lihaoyi/Ammonite/pull/850
is merged in Ammonite.


