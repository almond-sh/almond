---
title: Writing custom kernels
---

The sources of the scala kernel have such an example demo kernel under [this directory](https://github.com/jupyter-scala/jupyter-scala/tree/c6bc94a397196be52232cc833e1095ef5e6264d2/modules/echo).

See [build.sbt](https://github.com/jupyter-scala/jupyter-scala/blob/c6bc94a397196be52232cc833e1095ef5e6264d2/build.sbt#L100-L106) for its dependencies (currently, only
[case-app](https://github.com/alexarchambault/case-app), `"com.github.alexarchambault" %% "case-app" % "2.0.0-M2"`, and the `kernel` module, `"sh.almond" %% "kernel" % "0.1.3"`).

This demo kernel mainly consists in [`EchoInterpreter`](https://github.com/jupyter-scala/jupyter-scala/blob/c6bc94a397196be52232cc833e1095ef5e6264d2/modules/echo/src/main/scala/almond/echo/EchoInterpreter.scala),
an implementation of [`almond.interpreter.Interpreter`](https://github.com/jupyter-scala/jupyter-scala/blob/c6bc94a397196be52232cc833e1095ef5e6264d2/modules/shared/interpreter/src/main/scala/almond/interpreter/Interpreter.scala),
and in [`EchoKernel`](https://github.com/jupyter-scala/jupyter-scala/blob/c6bc94a397196be52232cc833e1095ef5e6264d2/modules/echo/src/main/scala/almond/echo/EchoKernel.scala), a small
application that either installs the kernel, or actually runs it when it is launched by Jupyter.

When implementing a custom kernel, you may want to re-use almost as is `EchoKernel`, instantiating your own `Interpreter` instead of `EchoInterpreter`. You may optionally handle command-line arguments differently,
or add extra command-line options to pass to your `Interpreter` implementation.

For the kernel installation to work out-of-the-box, the kernel has to be launched via [coursier](http://get-coursier.io) in one way or another. To test your kernel locally,
publish it locally via a `publishLocal` from sbt. Note its organization, module name, and version. Then create a launcher for your kernel with
```
$ coursier bootstrap --embed-files=false organization:module-name_2.1?:version -o kernel
```
and install your kernel with
```
$ ./kernel --install
```
Your kernel should then be detected by [Jupyter Notebook](https://github.com/jupyter/notebook) or [nteract](https://nteract.io).
