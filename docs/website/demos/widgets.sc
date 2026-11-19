// Front page demo: "Interactive widgets"
// Each "// %%" line starts a cell, typed then run by record.mjs (see README.md).
// Widgets render in JupyterLab via the jupyterlab_widgets extension. They need Almond 0.16.0 or
// later.

// %demo height=460

// %%
//> using dep "sh.almond::widgets:0.1.0"
import almond.widgets._

// %% drag=.noUi-handle:165,-95 pause=2500
interact(IntSlider(value = 5, max = 25)) { n =>
  s"$n! = ${(1 to n).map(BigInt(_)).product}"
}
