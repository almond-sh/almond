// Front page demo: "APIs to interact with Jupyter front-ends"
// Each "// %%" line starts a cell, typed then run by record.mjs.

// %%
val bar = ProgressBar(0, 100)
  .withLabel("Step {progress} / {total}")

// %%
for (i <- 1 to 100) {
  Thread.sleep(25L)
  bar.withProgress(i).update()
}

// %%
Markdown("**Done!** All 100 steps completed.")
