---
title: Plotting
---

Several Scala and Java plotting libraries work from Almond. They either output specifications
that Jupyter front-ends render interactively ([plotly-scala](#plotly-scala),
[Vega and Vega-Lite](#vega-and-vega-lite)), or images ([nspl, XChart, …](#image-based-libraries)).
The [Almond examples](https://github.com/almond-sh/examples) have
[example notebooks](https://github.com/almond-sh/examples/tree/main/notebooks/visualization) for most of them.

## plotly-scala

[plotly-scala](https://github.com/alexarchambault/plotly-scala) provides Scala bindings for
[plotly.js](https://plotly.com/javascript), with a module for Almond, `plotly-almond`:
```scala
//> using dep "org.plotly-scala::plotly-almond:0.8.5"

import plotly._, plotly.element._, plotly.layout._, plotly.Almond._

val trace1 = Scatter(Seq(1, 2, 3, 4), Seq(10, 15, 13, 17))
val trace2 = Scatter(Seq(1, 2, 3, 4), Seq(16, 5, 11, 9))

plot(Seq(trace1, trace2))
```

plotly-scala isn't published for Scala 3, but its Scala 2.13 artifacts work from Scala 3
kernels. Use `//> using dep "org.plotly-scala:plotly-almond_2.13:0.8.5"` there (note the single
`:` before the module name, followed by the explicit `_2.13` suffix).

By default, the plots load plotly.js from a CDN. To embed it in the notebook instead, so that
plots work without an internet connection, call `init(offline=true)` before plotting.

See the [plotly-scala example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/visualization/plotly_examples.ipynb)
for more examples.

## Vega and Vega-Lite

JupyterLab and Jupyter Notebook render [Vega](https://vega.github.io/vega) and
[Vega-Lite](https://vega.github.io/vega-lite) specifications out of the box, from the
`application/vnd.vega.v5+json` and `application/vnd.vegalite.v5+json` MIME types. Pass a
specification to `Data` to display it, like
```scala
val values = Seq("A" -> 28, "B" -> 55, "C" -> 43, "D" -> 91)
val valuesJson = values
  .map { case (k, v) => "{\"category\": \"" + k + "\", \"amount\": " + v + "}" }
  .mkString("[", ", ", "]")

val spec =
  s"""{
     |  "$$schema": "https://vega.github.io/schema/vega-lite/v5.json",
     |  "data": {"values": $valuesJson},
     |  "mark": "bar",
     |  "encoding": {
     |    "x": {"field": "category", "type": "nominal"},
     |    "y": {"field": "amount", "type": "quantitative"}
     |  }
     |}""".stripMargin

Data("application/vnd.vegalite.v5+json" -> spec)
```

A convenient workflow consists in writing the specification in the
[Vega editor](https://vega.github.io/editor), with some sample data, then injecting
the actual data in it from Scala, with the JSON library of your choice.

### dedav4s

[dedav4s](https://github.com/Quafadas/dedav4s) builds on that workflow, from Scala 3. It parses
a specification at compile time, and lets you change any of its fields in a type-safe way. Its
`dedav4s_notebook` module shows plots in Almond, along with a static SVG for viewers that don't
run JavaScript, like GitHub:
```scala
//> using dep "io.github.quafadas::dedav4s_notebook:0.10.9"

import io.github.quafadas.plots.SetupVegaNotebook.{*, given}

val barChart = VegaPlot.fromString("""{
  "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
  "data": {"values": [{"category": "A", "amount": 28}]},
  "mark": "bar",
  "encoding": {
    "x": {"field": "category", "type": "nominal"},
    "y": {"field": "amount", "type": "quantitative"}
  }
}""")

barChart.plot(
  _.data.values := Seq(
    (category = "A", amount = 28),
    (category = "B", amount = 55),
    (category = "C", amount = 43)
  )
)
```

See the [dedav4s example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/visualization/dedav4s.ipynb)
for more examples.

### Smile

The machine learning library [Smile](https://haifengl.github.io) can also generate Vega-Lite
specifications, via its `smile.plot.vegalite` package. Note that it is licensed under the GPL, and
that its recent versions require Java 25.

## Image-based libraries

Libraries that draw plots with Java 2D can render them as `BufferedImage`s, that
`Image.fromRenderedImage` shows in the notebook. Those that output SVG or bitmap images
can be shown with the [`Svg` and `Image` displays](api-jupyter.md#display).

### nspl

[nspl](https://github.com/pityka/nspl) is a Scala 2.13 and Scala 3 plotting library, that renders
plots as bitmaps or as vector images:
```scala
//> using dep "io.github.pityka::nspl-awt:0.20.0"

import org.nspl._
import org.nspl.awtrenderer._

val points = Seq.tabulate(100)(i => i.toDouble -> math.sin(i / 10.0))
val plot = xyplot(points -> line())(par.withMain("A sine wave"))

Image(renderToByteArray(plot.build, width = 800)).withFormat(Image.PNG)
```

See the [nspl example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/visualization/nspl.ipynb)
for more examples.

### XChart

[XChart](https://github.com/knowm/XChart) is a Java charting library, with few dependencies:
```scala
//> using dep "org.knowm.xchart:xchart:4.0.4"

import org.knowm.xchart._

val xs = (0 to 100).map(_ / 10.0).toArray
val chart = new XYChartBuilder().width(800).height(450).title("A sine wave").build()
chart.addSeries("sin", xs, xs.map(math.sin))

Image.fromRenderedImage(BitmapEncoder.getBufferedImage(chart), Image.PNG)
```

See the [XChart example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/visualization/xchart.ipynb)
for more examples. Other Java 2D-based libraries, like [JFreeChart](https://www.jfree.org/jfreechart)
(via `JFreeChart#createBufferedImage`), can be used the same way.

### EvilPlot

[EvilPlot](https://github.com/cibotech/evilplot) is a Scala 2.12 and 2.13 plotting library. It
isn't very active anymore (its last release dates from 2024), but still works fine from Almond. See its
[example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/visualization/evilplot.ipynb).

## Matplotlib

[ScalaPy](https://scalapy.dev) lets you call Python libraries from Scala, including
[Matplotlib](https://matplotlib.org). See the
[ScalaPy example notebook](https://github.com/almond-sh/examples/blob/main/notebooks/scalapy.ipynb).

## Other libraries

Any library that renders plots as images or HTML can be used, via
[displays](api-jupyter.md#display), like
```scala
Image(pngBytes).withFormat(Image.PNG)
Svg(svgContent)
Html(htmlContent)
```
