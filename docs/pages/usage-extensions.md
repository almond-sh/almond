---
title: JupyterLab extensions
---

Besides [completions and inspections](usage-completion.md), Almond supports a few
JupyterLab extensions.

## Formatting cells with scalafmt

The [almond-scalafmt](https://github.com/almond-sh/almond-scalafmt) extension formats code
cells with [scalafmt](https://scalameta.org/scalafmt). Install it in the Python environment
of JupyterLab with
```text
$ pip install almond-scalafmt
```

It adds two commands to notebooks running Almond, "Format Selected Cells with scalafmt" (also
available from the context menu of code cells) and "Format All Code Cells with scalafmt". Find
them in the command palette by typing `scalafmt`. The formatting itself is done by the kernel,
which can be prevented by passing [`--scalafmt=false`](install-options.md#--scalafmt) when
installing it.

## Variable inspector

The [variable inspector](https://github.com/jupyterlab-contrib/jupyterlab-variableInspector)
extension lists the variables defined in a notebook, along with their types and values.
Install it in the Python environment of JupyterLab with
```text
$ pip install lckr-jupyterlab-variableinspector
```
then open it with "Open Variable Inspector", from the context menu of a notebook or from the
command palette.

Almond starts keeping track of variables when the inspector is first opened, so the variables
of cells run before that aren't listed. Lazy vals and parameterless methods are listed as `[lazy]`
and `[def]`, without evaluating them. The matrix view of the inspector and deleting variables
from it aren't supported with Almond.

Pass [`--variable-inspector=true`](install-options.md#--variable-inspector) when installing the
kernel to keep track of variables from the start, or `--variable-inspector=false` to disable
variable tracking altogether.

## Debugger (experimental)

The [JupyterLab debugger](https://jupyterlab.readthedocs.io/en/latest/user/debugger.html), that
comes with JupyterLab, can debug the code of cells: set breakpoints by clicking on the left of
the lines of cells, step through code, and look at the call stack and at the values of
variables. It's enabled by default for kernels running on Java 17 or later. Pass
[`--debugger=false`](install-options.md#--debugger) when installing the kernel to disable it, like
```text
$ cs launch --use-bootstrap almond -- --install --debugger=false
```
or `--debugger` to enable it on older Java versions.
The kernel then runs with a JDWP agent, and needs to run on a JDK (rather than a JRE). On Java 9
and later, the agent only listens (on a random port of the loopback interface) when the debugger
gets enabled, until the kernel connects to it, and the kernel then keeps that connection open: no
other process can connect to the agent afterwards. Enable the debugger with the bug icon of the toolbar of a notebook.
Stepping into the methods of libraries opens their sources, read from their source JARs (when
these are in the class path of the kernel, like the source JARs of the dependencies added from
notebooks). The code Ammonite generates around cells (accessors of vals, code printing the results
of cells) is skipped when stepping.

The first time the debugger is enabled, the kernel fetches its debug adapter, based on
[scala-debug-adapter](https://github.com/scalacenter/scala-debug-adapter).

Only the kernels for a specific Scala version support it for now, not the kernel picking its
Scala version from the first cell of notebooks.
