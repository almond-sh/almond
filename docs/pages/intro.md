---
title: Overview
hide_title: true
---

# Almond

*Almond* is a [Scala](https://scala-lang.org) kernel for [Jupyter](https://jupyter.org). It is formerly known as *jupyter-scala*.

It supports Scala 3, Scala 2.13, and Scala 2.12 (see [Versions](install-versions.md)), and features:
- all the [Ammonite](https://ammonite.io) niceties,
- [code completion and inspections](usage-completion.md),
- notebook configuration via [using directives](usage-directives.md), like dependencies, compiler options, or the Scala version,
- an [API](api.md) that libraries can rely on to interact with Jupyter front-ends,
- extensible [plotting](usage-plotting.md) support,
- [Spark support](usage-spark.md), relying on [almond-spark](https://github.com/almond-sh/almond-spark), with progress bars and cancellation of Spark jobs.

It also provides libraries allowing one to [write custom Jupyter kernels
in Scala](dev-custom-kernel.md).

*This is the documentation for version @VERSION@*.

