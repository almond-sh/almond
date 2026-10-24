---
title: Installation
---

## Install the almond kernel

A Java Virtual Machine (JVM) needs to be installed on your system. You
can check if a JVM is installed by running
```text
$ java -version
java version "1.8.0_121"
Java(TM) SE Runtime Environment (build 1.8.0_121-b13)
Java HotSpot(TM) 64-Bit Server VM (build 25.121-b13, mixed mode)
```
If you don't have a JVM yet, we recommend installing [AdoptOpenJDK](https://adoptopenjdk.net) version 8.

Almond can then be fetched and installed with [coursier](http://get-coursier.io),
like
```text
$ curl -Lo coursier https://git.io/coursier-cli
$ chmod +x coursier
$ ./coursier launch --use-bootstrap almond -- --install
$ rm -f coursier
```

Note the `--` before `--install`, separating the arguments passed to Almond
from the ones handled by coursier.

You can specify explicit Almond and / or Scala versions, like
```text
$ ./coursier launch --use-bootstrap almond:0.10.0 --scala 2.12.11 -- --install
```

Short Scala versions, like just `2.12` or `2.13`, are accepted too.
The available versions of Almond can be found [here](https://github.com/almond-sh/almond/releases).
Not all Almond and Scala versions combinations are available.
See the possible combinations [here](install-versions.md).


<details>
<summary>Equivalent Windows command</summary>
```bat
> bitsadmin /transfer downloadCoursierCli https://git.io/coursier-cli "%cd%\coursier"
> bitsadmin /transfer downloadCoursierBat https://git.io/coursier-bat "%cd%\coursier.bat"
> .\coursier launch --use-bootstrap almond -M almond.ScalaKernel -- --install
```
</details>

Once the kernel is installed, you can use it within Jupyter or nteract.

## Getting help about the launcher

Pass `--help` instead of `--install`, like
```text
$ ./coursier launch --use-bootstrap almond -- --help
```

## Update the almond kernel

To update the almond kernel, just re-install it, but passing the `--force` option to almond (like `./coursier launch --use-bootstrap almond -- --install --force`). That will override any previous almond (or kernel with name `scala`).

## Uninstall the almond kernel

To uninstall the almond kernel, use `jupyter kernelspec remove scala`.
