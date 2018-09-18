# Jupyter Scala

Jupyter Scala is a Scala kernel for [Jupyter](https://jupyter.org).
It aims at being a versatile and easily extensible alternative to other Scala kernels or
notebook UIs, building on both Jupyter and [Ammonite](https://github.com/lihaoyi/Ammonite).

[![Build Status](https://travis-ci.org/alexarchambault/jupyter-scala.svg?branch=master)](https://travis-ci.org/alexarchambault/jupyter-scala)
[![Gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/alexarchambault/jupyter-scala?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=body_badge)
[![Maven Central](https://img.shields.io/maven-central/v/org.jupyter-scala/scala-cli_2.11.8.svg)](https://maven-badges.herokuapp.com/maven-central/org.jupyter-scala/scala-cli_2.11.8)

The current version is available for Scala 2.11. Support for Scala 2.10 could be added back, and 2.12 should be supported soon (via ammonium / Ammonite).

## Table of contents

1. [Quick start](#quick-start)
2. [Extra launcher options](#extra-launcher-options)
3. [Comparison to alternatives](#comparison-to-alternatives)
4. [Status / disclaimer](#status--disclaimer)
5. [Big data frameworks](#big-data-frameworks)
  1. [Spark](#spark)
  2. [Flink](#flink)
  3. [Scio / Beam](#scio--beam)
  4. [Scalding](#scalding)
6. [Plotting](#plotting)
  1. [Vegas](#vegas)
  2. [plotly-scala](#plotly-scala)
7. [Special commands / API](#special-commands--api)
8. [Jupyter installation](#jupyter-installation)
9. [Internals](#internals)
10. [Compiling it](#compiling-it)

## Quick start

First ensure you have [Jupyter](https://jupyter.org/) installed.
Running `jupyter --version` should print a value >= 4.0. See [Jupyter installation](#jupyter-installation)
if it's not the case.

Simply run the [`jupyter-scala` script](https://raw.githubusercontent.com/alexarchambault/jupyter-scala/master/jupyter-scala) of this
repository to install the kernel. Launch it with `--help` to list available (non mandatory) options.

Once installed, the kernel should be listed by `jupyter kernelspec list`.

## Extra launcher options

Some options can be passed to the `jupyter-scala` script / launcher.
- The kernel ID (`scala`) can be changed with `--id custom` (allows to install the kernel alongside already installed Scala kernels).
- The kernel name, that appears in the Jupyter Notebook UI, can be changed with `--name "Custom name"`.
- If a kernel with the same ID is already installed and should be erased, the `--force` option should be specified.

## Comparison to alternatives

There are already a few notebook UIs or Jupyter kernels for Scala out there:
- the ones originating from [IScala](https://github.com/mattpap/IScala),
  - [IScala](https://github.com/mattpap/IScala) itself, and
  - [ISpark](https://github.com/tribbloid/ISpark) that adds some Spark support to it,
- the ones originating from [scala-notebook](https://github.com/Bridgewater/scala-notebook),
  - [scala-notebook](https://github.com/Bridgewater/scala-notebook) itself, and
  - [spark-notebook](https://github.com/andypetrella/spark-notebook) that updated / reworked various parts of it and added Spark support to it, and
- the ones affiliated with Apache,
  - [Toree](https://github.com/apache/incubator-toree) (incubated, formerly known as [spark-kernel](https://github.com/ibm-et/spark-kernel)), a Jupyter kernel to do Spark calculations, and
  - [Zeppelin](https://github.com/apache/zeppelin), a JVM-based alternative to Jupyter, with some support for Spark, Flink, Scalding in particular.)

Compared to them, jupyter-scala aims at being versatile, allowing to add support for big data frameworks on-the-fly. It aims
at building on the nice features of both [Jupyter](https://jupyter.org) (alternative UIs, ...) and [Ammonite](https://github.com/lihaoyi/Ammonite) -
it is now based on a only slightly modified version of it ([ammonium](https://github.com/alexarchambault/ammonium)). Most
of what can be done via notebooks can also be done in the console via
[ammonium](https://github.com/alexarchambault/ammonium) (slightly modified [Ammonite](https://github.com/lihaoyi/Ammonite)). jupyter-scala is not tied to specific versions of Spark - one can add
support for a given version in a notebook, and support for another version in another notebook.

## Status / disclaimer

jupyter-scala tries to build on top of both Jupyter and Ammonite. Both of them are quite used and well tested / reliable.
The specific features of jupyter-scala (support for big data frameworks in particular) should be relied on with caution -
some are just POC for now (support for Flink, Scio), others are a bit more used... in specific contexts (support for
Spark, quite used on YARN at my current company, but whose status is unknown with other cluster managers).

## Big data frameworks

### Spark

Status: some specific uses (Spark on YARN) well tested in particular contexts (especially the previous version, the current one less so for now), others (Mesos, standalone clusters) unknown with the current code base

Use like

```scala
import $exclude.`org.slf4j:slf4j-log4j12`, $ivy.`org.slf4j:slf4j-nop:1.7.21` // for cleaner logs
import $profile.`hadoop-2.6`
import $ivy.`org.apache.spark::spark-sql:2.1.0` // adjust spark version - spark >= 2.0
import $ivy.`org.apache.hadoop:hadoop-aws:2.6.4`
import $ivy.`org.jupyter-scala::spark:0.4.2` // for JupyterSparkSession (SparkSession aware of the jupyter-scala kernel)

import org.apache.spark._
import org.apache.spark.sql._
import jupyter.spark.session._

val sparkSession = JupyterSparkSession.builder() // important - call this rather than SparkSession.builder()
  .jupyter() // this method must be called straightaway after builder()
  // .yarn("/etc/hadoop/conf") // optional, for Spark on YARN - argument is the Hadoop conf directory
  // .emr("2.6.4") // on AWS ElasticMapReduce, this adds aws-related to the spark jar list
  // .master("local") // change to "yarn-client" on YARN
  // .config("spark.executor.instances", "10")
  // .config("spark.executor.memory", "3g")
  // .config("spark.hadoop.fs.s3a.access.key", awsCredentials._1)
  // .config("spark.hadoop.fs.s3a.secret.key", awsCredentials._2)
  .appName("notebook")
  .getOrCreate()
```

Important: `SparkSession`s should *not* be manually created. Only the ones from the `org.jupyter-scala::spark` library
are aware of the kernel, and setup the `SparkSession` accordingly (passing it the loaded dependencies, the kernel
build products, etc.).

Note that no Spark distribution is required to have the kernel work. In particular, on YARN, the call to `.yarn(...)` above
generates itself the so-called spark assembly (or list of JARs with Spark 2), that is (are) shipped to the driver and
executors.

### Flink

Status: POC

Use like

```scala
import $exclude.`org.slf4j:slf4j-log4j12`, $ivy.`org.slf4j:slf4j-nop:1.7.21`, $ivy.`org.slf4j:log4j-over-slf4j:1.7.21` // for cleaner logs
import $ivy.`org.jupyter-scala::flink-yarn:0.4.2`

import jupyter.flink._

addFlinkImports()

sys.props("FLINK_CONF_DIR") = "/path/to/flink-conf-dir" // directory, should contain flink-conf.yaml

interp.load.cp("/etc/hadoop/conf")

val cluster = FlinkYarn(
  taskManagerCount = 2,
  jobManagerMemory = 2048,
  taskManagerMemory = 2048,
  name = "flink",
  extraDistDependencies = Seq(
    s"org.apache.hadoop:hadoop-aws:2.7.3" // required on AWS ElasticMapReduce
  )
)

val env = JupyterFlinkRemoteEnvironment(cluster.getJobManagerAddress)
```

### Scio / Beam

Status: POC

Use like

```scala
import $ivy.`org.jupyter-scala::scio:0.4.3`, $ivy.`org.apache.beam:beam-runners-direct-java:2.6.0`

import jupyter.scio._

import com.spotify.scio._
import com.spotify.scio.bigquery._

// Define JupyterScioContext
JupyterScioContext(
  "runner" -> "DirectRunner", // DirectRunner or DataflowRunner
  "project" -> "jupyter-scala",
  "stagingLocation" -> "gs://bucket/staging"
)

sc.withGcpCredential("/path-to/credentials.json") // alternatively, set the env var GOOGLE_APPLICATION_CREDENTIALS to that path

// Access JupyterScioContext with `sc`
```

### Scalding

Status: TODO! (nothing for now)

## Special commands / API

Being based on a slightly modified version of [Ammonite](https://github.com/lihaoyi/Ammonite), jupyter-scala allows to
- add dependencies / repositories, 
- manage pretty-printing, 
- load external scripts, etc.

the same way Ammonite does, with the same API, described in its [documentation](http://www.lihaoyi.com/Ammonite/#Ammonite-REPL).

It has some additions compared to it though:

### Excluding dependencies

One can exclude dependencies with, e.g.
```scala
import $exclude.`org.slf4j:slf4j-log4j12`
```
to exclude `org.slf4j:slf4j-log4j12` from subsequent dependency loading.

### Displaying HTML / images / running Javascript

```scala
publish.html(
  """
    <b>Foo</b>
    <div id="bar"></div>
  """
)

publish.png(png) // png: Array[Byte]

publish.js(
  """
    console.log("hey");
  """
)
```

## Plotting

Like for big data frameworks, support for plotting libraries can be added on-the-fly during a notebook session.

### Vegas

[Vegas](https://github.com/vegas-viz/Vegas) is a Scala wrapper for [Vega-Lite](https://vega.github.io/vega-lite/)

Use like
```scala
import $ivy.`org.vegas-viz::vegas:0.3.8`

import vegas._

Vegas("Country Pop").
  withData(
    Seq(
      Map("country" -> "USA", "population" -> 314),
      Map("country" -> "UK", "population" -> 64),
      Map("country" -> "DK", "population" -> 80)
    )
  ).
  encodeX("country", Nom).
  encodeY("population", Quant).
  mark(Bar).
  show
```

Additional Vegas samples with jupyter-scala notebook are [here](https://github.com/vegas-viz/Vegas/blob/master/notebooks/jupyter_example.ipynb).  

### plotly-scala

[plotly-scala](http://plotly-scala.org) is a Scala wrapper for [plotly.js](https://plot.ly/javascript/).

Use like
```scala
import $ivy.`org.plotly-scala::plotly-jupyter-scala:0.3.0`

import plotly._
import plotly.element._
import plotly.layout._
import plotly.JupyterScala._

plotly.JupyterScala.init()

val (x, y) = Seq(
  "Banana" -> 10,
  "Apple" -> 8,
  "Grapefruit" -> 5
).unzip

Bar(x, y).plot()
```

## Jupyter installation

Check that you have [Jupyter](https://jupyter.org/) installed by running
`jupyter --version`. It should print a value >= 4.0. If it's
not the case, a quick way of setting it up consists
in installing the [Anaconda](https://continuum.io/downloads) Python
distribution (or its lightweight counterpart, Miniconda), and then running

    $ pip install jupyter

or

    $ pip install --upgrade jupyter

`jupyter --version` should then print a value >= 4.0.

## Internals

jupyter-scala uses the Scala interpreter of [ammonium](https://github.com/alexarchambault/ammonium),
a slightly modified [Ammonite](https://github.com/lihaoyi/Ammonite). The interaction with Jupyter
(the Jupyter protocol, ZMQ concerns, etc.) are handled in a separate project,
[jupyter-kernel](https://github.com/alexarchambault/jupyter-kernel). In a way, jupyter-scala is just
a bridge between these two projects.

The API as seen from a jupyter-scala session is defined
in the `scala-api` module, that itself depends on the `api` module of jupyter-kernel.
The core of the kernel is in the `scala` module, in particular with an implementation
of an `Interpreter` for jupyter-kernel,
and implementations of the interfaces / traits defined in `scala-api`.
It also has a third module, `scala-cli`, which deals with command-line argument parsing,
and launches the kernel itself. The launcher script just runs this third module.

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

Edit the `jupyter-scala` script, and set `VERSION` to `0.4.3-SNAPSHOT` (the version being built / published locally). Install it:
```bash
$ ./jupyter-scala --id scala-develop --name "Scala (develop)" --force
```

If one wants to make changes to jupyter-kernel or ammonium, and test them via jupyter-scala, just clone their sources,
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
$ sbt published/publishLocal
```

Then adjust the `ammoniumVersion` or `jupyterKernelVersion` in the `build.sbt` of jupyter-scala (set them to `0.4.3-SNAPSHOT`
or `0.8.1-SNAPSHOT`), reload the SBT compiling / publishing jupyter-scala (type `reload`, or exit and relaunch it), and
build / publish locally jupyter-scala again (`sbt publishLocal`). That will make the locally published artifacts of
jupyter-scala depend on the locally published ones of ammonium or jupyter-kernel.

Released under the Apache 2.0 license, see LICENSE for more details.
