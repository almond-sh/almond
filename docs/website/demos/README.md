# Front page demos

The animations of the front page of the website are recorded from a real JupyterLab, by
[`record.mjs`](record.mjs). It types and runs the cells of the demo scripts of this directory, and
records the result with [Playwright](https://playwright.dev) and ffmpeg.

## Demo scripts

Each `*.sc` file is a demo, whose name is the file name without its extension. `// %%` lines start
cells, and can be followed by options:
- `pause=3000`: how long to wait after the cell ran, in milliseconds (1200 by default),
- `wait=.js-plotly-plot`: a CSS selector to wait for in the cell, for outputs rendered
  asynchronously, like plots loading their JavaScript library,
- `drag=.noUi-handle:165,-95`: once the cell ran, drag the first element matching a CSS selector
  in the cell (here, the handle of a slider widget) horizontally, by each of the comma-separated
  offsets in turn (in CSS pixels), with a mouse pointer drawn in the page (like in the widgets
  demo, or in the plotly one, where dragging rotates a 3D plot). The mouse is then moved out of
  the output, so that hover effects, like plot tooltips, don't stay in the recording,
- `breakpoints=2,4`: in demos with `debugger=true`, set breakpoints on these lines of the cell
  (starting from 1), by clicking in the gutter of its editor, before running it,
- `debug=next,expand:xs,continue`: in demos with `debugger=true`, wait for the debugger to stop in
  the cell once it runs, then run these debugger actions in turn: `continue`, `next`, `stepIn`, and
  `stepOut` click the buttons of the call stack panel of the debugger sidebar (and wait for the
  debugger to stop again, or for the cell to be done running), and `expand:<name>` expands a
  variable in the variables panel. The last action needs to let the cell run until its end.

Before the first cell, a `// %demo` line can set options for the whole demo:
- `kernel=echo`: the kernel to run the demo with (the Scala kernel passed via `--kernel` by default),
- `width=640` / `height=600`: size of the browser viewport, in CSS pixels (640 x 520 by default),
- `warmup=true`: run the demo once before recording it, so that things like dependency downloads
  don't show up in the recording (dependency downloads, and their progress, show up otherwise),
- `debugger=true`: record the [JupyterLab debugger](https://jupyterlab.readthedocs.io/en/latest/user/debugger.html):
  the notebook toolbar is kept (with only the button enabling the debugger and the kernel name and
  status in it), the debugger gets enabled with that button before the first cell, and the
  recording shows the debugger sidebar along with the notebook. The kernel needs to support the
  debugger.

Other lines before the first cell are ignored.

```scala
// %demo height=480

// %%
val bar = ProgressBar(0, 100)
  .withLabel("Step {progress} / {total}")

// %% pause=2500
for (i <- 1 to 100) {
  Thread.sleep(25L)
  bar.withProgress(i).update()
}
```

## Recording

[`record.sh`](record.sh) installs the kernels of a released version of Almond in a temporary
directory (`ALMOND_VERSION`, 0.15.0 by default), then records all the demos with them, with the
light and dark themes of JupyterLab (set `THEMES=light` or `THEMES=dark` to record only one of
them):
```text
$ docs/website/demos/record.sh
```
The widgets demo needs Almond 0.16.0 or later, and the 0.1.0 release of
[almond-widgets](https://github.com/almond-sh/almond-widgets) (its current recording was made with
both built from sources, before they were released). The debugger demo runs on a Scala 3 kernel
(`SCALA3_VERSION`, 3.10.1 by default), that `record.sh` installs with `--debugger`, which needs a
version of Almond supporting the debugger (its current recording was made with Almond built from
sources, before its release).

Pass it the paths of some demos to only record those, and [`record.mjs`](record.mjs) options
(see below), like
```text
$ docs/website/demos/record.sh --ffmpeg /path/to/ffmpeg docs/website/demos/spark.sc
```

On GitHub Actions, the [Website demos workflow](../../../.github/workflows/website-demos.yml),
run by hand (typically after a release), runs `record.sh`, and opens a pull request with the new
recordings:
```text
$ gh workflow run website-demos.yml -f almond-version=0.15.0
```

`record.sh` requires
- [coursier](https://get-coursier.io) (`cs`), [uv](https://docs.astral.sh/uv), and Node.js,
- a Chrome or Chromium: the one Playwright downloads (`npx playwright-core install chromium-headless-shell`
  from this directory) is picked up automatically, pass `--chrome /path/to/chrome` otherwise,
- a full ffmpeg build (with libx264 and libvpx-vp9), either on the `PATH` or passed via
  `--ffmpeg /path/to/ffmpeg`, like the one of
  `uvx --from imageio-ffmpeg python -c "import imageio_ffmpeg; print(imageio_ffmpeg.get_ffmpeg_exe())"`.

`record.mjs` can also be run directly, with the command starting JupyterLab and the name of the Scala
kernel to use. With the kernels built from sources, using the JupyterLab command of `dev.jupyterCmd`
(the Spark and echo demos need kernels that `dev.jupyterCmd` doesn't set up):
```text
$ node docs/website/demos/record.mjs \
    --jupyter-cmd "$(./mill show dev.jupyterCmd 2.13.18 | jq -r .)" \
    --kernel scala-debug-2.13 \
    docs/website/demos/progressbar.sc
```

`record.mjs` records all the demos of this directory by default, or the ones passed as arguments.
It writes `<demo>.mp4`, `<demo>.webm`, and `<demo>.png` (the last frame, used as poster) to
`../static/frontpage`. Run `node record.mjs --help` for the full list of options. Among them:
- `--formats mp4,webm,gif,png`: the files to write,
- `--theme dark`: record with the JupyterLab dark theme, writing `<demo>-dark.*` files (the front
  page shows those when the website is in dark mode),
- `--width` / `--height` / `--scale`: default size of the browser viewport, in CSS pixels, and
  device scale factor (640 x 520 at 2x by default),
- `--max-idle 400`: longest time spent waiting for the kernel with nothing changing on screen
  that's kept in recordings, in milliseconds,
- `--keep-frames`: keep the captured frames, and the ffconcat file listing them.

## How it works

`record.mjs` starts JupyterLab with dedicated settings (light theme, larger fonts, no
auto-completion or auto-closing brackets, no extensions running code when kernels start), opens a
notebook, and hides everything but the notebook itself. It then types each cell key by key and runs
it with Shift+Enter, while capturing frames with Chrome's screencast API, along with the time each
of them was received at.

Times spent waiting for the kernel with nothing changing on screen (kernel start-up, compilation,
…) are cut down to `--max-idle` milliseconds, so that recordings don't depend much on how fast the
machine they're made on is. Frames are then cropped to the notebook and encoded with ffmpeg.
Recording fails if a cell fails, or if one of its lines is too long for the viewport, which would
make the editor scroll horizontally.

Kernels run with a coursier cache of their own (`COURSIER_CACHE`), that starts empty. Before
recording, `record.mjs` runs some code once with each kernel, so that the cache gets what kernels
need to start, then resets the cache to that state before each demo. That way, the dependencies
that demos load get downloaded while they're recorded, whatever is in the coursier cache of the
machine, and the kernels installed by `record.sh` show these downloads and their progress in the
notebook (`--use-notebook-coursier-logger`). Only demos with `warmup=true`, like the Spark one,
don't show their downloads. Note that Almond 0.15.0 and earlier versions only show the downloads of
`import $ivy`, not those of `//> using dep` directives (later versions show both).
