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
import $ivy.`sh.almond::almond-spark:@VERSION@` // Not required since almond 0.7.0 (will be automatically added when importing spark)
```

Usually you want to disable logging in order to avoid polluting your cell outputs:

```scala
import org.apache.log4j.{Level, Logger}
Logger.getLogger("org").setLevel(Level.OFF)

```

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
