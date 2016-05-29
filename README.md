# Jupyter Scala

Jupyter Scala is a Scala kernel for [Jupyter](http://jupyter.org/) (formerly known as [IPython](http://ipython.org)).
It aims at being a versatile and easily extensible alternative to other Scala kernels or
notebook UIs.

[![Build Status](https://travis-ci.org/alexarchambault/jupyter-scala.svg?branch=master)](https://travis-ci.org/alexarchambault/jupyter-scala)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/alexarchambault/jupyter-scala?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=body_badge)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.alexarchambault.jupyter/scala_2.11.8.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.alexarchambault.jupyter/scala_2.11.8)

## Table of contents

1. [Quick start](#quick-start)
2. [Why](#why)
3. [Special commands / API](#special-commands--api)
4. [Writing libraries using the interpreter API](#writing-libraries-using-the-interpreter-api)
5. [Jupyter installation](#jupyter-installation)
6. [Examples (deprecated)](#examples)
7. [Internals](#internals)
8. [Compiling it](#compiling-it)

## Quick start

First ensure you have [Jupyter](http://jupyter.org/) installed.
Running `jupyter --version` should print a value >= 4.0. See [#jupyter-installation]
if it's not the case.

Then download and run the Jupyter Scala launcher with
```
$ curl -L -o jupyter-scala https://git.io/vrHhi && chmod +x jupyter-scala && ./jupyter-scala && rm -f jupyter-scala
```

This downloads the bootstrap launcher of Jupyter Scala, then runs it. If no previous version of it is already installed,
this simply sets up the kernel in `~/Library/Jupyter/kernels/scala211` (OSX) or `~/.local/share/jupyter/kernels/scala211`
(Linux). Note that on first launch, it will download its dependencies from Maven repositories. These can be found under
`~/.jupyter-scala/bootstrap`.

Once installed, the downloaded launcher can be removed, as it copies itself in `~/Library/Jupyter/kernels/scala211`
or `~/.local/share/jupyter/kernels/scala211`.

For the Scala 2.10.x version, you can do instead
```
$ curl -L -o jupyter-scala-2.10 https://git.io/vrHh7 && chmod +x jupyter-scala-2.10 && ./jupyter-scala-2.10 && rm -f jupyter-scala-2.10
```

The Scala 2.10.x version shares its bootstrap dependencies directory, `~/.jupyter-scala/bootstrap`, with the
Scala 2.11.x version. It installs itself in `~/Library/Jupyter/kernels/scala210` (OSX) or
`~/.local/share/jupyter/kernels/scala210` (Linux).

Some options can be passed to the `jupyter-scala` (or `jupyter-scala-2.10`) launcher.
- The kernel ID (`scala211`) can be changed with `--id custom` (allows to install the kernel alongside already installed Scala kernels).
- The kernel name, that appears in the Jupyter Notebook UI, can be changed with `--name "Custom name"`.
- If a kernel with the same ID is already installed and should be erased, the `--force` option should be specified.

You can check that a kernel is installed with
```
$ jupyter kernelspec list
```
which should print one line per installed Jupyter kernel.

## Why

There are already a few notebook UIs or Jupyter kernels for Scala out there:
- the ones originating from [IScala](https://github.com/mattpap/IScala),
  - [IScala](https://github.com/mattpap/IScala) itself, and
  - [ISpark](https://github.com/tribbloid/ISpark) that adds some Spark support to it,
- the ones originating from [scala-notebook](https://github.com/Bridgewater/scala-notebook),
  - [scala-notebook](https://github.com/Bridgewater/scala-notebook) itself, and
  - [spark-notebook](https://github.com/andypetrella/spark-notebook) that updated / reworked various parts of it and added Spark support to it, and
- [Apache Toree](https://github.com/apache/incubator-toree) (formerly known as [spark-kernel](https://github.com/ibm-et/spark-kernel)), a Jupyter kernel to do Spark calculations in Scala.

[zeppelin](https://github.com/apache/incubator-zeppelin) is worth noticing too. Although not a Jupyter kernel, it provides similar features to Jupyter itself, and has some support for Spark, Flink, Scalding in particular.

Most of them usually target one single use - like Spark calculations (and you have to have Spark around if you just do bare Scala!), or just Scala
calculations (and no way of adding Spark on-the-fly). They share no code with each other, so that features can typically be added to only one single
kernel or project, and need to be re-implemented in the ones targetting other usages. That also makes it hard to share code
between these various uses.

Jupyter Scala is an attempt at having a more modular and versatile kernel to do Scala from Jupyter.

Jupyter Scala aims at being closer to what
[Ammonite](https://github.com/lihaoyi/Ammonite) achieves in the terminal in terms of completion or
pretty-printing. Also, like with Ammonite, users interact with the interpreter via a Scala API
rather than ad-hoc hard-to-reuse-or-automate special commands. Jupyter Scala also publishes
this API in a separate module (`scala-api`), which allows to write external libraries
that interact with the interpreter. In particular it has a Spark bridge, that straightforwardly
adds Spark support to a session. More bridges like this should come soon, to interact with
other big data frameworks, or for plotting. 

Thanks to this modularity, Jupyter Scala shares its interpreter and most of its API with
[ammonium](https://github.com/alexarchambault/ammonium), the fork of Ammonite it is based on.
One can switch from notebook-based UIs to the terminal more confidently, with all that
can be done on one side, being possible on the other (a bit like Jupyter / IPython allows with its
`console` and `notebook` commands, but with the additional niceties of Ammonite, also available in ammonium,
like syntax highlighting of the input). In teams where some people prefer terminal interfaces
to web-based ones, some people can use Jupyter Scala, and others its terminal cousins, according to their tastes.


## Special commands / API

The content of an instance of a [`jupyter.api.API`](https://github.com/alexarchambault/jupyter-scala/blob/master/api/src/main/scala/jupyter/api/API.scala)
is automatically imported when a session is opened (a bit like Ammonite does with its "bridge").
Its methods can be called straightaway, and replace so called "special" or "magic" commands in other notebooks
or REPLs.

### show

```scala
show(value)
```

Print `value` - its [pprint](https://github.com/lihaoyi/upickle-pprint/) representation - with
no truncation. (Same as in Ammonite.)

### classpath.add

```scala
classpath.add("organization" % "name" % "version")
```

Add Maven / Ivy module `"organization" % "name" % "version"` - and its transitive dependencies - to the classpath.
Like in SBT, replace the first `%` with two percent signs, `%%`, for Scala specific modules. This
adds a Scala version suffix to the name that follows. E.g. in Scala 2.11.x, `"organization" %% "name" % "version"`
is equivalent to `"organization" % "name_2.11" % "version"`.

Can be called with several modules at once, like
```scala
classpath.add(
  "organization" % "name" % "version",
  "other" %% "name" % "version"
)
```

(Replaces `load.ivy` in Ammonite.)

### classpath.addInConfig

```scala
classpath.addInConfig("config")(
  "organization" % "name" % "version",
  "organization" %% "name" % "version"
)
```

Add Maven / Ivy modules to a specific configuration. Like in SBT, which itself follows what Ivy does,
dependencies are added to so called configurations. Jupyter Scala uses 3 configurations,

* `compile`: default configuration, the one of the class loader that compiles and runs things,
* `macro`: configuration of the class loader that runs macros - it inherits `compile`, and initially has `scala-compiler` in it, that some macros require,
* `plugin`: configuration for compiler plugins - put compiler plugin modules in it.

### classpath.dependencies

```scala
classpath.dependencies: Map[String, Set[(String, String, String)]]
```

Return the previously added dependencies (values) in each configuration (keys).

### classpath.addRepository

```scala
classpath.addRepository("repository-address")
```

Add a Maven / Ivy repository, for dependencies lookup. For Maven repositories, add the base address
of the repository, like
```scala
classpath.addRepository("https://oss.sonatype.org/content/repositories/snapshots")
```
For Ivy repositories, add the base address along with the pattern of the repository, like
```scala
classpath.addRepository(
  "https://repo.typesafe.com/typesafe/ivy-releases/" +
    "[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)" +
    "[revision]/[type]s/[artifact](-[classifier]).[ext]"
)
```

By default, `~/.ivy2/local`, Central (`https://repo1.maven.org/maven2/`), and
Sonatype releases (`https://oss.sonatype.org/content/repositories/releases`) are used.
Sonatype snapshots (`https://oss.sonatype.org/content/repositories/snapshots`) is also
added in snapshot versions of Jupyter Scala.

### classpath.repositories

```scala
classpath.repositories: Seq[String]
```

Returns a list of the previously added repositories.

### classpath.addPath

```scala
classpath.addPath("/path/to/file.jar")
classpath.addPath("/path/to/directory")
```
Adds one or sevaral JAR files or directories to the classpath.

### classpath.classLoader (advanced)

```scala
classpath.classLoader(config: String): ClassLoader
```

Returns the `ClassLoader` of configuration `config` (see above for the available configurations).

### classpath.addedClasses (advanced)

```scala
classpath.addedClasses(config: String): Map[String, Array[Byte]]
```

Returns a map of the byte code of classes generated during the REPL session. Each line or cell
in a session gets compiled, and added to this map - before getting loaded by a `ClassLoader`
and run.

The `ClassLoader` used to compile and run code mainly contains initial and added
dependencies, and things from this map.

### classpath.onPathsAdded (advanced)

Registers a hook called whenever things are added to the classpath.

### classpath.path (advanced)

```scala
classpath.path()
```

Returns the classpath of the current session.

### Add Spark support

See the example in the [Ammonium README](https://github.com/alexarchambault/ammonium#spark).

## Writing libraries using the interpreter API

Most available classes (`classpath`, `eval`, `setup`, `interpreter`, ...) from a notebook
are:

* defined in the `scala-api` module (`"com.github.alexarchambault.jupyter" % "scala-api_2.11.8" % "0.3.0-M3"`) - or its dependencies ([jupyter-kernel-api](https://github.com/alexarchambault/jupyter-kernel/tree/master/api/src/main/scala/jupyter/api) or
[ammonium-interpreter-api](https://github.com/alexarchambault/ammonium/tree/master/interpreter/api/src/main/scala/ammonite/api)),
* available implicitly from a notebook (`implicitly[ammonite.api.Classpath]` would be the same as just `classpath`).

This allows to write libraries that can easily interact with Jupyter Scala. E.g. one can define a method in a library, like
```scala
def doSomething()(implicit eval: ammonite.api.Eval): Unit = {
  eval("some code")
}

def displaySomething(implicit publish: jupyter.api.Publish[jupyter.api.Evidence], ev: jupyter.api.Evidence): Unit = {
  publish.display("text/html" -> "<div id='myDiv'></div>")
  publish.display("application/javascript" -> "// some JS")
}
```
then load this library via `classpath.add`, and call these methods from the session.

## Jupyter installation

Check that you have [Jupyter](http://jupyter.org/) installed by running
`jupyter --version`. It should print a value >= 4.0. If it's
not the case, a quick way of setting it up consists
in installing the [Anaconda](http://continuum.io/downloads) Python
distribution (or its lightweight counterpart, Miniconda), and then running

    $ pip install jupyter

or

    $ pip install --upgrade jupyter

`jupyter --version` should then print a value >= 4.0.

## Examples

*Warning: these examples are somehow deprecated, and should be updated.*

Some example notebooks can be found in the [examples](https://github.com/alexarchambault/jupyter-scala/tree/master/examples)
directory: you can follow [macrology 201](https://github.com/alexarchambault/jupyter-scala/blob/master/examples/tutorials/Macrology.ipynb) in a notebook,
use compiler plugins like [simulacrum](https://github.com/alexarchambault/jupyter-scala/blob/master/examples/libraries/Simulacrum.ipynb) from notebooks,
use a type level library to [parse CSV](https://github.com/alexarchambault/jupyter-scala/blob/master/examples/libraries/PureCSV.ipynb),
setup a notebook for [psp-std](https://github.com/alexarchambault/jupyter-scala/blob/master/examples/libraries/psp-std.ipynb)
etc.


## Internals

Jupyter Scala uses the Scala interpreter of [ammonium](https://github.com/alexarchambault/ammonium),
in particular its `interpreter` and `interpreter-api` modules. The interaction with Jupyter
(the Jupyter protocol, ZMQ concerns, etc.) are handled in a separate project,
[jupyter-kernel](https://github.com/alexarchambault/jupyter-kernel). In a way, Jupyter Scala is just
a bridge between these two projects.

The API as seen from a Jupyter Scala session is defined
in the `scala-api` module, that itself depends on the `interpreter-api` module of ammonium.
The core of the kernel is in the `scala` module, in particular with an implementation
of an `Interpreter` for jupyter-kernel, based on `interpreter` from ammonium,
and implementations of the interfaces / traits defined in `scala-api`.
It also has a third module, `scala-cli`, which deals with command-line argument parsing,
and launches the kernel itself. The launcher consists in this third module.

The launcher itself is [generated](https://github.com/alexarchambault/jupyter-scala/blob/master/project/generate-launcher.sh)
with [coursier](https://github.com/alexarchambault/coursier).

## Compiling it

Clone the sources:
```bash
$ git clone https://github.com/alexarchambault/jupyter-scala.git
$ cd jupyter-scala
```

Compile and publish them:
```bash
$ sbt publishLocal
```

Edit the `launch` script, and set `VERSION` to `0.3.0-SNAPSHOT` (the version being built / published locally). Launch it:
```bash
$ ./launch --help
```

`launch` behaves like the `jupyter-scala` launcher above, and accepts the same options as it (`--id custom-id`, `--name "Custom name"`, `--force`, etc - see `--help` for more infos). When launched, it will download (on first launch) and launch coursier,
that will itself launch the kernel out of the artifacts published locally. If you install a Jupyter kernel through it, it will
copy the coursier launcher in a Jupyter kernel directory (like `~/Library/Jupyter/kernels/scala211` on OSX), and
setup a `kernel.json` file in it able to launch the copied coursier launcher with the right options, so that coursier will then
launch the Jupyter kernel out of the locally published artifacts.

Once a kernel is setup this way, there's no need to run the launcher again if the sources change. Just publishing them locally
with `sbt publishLocal` is enough for them to be used by the kernel on the next (re-)launch. One can also run
`sbt "~publishLocal"` for the sources to be watched for changes, and built / published after each of them.

If one wants to make changes to jupyter-kernel or ammonium, and test them via Jupyter Scala, just clone their sources,
```bash
$ git clone https://github.com/alexarchambault/jupyter-kernel
```
or
```bash
$ git clone https://github.com/alexarchambault/ammonium
```
build them and publish them locally,
```bash
$ cd jupyter-kernel
$ sbt publishLocal
```
or
```bash
$ cd ammonium
$ sbt publishLocal
```

Then adjust the `ammoniumVersion` or `jupyterKernelVersion` in the `build.sbt` of jupyter-scala (set them to `0.3.0-SNAPSHOT`
or `0.4.0-SNAPSHOT`), reload the SBT compiling / publishing jupyter-scala (type `reload`, or exit and relaunch it), and
build / publish locally jupyter-scala again (`sbt publishLocal`). That will make the locally published artifacts of
jupyter-scala depend on the locally published ones of ammonium or jupyter-kernel.

To generate a launcher using these modified ammonium / jupyter-kernel / jupyter-scala, run
```bash
$ VERSION=0.3.0-SNAPSHOT project/generate-launcher.sh -s
```
The `VERSION` environment variable tells the script to use the locally published jupyter-scala version. The `-s` option
makes it generate a *standalone* launcher, rather than a thin one. A thin launcher requires the ammonium / jupyter-kernel /
jupyter-scala versions it uses to be published on a (Maven) repository accessible to the users. It is the case for the
launcher in the jupyter-scala repository, but it's likely not the case if you just modified the sources. A standalone
launcher embeds all the JARs it needs, including the ones you locally published on your machine - at the cost of an
increased size (~40 MB). Note that as this solution is a bit hackish, you shouldn't change the version of the
versions of the locally published projects (these should stay the default `0.x.y-SNAPSHOT`), so that the dependency
management in the kernel still can find public corresponding artifacts - although the embedded ones will have the priority
in practice.


Released under the Apache 2.0 license, see LICENSE for more details.
