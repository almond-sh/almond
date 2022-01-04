---
title: Plotting
---
There are two good plotting strategies for Almond. 

1. [plotly scala](https://github.com/alexarchambault/plotly-scala)
2. Vega / Lite

Plotly scala has it's own documentationv via the link above. Vega / lite is built into juypter lab. 

To display a vega / lite spec, we need to set it's mime-type correclty, and jupyter will do the rest. Let's assume that ```spec```is a [valid vega spec](https://vega.github.io/vega/examples/bar-chart.vg.json), and vlspec is a [valid vega lite spec](https://vega.github.io/vega-lite/examples/bar.html)

```
kernel.publish.display(
  almond.interpreter.api.DisplayData(
    data = Map(      
      "application/vnd.vega.v5+json" -> spec
    )
  )  
)
```

This has been further simplified for your convienience. Either of these for vega; 

```
almond.display.VegaLite(vlSpec)

Display.vega(spec)
```
or for Vega Lite

```
almond.display.VegaLite(vlSpec)

Display.vegaLite(vlSpec)
```