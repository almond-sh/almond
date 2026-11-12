---
title: Code completion
---

almond answers the
[completion](https://jupyter-client.readthedocs.io/en/stable/messaging.html#completion) and
[inspection](https://jupyter-client.readthedocs.io/en/stable/messaging.html#introspection)
requests of the
[Jupyter messaging protocol](https://jupyter-client.readthedocs.io/en/stable/messaging.html).
These are computed by the Scala compiler of the kernel, with the dependencies, imports, and
values of the notebook in scope. In JupyterLab, press Tab in a code cell to get completions
(see the [JupyterLab documentation of its completer](https://jupyterlab.readthedocs.io/en/stable/user/completer.html)),
and Shift+Tab to inspect what is under the cursor.

## What gets completed

Pressing Tab completes:
- the names in scope: those defined or imported by the cells that were run so far, those
  defined earlier in the current cell, and those of the default imports (`scala._`,
  `scala.Predef._`, `java.lang._`, …),
- the members of a value or of a package, after a `.`, like in `List(1, 2).ma` or
  `scala.collection.mut`,
- identifiers written between backticks, like `` `my-value` ``,
- with Scala 2, the classes and objects that aren't imported, when no name in scope matches.
  Typing `HashM` then offers `java.util.HashMap`, `scala.collection.immutable.HashMap`,
  `scala.collection.mutable.HashMap`, and so on, which are inserted with their fully
  qualified names,
- the dependencies in `import $ivy`, where organizations, module names, and versions are
  looked up in the repositories of the kernel, like
  ```scala
  import $ivy.`org.typelevel::cats-co
  ```

Implicit definitions, names containing `$` (internal to the compiler or to almond), and a
few methods of `Any` and `Object` (`##`, `eq`, `ne`, `synchronized`, `wait`, `notify`, …)
are left out of completions.

Along with each completion, almond sends its kind (class, method, value, package, …),
which JupyterLab shows next to it.

## Completions while typing

JupyterLab (4.0 and later) can also show completions as you type, like a REPL
does, instead of only when Tab is pressed. This is disabled by default. Enable it
from Settings → Settings Editor → Code Completion, by ticking "Enable
autocompletion" (see
[the JupyterLab documentation](https://jupyterlab.readthedocs.io/en/stable/user/completer.html#automatic-completion)).
The completer then opens after each character of a word that you type, with the
words of the editor and the completions of the kernel.

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

JupyterLab also has an
[inline completer](https://jupyterlab.readthedocs.io/en/stable/user/completer.html#inline-completer),
that shows suggestions as greyed out text. Its suggestions don't come from the completions
of the kernel. Its default provider suggests lines from the history of the kernel, which
almond doesn't send for now, so that only providers added by extensions make suggestions
with almond.

## Inspections

almond also answers the
[inspection requests](https://jupyter-client.readthedocs.io/en/stable/messaging.html#introspection)
of the Jupyter protocol. In JupyterLab, press Shift+Tab in a code cell to get the
signature or the type of what is under the cursor, along with its documentation, in a
[tooltip](https://jupyterlab.readthedocs.io/en/stable/user/notebook.html#user-tooltip).
Help → Show Contextual Help opens a panel that shows the same, and follows the cursor.
With Scala 3, right after an opening parenthesis, like in `List(1, 2).map(`, the method
being called is inspected.

The documentation of a class is read from the source JAR of its library, or, for
Scala 3 libraries, from their TASTy files. The source JARs of the dependencies of the
kernel and of the dependencies added with `import $ivy` or
[`//> using dep`](usage-directives.md) are available. Those of JARs added with
[`--extra-class-path`](install-options.md#--extra-class-path), `import $cp`, or
`interp.load.cp` aren't: add their source JARs to the class path too to get their
documentation, like
```scala
interp.load.cp(Seq(
  java.nio.file.Paths.get("/path/to/lib.jar"),
  java.nio.file.Paths.get("/path/to/lib-sources.jar")
))
```

## While a cell is running

Completions and inspections are computed in parallel with the cells being run, so that they
keep working while a long cell runs. They wait for the compilation of a cell to be done
though, as they rely on the same compiler.

## Multi-line input in consoles

almond also answers the
[code completeness requests](https://jupyter-client.readthedocs.io/en/stable/messaging.html#code-completeness)
of the Jupyter protocol. Console front-ends, like
[Jupyter console](https://jupyter-console.readthedocs.io), send those to decide what pressing
Enter does: run the code if it is complete, or start a new line, indented, if it isn't, like
after `def f(n: Int) = {`.

## Differences with language servers

Completions and inspections from almond aren't based on the
[Language Server Protocol](https://microsoft.github.io/language-server-protocol/) (LSP),
that IDEs and editors use, and that JupyterLab supports via extensions, such as
[jupyterlab-lsp](https://github.com/jupyter-lsp/jupyterlab-lsp) (see
[the JupyterLab documentation](https://jupyterlab.readthedocs.io/en/stable/user/lsp.html)).
A language server, like [Metals](https://scalameta.org/metals) for Scala, is a process
running alongside the editor, that reads the code being edited and the build definition of
a project. almond instead answers requests of the Jupyter protocol itself, from the kernel,
which has a few consequences:
- completions take into account the actual state of the session: the dependencies loaded so
  far, and what the cells that were run defined, whatever the order they were run in,
- conversely, what is defined in cells that weren't run yet isn't known, and isn't completed,
- there are no LSP features beyond completions and inspections, like errors and warnings
  shown as you type (these only show up when running a cell), go to definition, find
  references, or renaming.

almond doesn't ship a language server for notebooks.
