---
title: Code completion
---

almond answers the completion requests of the Jupyter protocol, from the Scala
compiler, with the dependencies, imports, and values of the notebook in scope. In
JupyterLab, press Tab in a code cell to get completions.

## Completions while typing

JupyterLab (4.0 and later) can also show completions as you type, like a REPL
does, instead of only when Tab is pressed. This is disabled by default. Enable it
from Settings → Settings Editor → Code Completion, by ticking "Enable
autocompletion". The completer then opens after each character of a word that you
type, with the words of the editor and the completions of the kernel.

JupyterLab drops the completions of the kernel if they take longer than its
"Default timeout for a provider" setting, one second by default. The first
completion after a kernel starts, or a completion while a cell is being compiled,
can take longer than that: raise this timeout in the same settings section if
completions sometimes go missing.

To make these the defaults of a JupyterLab installation, write them to an
`overrides.json` file in its application settings directory (usually
`<sys.prefix>/share/jupyter/lab/settings`, see the
[JupyterLab documentation](https://jupyterlab.readthedocs.io/en/stable/user/directories.html#overridesjson)):
```json
{
  "@jupyterlab/completer-extension:manager": {
    "autoCompletion": true,
    "providerTimeout": 3000
  }
}
```

The [almond Docker images](try-docker.md) ship with these defaults.

## Inspections

almond also answers the inspection requests of the Jupyter protocol. In JupyterLab,
press Shift+Tab in a code cell to get the signature or the type of what is under the
cursor, along with its documentation. With Scala 3, right after an opening parenthesis,
like in `List(1, 2).map(`, the method being called is inspected.

The documentation of a class is read from the source JAR of its library, or, for
Scala 3 libraries, from their TASTy files. The source JARs of the dependencies of the
kernel and of the dependencies added with `import $ivy` or `//> using dep` are
available. Those of JARs added with `--extra-class-path`, `import $cp`, or
`interp.load.cp` aren't: add their source JARs to the class path too to get their
documentation, like
```scala
interp.load.cp(Seq(
  java.nio.file.Paths.get("/path/to/lib.jar"),
  java.nio.file.Paths.get("/path/to/lib-sources.jar")
))
```
