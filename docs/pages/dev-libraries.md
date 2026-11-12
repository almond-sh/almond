---
title: Writing libraries interacting with Jupyter
---

Libraries can interact with Jupyter front-ends when they're loaded in an Almond session,
like plotting libraries such as [plotly-scala](usage-plotting.md#plotly-scala) do. They can

- display things in the front-end, update or clear them later on,
- control how values of their types are shown, when they're the results of cells,
- request input from users, or exchange messages with JavaScript code running in the front-end.

## Accessing the Almond API

Libraries get access to the Almond API by depending on the `scala-kernel-api` module, as a
"provided" dependency, like
```scala
libraryDependencies += "sh.almond" %% "scala-kernel-api" % "@VERSION@" % Provided
```
The API instance, `almond.api.JupyterApi`, can then be requested implicitly by the methods
of the library, so that users don't have to pass it themselves. See
[Access API instances](api-access-instances.md#from-a-library) for more details, and the
[Almond Jupyter API](api-jupyter.md) page for what this API offers.

## Displaying values of library types

To have values of a type displayed in a rich way when they're the results of cells, register a
[jvm-repr](https://github.com/almond-sh/almond/tree/main/modules/api/src/main/java/jupyter)
displayer for that type. Such displayers return a map from MIME types to content, like
```scala
case class Matrix(values: Seq[Seq[Double]])

jupyter.Displayers.register(
  classOf[Matrix],
  new jupyter.Displayer[Matrix] {
    def display(m: Matrix): java.util.Map[String, String] = {
      val rows = m.values.map(_.map(v => s"<td>$v</td>").mkString("<tr>", "", "</tr>"))
      java.util.Collections.singletonMap(jupyter.MIMETypes.HTML, rows.mkString("<table>", "", "</table>"))
    }
  }
)
```
The registration needs to run before such values are displayed, for example from the
initialization of an object of the library that users call first. Almond ships the jvm-repr
classes, in their original `jupyter` package, so they come with the `scala-kernel-api` module. As
displayers don't depend on Almond itself, other JVM kernels supporting jvm-repr display these
values too.

Whether such values are displayed when assigned to variables, or only when they're the result of a
bare expression, can be changed via `almond.display.ResultDisplay`, see
[Showing results](api-access-instances.md#showing-results).

## Example

[almond-sh/example-library-jupyter-api](https://github.com/almond-sh/example-library-jupyter-api)
is an example of a library relying on the Almond API, along with a notebook using it.
