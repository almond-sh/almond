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
