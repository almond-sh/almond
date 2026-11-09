---
title: Plotting
---

## plotly-scala

[plotly-scala](https://github.com/alexarchambault/plotly-scala) provides Scala bindings for
[plotly.js](https://plotly.com/javascript), with a module for almond, `plotly-almond`:
```scala
import $ivy.`org.plotly-scala::plotly-almond:0.7.6`
import plotly._, plotly.element._, plotly.layout._, plotly.Almond._

val trace1 = Scatter(Seq(1, 2, 3, 4), Seq(10, 15, 13, 17))
val trace2 = Scatter(Seq(1, 2, 3, 4), Seq(16, 5, 11, 9))

plot(Seq(trace1, trace2))
```

plotly-scala isn't published for Scala 3, but its Scala 2.13 artifacts work from Scala 3
kernels. Use ``import $ivy.`org.plotly-scala:plotly-almond_2.13:0.7.6` `` there (note the single
`:` before the module name, followed by the explicit `_2.13` suffix).

By default, the plots load plotly.js from a CDN. To embed it in the notebook instead, so that
plots work without an internet connection, call `init(offline=true)` before plotting.

See the [plotly-scala example notebook](https://github.com/almond-sh/almond/blob/main/examples/plotly-scala.ipynb)
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

## Other libraries

Any library that renders plots as images or HTML can be used, via
[displays](api-jupyter.md#display), like
```scala
Image(pngBytes).withFormat(Image.PNG)
Svg(svgContent)
Html(htmlContent)
```
