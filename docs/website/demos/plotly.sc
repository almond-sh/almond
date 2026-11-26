// Front page demo: "Plotting libraries"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).
// Plots render in JupyterLab via the jupyterlab-plotly extension, from the plotly Python package.
// Once rendered, the 3D plot gets rotated, by dragging it.

// %demo height=772

// %%
//> using dep "org.plotly-scala::plotly-almond:0.8.5"
import plotly._, plotly.element._, plotly.layout._, plotly.Almond._

// %% wait=.js-plotly-plot drag=.js-plotly-plot:-180,120 pause=2500
Surface()
  .withZ(Seq.tabulate(61, 61) { (i, j) =>
    val r = math.hypot(i - 30, j - 30) / 3
    math.cos(r) * math.exp(-r / 5)
  })
  .withShowscale(false)
  .plot(Layout().withHeight(360).withMargin(Margin(0, 0, 0, 0)))
