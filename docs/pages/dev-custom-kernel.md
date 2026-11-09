---
title: Writing custom kernels
---

The `kernel` module of almond implements the Jupyter protocol, independently of the
Scala interpreter of almond. It can be used to write Jupyter kernels in Scala, for the
language of your choice.

The almond sources contain a demo kernel relying on it, the
[echo kernel](https://github.com/almond-sh/almond/tree/main/modules/echo). It depends on
- the `kernel` module, `"sh.almond" %% "kernel" % "@VERSION@"`,
- [case-app](https://github.com/alexarchambault/case-app), to parse its command-line arguments
  (case-app can be replaced by the command-line parsing library of your choice).

You can try it with
```text
$ cs launch --use-bootstrap sh.almond:echo_3:@VERSION@ -- --install
```
then pick the "Echo" kernel in Jupyter.

The echo kernel mainly consists in
- [`EchoInterpreter`](https://github.com/almond-sh/almond/blob/main/modules/echo/src/main/scala/almond/echo/EchoInterpreter.scala),
  an implementation of
  [`almond.interpreter.Interpreter`](https://github.com/almond-sh/almond/blob/main/modules/shared/kernel/src/main/scala/almond/interpreter/Interpreter.scala),
  that runs the code of cells (here, it just echoes it back), and optionally answers completion
  and inspection requests,
- [`EchoKernel`](https://github.com/almond-sh/almond/blob/main/modules/echo/src/main/scala/almond/echo/EchoKernel.scala),
  a small application that either installs the kernel, when passed `--install`, or actually
  runs it, when Jupyter launches it with a connection file.

When implementing a custom kernel, you may want to re-use `EchoKernel` almost as is,
instantiating your own `Interpreter` instead of `EchoInterpreter`. You may optionally handle
command-line arguments differently, or add extra command-line options to pass to your
`Interpreter` implementation.

For the kernel installation to work out-of-the-box, the kernel should be launched via
[coursier](https://get-coursier.io) in one way or another, like the almond kernel is. (It is
possible nonetheless to rely on e.g. uber JARs, by tweaking the `arg` or `command` fields of
the installation options, see [`--arg` / `--command`](install-options.md#--arg----command).)
To test your kernel locally, publish it locally (with `publishLocal` with sbt, or Mill, or
`scala-cli publish local`), note its organization, module name, and version, then install it with
```text
$ cs launch --use-bootstrap organization:module-name_3:version -- --install
```
Your kernel should then be listed in [JupyterLab](https://jupyterlab.readthedocs.io) and
[Jupyter Notebook](https://jupyter-notebook.readthedocs.io).
