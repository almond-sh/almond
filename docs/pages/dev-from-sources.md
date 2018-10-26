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

## Compiling

Check-out the sources with
```bash
$ git clone https://github.com/almond-sh/almond.git
$ cd almond
```

Compile and publish the kernel locally with
```bash
$ sbt publishLocal
```
This installs the kernel artifacts under your `~/.ivy2/local` local repository. The kernel artifacts should land under `~/.ivy2/local/sh.almond/` in particular. In the output of `sbt publishLocal`, note the snapshot version the kernel is installed with. At the time of writing this, this version is `0.1.11-SNAPSHOT`.

## Installing

Create a launcher with
```bash
$ SCALA_VERSION=2.12.7 ALMOND_VERSION=0.1.11-SNAPSHOT
$ coursier bootstrap \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond-snapshot --embed-files=false
```
Compared to [the default installation instructions](quick-start-install.md), `--embed-files=false` is added as an option. This makes the generated launcher directly rely on files under `~/.ivy2/local`, rather than copying those in the launcher. If the files under `~/.ivy2/local` are updated (e.g. with a new `sbt publishLocal`), just restarting the kernel is enough for these to be picked, which is useful for fast iterations during development.

Then install the kernel with
```bash
$ ./almond-snapshot --install \
    --id scala-snapshot \
    --display-name "Scala (snapshot)" \
    --log debug \
    --log-to "$(pwd)/almond-snapshot-log.txt"
```

Optionally, change the log level with e.g. `--log debug`. If you'd like these logs to go to a distinct file rather than in the console, pass e.g. `--log-to "/path/to/log-file.txt"`.

## Development cycle

Once the kernel is installed this way, one can update its artifacts with `sbt publishLocal`. These are taken into account by restarting the kernel.

Re-generating an installer, and installing it, is only necessary if the dependencies of the kernel are changed / updated, or if the kernel version changes. It is safer to re-generate an installer and install it after a `git pull` in particular.
