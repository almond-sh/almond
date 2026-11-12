// Front page demo: "Spark support"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).
// Spark needs the kernel to be started with --add-opens Java options.

// %demo height=640 warmup=true

// %%
//> using dep "org.apache.spark::spark-sql:3.5.8"
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
Configurator.setRootLevel(Level.OFF)

// %%
import org.apache.spark.sql._
val spark = NotebookSparkSession.builder()
  .master("local[4]")
  .getOrCreate()

// %%
val rdd = spark.sparkContext.parallelize(1 to 1000, 40)
val n = rdd
  .map { i =>
    Thread.sleep(10L)
    i + 1
  }
  .sum()

// %% pause=2500
import spark.implicits._
rdd
  .map(i => (i % 5, i))
  .toDF("key", "value")
  .groupBy("key")
  .sum("value")
  .orderBy("key")
  .show()
