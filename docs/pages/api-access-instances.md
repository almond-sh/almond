---
title: Access API instances
---

## From the Jupyter cells

These can be accessed directly by their names, like
```scala
interp
repl
kernel
```

or via implicits, like
```scala
implicitly[ammonite.interp.api.InterpAPI]
implicitly[ammonite.repl.api.ReplAPI]
implicitly[almond.api.JupyterApi]
```

## From a library

To access API instances from libraries, depend on either
- `sh.almond.ammonite:ammonite-repl-api_@SCALA_BINARY_VERSION@:@AMMONITE_VERSION@` for [`ammonite.interp.api.InterpApi`](api-ammonite.md#interpapi) and [`ammonite.repl.api.ReplAPI`](api-ammonite.md#replapi),
- `sh.almond:scala-kernel-api_@SCALA_BINARY_VERSION@:@VERSION@` for [`almond.api.JupyterApi`](api-jupyter.md#jupyterapi).

You can depend on those libraries as "provided" dependencies, as these libraries
are guaranteed to be already loaded by almond itself.

In practice, you can add something along those lines in `build.sbt`,
```scala
libraryDependencies ++= Seq(
  "sh.almond.ammonite" %% "ammonite-repl-api" % "@AMMONITE_VERSION@" % Provided, // for ammonite.interp.api.InterpApi and ammonite.repl.api.ReplAPI
  "sh.almond" %% "scala-kernel-api" % "@VERSION@" % Provided // for almond.api.JupyterApi
)
```

You can then write methods looking for implicit
API instances, like
```scala
def displayTimer()(implicit kernel: almond.api.JupyterApi): Unit = {
  val count = 5
  val id = java.util.UUID.randomUUID().toString
  kernel.publish.html(s"<b>$count</b>", id)
  for (i <- (0 until count).reverse) {
    Thread.sleep(1000L)
    kernel.publish.updateHtml(s"<b>$i</b>", id)
  }
  Thread.sleep(200L)
  kernel.publish.updateHtml(Character.toChars(0x1f981).mkString, id)
}
```

Users can then load the library defining this method, and call it themselves
from a notebook. The library can interact with the front-end, without overhead
for users.

A library defining this method, along with instructions to set up the library and
a notebook using it, are available in
[this repository](https://github.com/almond-sh/example-library-jupyter-api).

## Showing results

By default, almond shows both the results of bare expressions, like `res0: Int = 2`
for `1 + 1`, and the values of named definitions, like `n: Int = 2` for
`val n = 1 + 1`. Values with a rich representation ([displays](api-jupyter.md#display),
values with a [jvm-repr](https://github.com/jupyter/jvm-repr) displayer) are displayed
via it, rather than printed as text.

Libraries can change that for their own types via `almond.display.ResultDisplay`, like
```scala
import almond.display.ResultDisplay

// Handles, only printed when they're assigned to a variable
ResultDisplay.register(classOf[Handle], expressions = ResultDisplay.Mode.Hidden)

// Widgets, only displayed when they're the result of a bare expression,
// and printed as text when they're assigned to a variable
ResultDisplay.register(classOf[Widget], definitions = ResultDisplay.Mode.Text)
```

Like jvm-repr displayers, these settings apply to sub-classes too, and are looked up
from the static type of values.
