---
title: Installing from sources
---

## Prerequisites

Ensure a JDK 8 is installed on your machine
```bash
$ java -version
java version "1.8.0_121"
Java(TM) SE Runtime Environment (build 1.8.0_121-b13)
Java HotSpot(TM) 64-Bit Server VM (build 25.121-b13, mixed mode)
$ javac -version
javac 1.8.0_121
```

Ensure an [sbt](https://scala-sbt.org) launcher is installed
```bash
$ sbt -help
Usage: sbt [options]

[…]
```
If it's not, [sbt-extras](https://github.com/paulp/sbt-extras) or the default installer from [the sbt website](https://scala-sbt.org) should do.

Check-out the sources with
```bash
$ git clone https://github.com/almond-sh/almond.git
$ cd almond
```

## Setup

From the almond sources, run
```bash
$ sbt jupyterStart shell
```

`jupyterStart` generates a [sbt-pack](https://github.com/xerial/sbt-pack)
distribution of the kernel under `modules/scala-kernel/target/pack`
(like `sbt scala-kernel/pack` would do), it also generates a `kernel.json`
to run almond from that distribution, then runs `jupyter lab` with
the `JUPYTER_PATH` environment variable pointing to that `kernel.json`. In the
launched `jupyter lab`, the kernel with id `scala` and name `Scala (sources)`
corresponds to the almond sources.

This jupyter server is automatically killed when sbt exits, or can be manually
stopped by typing `jupyterStop` at the sbt prompt.

Automatically re-generate the kernel when sources change by typing
```bash
> ~scala-kernel/pack
```
at the sbt prompt. Re-starting the kernel in the jupyter UI should then pick
the newly generated one.

You can adjust the Jupyter command by changing it in `build.sbt` (look
for `jupyterCommand`).

Note that that approach does _not_ benefit from the ClassLoader isolation features
that the coursier bootstrap-based approach allows for. All the almond
dependencies are also seen from the user session.

## Alternative launcher-based setup

This setup generates a launcher very similar to the one that can be generated
from the `coursier bootstrap` command of the
[installation instructions](quick-start-install.md). This launcher
benefits from the ClassLoader isolation that coursier bootstraps allow,
effectively isolating the kernel-specific dependencies from the user
facing ones. (To be more specific, this isolates the dependencies
of the `scala-kernel` module, that aren't dependencies of the `scala-kernel-api`
module - currently, this includes scalameta dependencies in particular.)

### Compiling

Compile and publish the kernel locally with
```bash
$ sbt publishLocal
```
This installs the kernel artifacts under your `~/.ivy2/local` local repository. The kernel artifacts should land under `~/.ivy2/local/sh.almond/` in particular. In the output of `sbt publishLocal`, note the snapshot version the kernel is installed with. At the time of writing this, this version is `0.1.11-SNAPSHOT`.

### Installing

Create a launcher with
```bash
$ SCALA_VERSION=2.12.7 ALMOND_VERSION=0.1.11-SNAPSHOT
$ coursier bootstrap \
    almond:$ALMOND_VERSION \
    --scala $SCALA_VERSION \
    --sources --default=true \
    -o almond-snapshot --embed-files=false
```
Compared to [the default installation instructions](quick-start-install.md), `--embed-files=false` is added as an option. This makes the generated launcher directly rely on files under `~/.ivy2/local`, rather than copying those in the launcher. If the files under `~/.ivy2/local` are updated (e.g. with a new `sbt publishLocal`), just restarting the kernel is enough for these to be picked, which is useful for fast iterations during development.

Then install the kernel with
```bash
$ ./almond-snapshot --install \
    --id scala-snapshot \
    --display-name "Scala (snapshot)"
```

Optionally, change the log level with e.g. `--log debug`. If you'd like these logs to go to a distinct file rather than in the console, pass e.g. `--log-to "/path/to/log-file.txt"`.

### Development cycle

Once the kernel is installed this way, one can update its artifacts with `sbt publishLocal`. These are taken into account by restarting the kernel.

Re-generating an installer, and installing it, is only necessary if the dependencies of the kernel are changed / updated, or if the kernel version changes. It is safer to re-generate an installer and install it after a `git pull` in particular.

## sbt crash course

Running `sbt` with no options starts an sbt shell
```
$ sbt
[…]
> 
```

At the sbt prompt, type
- `compile` to compile the main sources,
- `test:compile` to compile both the main and test sources,
- `test` to run all the tests,
- `publishLocal` to publish the projects locally (these land under `~/.ivy2/local`).

All these commands can be prefixed with a project id, like `kernel/test` or `interpreter/publishLocal`. List the available projects with `projects`.

Note that sbt knows about the dependencies between commands, so that you can directly run `test`. This triggers a compilation of the main and test sources if needed. Sources are automatically compiled incrementally.

`~` can be added as prefix to watch sources, like `~test:compile` or `~kernel/publishLocal`.

To run one or several commands from your shell rather than the sbt prompt, pass those to `sbt`, like
```
$ sbt "~test:compile"
$ sbt interpreter/test "~publishLocal"
```

Note that the sbt start up time is incurred each time you type `sbt` in your shell. Running it once, like `$ sbt`, then entering commands at the sbt prompt allows to avoid this.

Select a specific scala version, for example `2.12.9`, with `++2.12.9`. Example of use
```
$ sbt ++2.12.9 "~test:compile"
$ sbt
> ++2.12.9
> interpreter/test
```

List the available scala versions with the `show crossScalaVersions` command, like
```
$ sbt "show crossScalaVersions"
$ sbt
> show crossScalaVersions
```

Note that `show crossScalaVersions` is quoted when passed to sbt from the shell, so that sbt interprets it as a single command.

