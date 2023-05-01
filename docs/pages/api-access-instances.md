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
- `com.lihaoyi:ammonite-interp_@SCALA_VERSION@:@AMMONITE_VERSION@` for [`ammonite.interp.api.InterpApi`](api-ammonite.md#interpapi),
- `com.lihaoyi:ammonite-repl_@SCALA_VERSION@:@AMMONITE_VERSION@` for [`ammonite.repl.api.ReplAPI`](api-ammonite.md#replapi),
- `sh.almond:scala-kernel-api_@SCALA_VERSION@:@VERSION@` for [`almond.api.JupyterApi`](api-jupyter.md#jupyterapi).

You can depend on those libraries as "provided" dependencies, as these libraries
are guaranteed to be already loaded by almond itself.

In practice, you can add something along those lines in `build.sbt`,
```scala
libraryDependencies ++= Seq(
  ("com.lihaoyi" % "ammonite-interp" % "@AMMONITE_VERSION@" % Provided).cross(CrossVersion.full), // for ammonite.interp.api.InterpApi
  ("com.lihaoyi" % "ammonite-repl" % "@AMMONITE_VERSION@" % Provided).cross(CrossVersion.full), // for ammonite.repl.api.ReplAPI
  ("sh.almond" % "scala-kernel-api" % "@VERSION@" % Provided).cross(CrossVersion.full) // for almond.api.JupyterApi
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
