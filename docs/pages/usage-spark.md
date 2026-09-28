---
title: Spark
---

Almond comes with a Spark integration module called *almond-spark*, which allows you to connect to a Spark cluster and
to run Spark calculations interactively from a Jupyter notebook.

It is based on [ammonite-spark](https://github.com/alexarchambault/ammonite-spark), adding Jupyter specific features
such as progress bars and cancellation for running Spark computations.

*ammonite-spark* handles loading Spark in a clever way, and does not rely on a specific Spark distribution.
Because of that, you can use it with any Spark 2.x version.
The only limitation is that the Scala version of Spark and the running Almond kernel must match, so make sure your
kernel uses the same Scala version as your Spark cluster.
Spark 2.0.x - 2.3.x requires Scala 2.11. Spark 2.4.x supports both Scala 2.11 and 2.12.

Note that as of almond 0.7.0, almond only supports Scala 2.12 and therefore requires Spark 2.4.x for Scala 2.12.

For more information, see the [README](https://github.com/alexarchambault/ammonite-spark/blob/master/README.md) of ammonite-spark.

To use it, import the *almond-spark* dependency as well as Spark 2.x itself.

```scala
import $ivy.`org.apache.spark::spark-sql:2.4.0` // Or use any other 2.x version here
import $ivy.`sh.almond::almond-spark:_` // Not required since almond 0.7.0 (will be automatically added when importing spark)
```

Usually you want to disable logging in order to avoid polluting your cell outputs:

```scala
import org.apache.log4j.{Level, Logger}
Logger.getLogger("org").setLevel(Level.OFF)

```

The `_` version of *almond-spark* above stands for the version almond was built with (`@AMMONITE_SPARK_VERSION@`
for almond `@VERSION@`), which is also the one added automatically when Spark is imported.

Then create a `SparkSession` using the `NotebookSparkSessionBuilder` provided by *almond-spark*:

```scala
import org.apache.spark.sql._

val spark = {
  NotebookSparkSession.builder()
    .master("local[*]")
    .getOrCreate()
}
```

When running this, you should see that the cell output contains a link to the Spark UI.

Note the use of `NotebookSparkSession.builder()`, instead of `SparkSession.builder()` that one would use when e.g. writing a Spark job.

The builder returned by `NotebookSparkSession.builder()` extends the one of `SparkSession.builder()`,
so that one can call `.appName("foo")`, `.config("key", "value")`, etc. on it.

Now you can get a `SparkContext` from the `SparkSession` and run Spark calculations.

```scala
def sc = spark.sparkContext

val rdd = sc.parallelize(1 to 100000000, 100)

val n = rdd.map(_ + 1).sum()
```

When you execute a Spark action like `sum` you should see a progress bar, showing the progress of the running Spark job,
as well as a link to cancel the job if you are using the Jupyter classic UI.

### Syncing dependencies

If extra dependencies are loaded, via ``import $ivy.`…` `` after the `SparkSession` has been created, one should call
`NotebookSparkSession.sync()` for the newly added JARs to be passed to the Spark executors.

## Re-using a Spark distribution via `SPARK_HOME`

By default, *almond-spark* does not need a Spark distribution. Spark is loaded in the kernel via
``import $ivy.`org.apache.spark::spark-sql:…` ``, and the JARs *almond-spark* resolves from Maven Central that way
are the ones the driver runs with, and the ones sent to the executors.

That is the simplest way to get started, but one may prefer to re-use a Spark distribution already at hand, like
the one `spark-shell` or `spark-submit` run from on the same machine, for a few reasons:
- it guarantees that the driver runs with exactly the same JARs as the master and workers of a
  [standalone cluster](#using-with-standalone-cluster) started from that distribution,
- a vendor-provided distribution can contain patched or extra JARs, that cannot be found on Maven Central,
- the machine running the kernel may not have access to Maven Central at all.

*almond-spark* supports that via the `SPARK_HOME` environment variable. This is how the
[standalone and "Spark distribution" tests](https://github.com/alexarchambault/ammonite-spark/blob/main/TESTS.md)
of ammonite-spark load Spark. It involves three steps.

### Make `SPARK_HOME` visible to the kernel

The kernel needs to have `SPARK_HOME` set in its environment. Either export it before starting Jupyter, like
```text
$ export SPARK_HOME=/path/to/spark-3.5.8-bin-hadoop3
$ jupyter lab
```
or bake it in the kernel spec when installing almond, with the `--env` option
(see [Using custom Maven repositories](install-advanced.md#using-custom-maven-repositories) for more details about it):
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- \
    --install --env SPARK_HOME=/path/to/spark-3.5.8-bin-hadoop3
```
Jupyter then sets `SPARK_HOME` when it launches the kernel.

Note that, unlike `spark-shell`, *almond-spark* does not read the `conf/spark-defaults.conf` file of the
distribution: pass its settings via `.config("key", "value")` when building the session if needed.

`SPARK_HOME` changes how *almond-spark* handles the JARs of any session, so only set it for kernels that load Spark
from that distribution. If Spark is loaded via ``import $ivy.`…` `` while `SPARK_HOME` is set, the Spark JARs from
Maven Central are not recognized as Spark JARs. They then all get sent to the executors via `spark.jars`, and on YARN
the executors run with the JARs of the distribution rather than those of the notebook.

### Load the JARs of the distribution in the kernel

Rather than importing Spark with ``import $ivy.`…` ``, load the JARs of the distribution in the session, with the Ammonite
`interp.load.cp` API:

```scala
interp.load.cp {
  val sparkHome = os.Path(sys.env.getOrElse("SPARK_HOME", sys.error("SPARK_HOME not set")))
  os.list(sparkHome / "jars")
    .filter(_.ext == "jar")
    .filterNot { jar =>
      jar.last.startsWith("scala-library-") ||
      jar.last.startsWith("scala-reflect-") ||
      jar.last.startsWith("scala-compiler-") ||
      jar.last.startsWith("spark-repl_")
    }
}
```

Two kinds of JARs are left out:
- the Scala JARs (`scala-library`, `scala-reflect`, `scala-compiler`): the kernel already runs with its own,
  loading those of the distribution alongside would clash with them,
- Spark's own REPL JAR (`spark-repl_*`), like the ammonite-spark tests do. Up to Spark 3.4, that JAR contains
  the `ExecutorClassLoader` that fetches the classes compiled from notebook cells, and *almond-spark* only loads
  its own version of it when `spark-repl_*` isn't on the classpath. Since Spark 3.5, that class loader lives in
  `spark-core`, and leaving out `spark-repl_*` makes no difference.

As the Scala JARs of the distribution are not used, the kernel must run the same Scala binary version
as the distribution. The Scala version of a distribution can be found from its `scala-library` JAR:
```text
$ ls "$SPARK_HOME/jars"/scala-library-*
/path/to/spark-3.5.8-bin-hadoop3/jars/scala-library-2.12.18.jar
```
Spark 3.x distributions ship Scala 2.12 by default, and Scala 2.13 in their `-scala2.13` variant.
Spark 4.x distributions ship Scala 2.13. Install almond with the matching `--scala` version.

### Import almond-spark and create the session

As Spark is not imported via ``import $ivy.`…` `` in that case, *almond-spark* is not added automatically to the
session. Import it explicitly, then create the `SparkSession` as usual:

```scala
import $ivy.`sh.almond::almond-spark:_`

import org.apache.spark.sql._

val spark = {
  NotebookSparkSession.builder()
    .master("spark://localhost:7077")
    .getOrCreate()
}
```

When `SPARK_HOME` is set, `NotebookSparkSession.builder()` does not resolve any Spark JAR from Maven Central
(you should not see the `Getting spark JARs` message in the cell output). Instead, it considers the JARs of
`$SPARK_HOME/jars` as the Spark JARs, that the executors already have:
- they are not sent to the executors via `spark.jars`. Only the JARs loaded in the notebook via
  ``import $ivy.`…` `` on top of them are,
- if `SPARK_DIST_CLASSPATH` is set in the environment too, like it is by `spark-env.sh` on some Hadoop setups, its
  entries are also considered part of the distribution, and are not sent to the executors either,
- on YARN, `spark.yarn.jars` is set to the JARs of the distribution, so that the YARN containers use the same JARs
  as the driver. Call `.sendSparkYarnJars(false)` on the builder to set `spark.yarn.jars` or `spark.yarn.archive`
  yourself, e.g. if the distribution is already available on HDFS.

## Using with standalone cluster

Simply set the master to `spark://…` when building the session, e.g.

```scala
val spark = {
  NotebookSparkSession.builder()
    .master("spark://localhost:7077")
    .config("spark.executor.instances", "4")
    .config("spark.executor.memory", "2g")
    .getOrCreate()
}
```

Ensure the version of Spark used to start the master and executors matches the one loaded in the notebook session
(via e.g. ``import $ivy.`org.apache.spark::spark-sql:X.Y.Z` ``), and that the machine running the kernel can access / is
accessible from all nodes of the standalone cluster.

The most reliable way to get matching versions is to load Spark from the very distribution the master and workers were
started from, by pointing `SPARK_HOME` at it, as described in
[Re-using a Spark distribution via `SPARK_HOME`](#re-using-a-spark-distribution-via-spark_home). The driver then
runs with the exact same JARs as the cluster, and only the extra dependencies of the notebook get sent to the executors.

## Using with YARN cluster

Set the master to `"yarn"` when building the session, e.g.
```scala
val spark = {
  NotebookSparkSession.builder()
    .master("yarn")
    .config("spark.executor.instances", "4")
    .config("spark.executor.memory", "2g")
    .getOrCreate()
}
```

Ensure the configuration directory of the cluster is set in `HADOOP_CONF_DIR` or `YARN_CONF_DIR` in the environment
of the kernel, or is available at `/etc/hadoop/conf`. This directory should contain files like `core-site.xml`,
`hdfs-site.xml`, … Like `SPARK_HOME` above, these variables can be passed to the kernel with the `--env` option
when installing almond.

If a Spark distribution is available on the machine running the kernel, it can be re-used by setting `SPARK_HOME`, as
described in [Re-using a Spark distribution via `SPARK_HOME`](#re-using-a-spark-distribution-via-spark_home).
Its JARs are then passed to YARN via `spark.yarn.jars`.
