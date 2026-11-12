// Front page demo: "Plotting libraries"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).
// Plots render in JupyterLab via the jupyterlab-plotly extension, from the plotly Python package.

// %demo height=600 warmup=true

// %%
//> using dep "org.plotly-scala::plotly-almond:0.8.5"
import plotly._, plotly.element._, plotly.layout._, plotly.Almond._

// %% wait=.js-plotly-plot pause=2500
Bar(Seq("Banana", "Apple", "Grapefruit"), Seq(10, 8, 5)).plot()
