---
title: Using directives
---

Like [Scala CLI](https://scala-cli.virtuslab.org/docs/reference/directives), Almond accepts
`//> using` directives at the beginning of cells, before any code, like
```scala
//> using dep "com.lihaoyi::os-lib:0.11.4"
//> using option "-Xfatal-warnings"

os.list(os.pwd)
```

Values can be quoted or not, and directives accepting several values accept them separated
by commas, like `//> using options "-unchecked", "-Wunused:imports"`.

## Kernel directives

These can be used in any cell, and are taken into account before the cell is compiled.

| Directive | Description |
|-----------|-------------|
| `//> using dep` | Add dependencies, like `//> using dep "com.lihaoyi::os-lib:0.11.4"`. Also available as `deps`, `dependencies`, `lib`, and `libs`. Ammonite's ``import $ivy.`…` `` syntax does the same, see [Load dependencies](api-ammonite.md#load-dependencies). |
| `//> using repository` | Add repositories to resolve dependencies from, like `//> using repository jitpack`, `//> using repository sonatype:snapshots`, `//> using repository m2Local`, or the URL of the root of a Maven repository. |
| `//> using option` | Add Scala compiler options, like `//> using option "-Xfatal-warnings"`. Also available as `options`. See [Compiler options](usage-compiler-options.md). |
| `//> using script` | Compile and run [Ammonite scripts](api-ammonite.md#load-scripts), like `//> using script path/to/script.sc`. Also available as `scripts`. |
| `//> using outputStyle` | Change how the results of the next cells are displayed: `default`, `last`, or `python`. See [`--output-style`](install-options.md#--output-style). |

## Launcher directives

The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
also accepts directives configuring the kernel it starts. These are only taken into account
in the first cells of a notebook, until a cell with actual code (not only directives, comments,
or blank lines) is run, which is when the kernel is started.

| Directive | Description |
|-----------|-------------|
| `//> using scala` | The Scala version of the kernel, like `//> using scala "@SCALA213_VERSION@"`. Short versions, like `2.13` or `3`, are accepted too. Defaults to the `--scala` option passed when installing the kernel. |
| `//> using jvm` | The JVM to run the kernel with, like `//> using jvm 21`, `//> using jvm graalvm:21`, or `//> using jvm system`. JVMs are downloaded by [coursier](https://get-coursier.io/docs/cli-java) if needed. |
| `//> using javaOpt` | Java options to pass to the kernel, like `//> using javaOpt "-Xmx4g"`. See [Memory](install-advanced.md#memory). |
| `//> using javaCmd` | The full `java` command to run the kernel with, like `//> using javaCmd "/path/to/java" "-Dfoo=bar"`. |

The kernel directives above can also be used in these first cells, and are then passed to the
kernel when it starts.

Launcher directives used after the kernel started, or with the former launcher, are ignored,
and a warning is printed.

### Custom directives

The newer launcher accepts custom directives, handled by an external command, passed with
`--custom-directive-group` when installing the kernel, like
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --install \
    --custom-directive-group spark:/path/to/handle-spark-directives
```

Directives whose key starts with the prefix (like `//> using spark.version "3.5.8"`, or
`//> using spark`) are then passed to that command, as the path of a JSON file, like
```json
{
  "entries": [{"key": "spark.version", "values": ["3.5.8"]}],
  "currentLauncherParameters": {"scala": "@SCALA213_VERSION@", "javaOptions": []},
  "currentKernelParameters": {"dependencies": []}
}
```
The command prints updates of these parameters on its standard output, as JSON, like
```json
{
  "launcherParameters": {"javaOptions": ["-Xmx4g"]},
  "kernelParameters": {"dependencies": ["org.apache.spark::spark-sql:3.5.8"]}
}
```
`launcherParameters` accepts the `jvm`, `javaOptions`, `scala`, and `javaCmd` fields, and
`kernelParameters` accepts the `dependencies`, `scalacOptions`, `extraRepositories`, `scripts`,
and `outputStyle` fields.
