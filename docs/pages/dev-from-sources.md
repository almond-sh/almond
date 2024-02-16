---
title: Installing from sources
---

## Prerequisites

Ensure a JDK (Java Development Kit) is installed on your machine. Java versions 8 or 11
are recommended, with 8 as minimum version. If you don't already have a JDK installed,
you can install one by following the instructions on the
[AdoptOpenJDK website](https://adoptopenjdk.net), or by grabbing the
[coursier](https://get-coursier.io/docs/cli-installation.html#native-launcher) command-line
and using its [`cs java` command](https://get-coursier.io/docs/cli-java.html#setting-a-default-jvm-version).
Your OS package manager (`brew`, `apt`, …) may also offer to install a JDK for you.

Once a JDK is installed, you should be able to run the `java` command, like
```text
$ java -version
java version "1.8.0_121"
Java(TM) SE Runtime Environment (build 1.8.0_121-b13)
Java HotSpot(TM) 64-Bit Server VM (build 25.121-b13, mixed mode)
$ javac -version
javac 1.8.0_121
```

Check-out the sources with git:
```text
$ git clone https://github.com/almond-sh/almond.git
$ cd almond
```

Almond is built with [mill](https://com-lihaoyi.github.io/mill). A mill
launcher ships with almond, so that you don't need to install mill yourself.
We list below useful commands, to help get you started using mill to build almond.

## Run a Jupyter notebook server without installing a kernel

```text
$ ./mill -i dev.jupyterFast
```

This should
- build an almond launcher, then
- start JupyterLab in the current directory.

From the JupyterLab instance, select the kernel "Scala (sources)".

Optionally, pass a Scala version and / or JupyterLab options, like
```text
$ ./mill -i dev.jupyterFast 2.12.13
$ ./mill -i dev.jupyterFast --ip=192.168.0.1
$ ./mill -i dev.jupyterFast 2.12.13 --ip=192.168.0.1
```
(If specified, the Scala version needs to be passed first.)

## Build a kernel launcher

```text
$ ./mill dev.launcherFast
```

Once done building, this should print the path to the kernel launcher, like
`out/scala/scala-kernel/2.13.4/unixLauncherFast/dest/launcher`.

Optionally, pass a Scala version, like
```text
$ ./mill dev.launcherFast 2.12.13
```

You can then run that launcher to install it on your system:
```text
$ out/scala/scala-kernel/2.13.4/unixLauncherFast/dest/launcher --install
```
Pass `--help` or see [this page](install-options.md) for the available options.

## Watch for source changes

You can re-build a launcher upon source changes with
```text
$ ./mill -w dev.launcherFast
```

If you [ran a JupyterLab server from the almond sources](#run-a-jupyter-notebook-server-without-installing-a-kernel),
you can restart the kernel from a notebook via JupyterLab to pick a newly built launcher. If you passed a Scala
version to `./mill dev.jupyter`, beware to pass the same version to `./mill -w dev.launcher`.

## Useful commands

### List available Scala versions
```text
$ ./mill dev.scalaVersions
2.13.4
2.13.3
…
```

### Print the latest supported Scala 2.13 version
```text
$ ./mill dev.scala213
2.13.4
```

### Print the latest supported Scala 2.12 version
```text
$ ./mill dev.scala212
2.12.13
```

### Compile all modules for a Scala version
```text
$ ./mill '__[2.13.4].compile'
```

### Compile all modules for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.4].compile'
```

### Compile all tests for a Scala version
```text
$ ./mill '__[2.13.4].test.compile'
```

### Compile all tests for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.4].test.compile'
```

### Run all tests for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.4].test'
```

### Compile specific modules
```text
$ ./mill 'scala.scala-kernel[2.13.4].compile'
```

### Generate Metals configuration files

It is recommended to generate Metals configuration files manually, rather
than letting Metals load the project itself. In order to do that, run
```text
$ ./mill mill.contrib.Bloop/install
```

If you're using Metals from VSCode, you can then run the
"Metals: Connect to build server" command to take into account the newly
generated files.

If the command above takes too long to run, comment out Scala versions in
`deps.sc`. If no 2.12 versions are left, also comment out the few 2.12-specific
projects in `build.sc` (look for `212` to find them). Same if no 2.13 versions
are left (look for `213` to spot 2.13-specific projects).

### Generate IntelliJ IDEA configuration files

It is recommended to [manually generate IntelliJ configuration files](https://com-lihaoyi.github.io/mill/mill/Installation_IDE_Support.html#_intellij_idea_support),
rather than letting IntelliJ load the project itself. In order to do that, run
```text
$ ./mill mill.scalalib.GenIdea/idea
```

You can then open the project in IntelliJ.
IntelliJ should also automatically pick those files when they are overwritten.

Just like for Metals above, you may benefit from disabling all but one Scala
version (see above for more details).

## Validate the example notebooks

Example notebooks live under `examples/`. These are run
on the CI using nbconvert, and the resulting outputs are
compared to the committed ones. Any difference results
in the examples job on the CI to fail.

To validate the examples locally, run
```text
$ ./mill -i scala.examples.test
```

Optionally, you can pass a glob to filter notebook names:
```text
$ ./mill -i scala.examples.test 'almond.examples.Examples.scalapy*'
```
