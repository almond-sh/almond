# Front page demos

The animations of the front page of the website are recorded from a real JupyterLab, by
[`record.mjs`](record.mjs). It types and runs the cells of the demo scripts of this directory, and
records the result with [Playwright](https://playwright.dev) and ffmpeg.

## Demo scripts

Each `*.sc` file is a demo, whose name is the file name without its extension. `// %%` lines start
cells, and can be followed by options:
- `pause=3000`: how long to wait after the cell ran, in milliseconds (1200 by default),
- `wait=.js-plotly-plot`: a CSS selector to wait for in the cell, for outputs rendered
  asynchronously, like plots loading their JavaScript library.

Before the first cell, a `// %demo` line can set options for the whole demo:
- `kernel=echo`: the kernel to run the demo with (the Scala kernel passed via `--kernel` by default),
- `width=640` / `height=600`: size of the browser viewport, in CSS pixels (640 x 520 by default),
- `warmup=true`: run the demo once before recording it, so that things like dependency downloads
  don't show up in the recording.

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
directory (`ALMOND_VERSION`, 0.15.0 by default), then records all the demos with them:
```text
$ docs/website/demos/record.sh
```
Pass it the paths of some demos to only record those, and [`record.mjs`](record.mjs) options
(see below), like
```text
$ docs/website/demos/record.sh --ffmpeg /path/to/ffmpeg docs/website/demos/spark.sc
```

It requires
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
- `--theme dark`: record with the JupyterLab dark theme, writing `<demo>-dark.*` files,
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
dependency downloads, …) are cut down to `--max-idle` milliseconds, so that recordings don't
depend much on how fast the machine they're made on is. Frames are then cropped to the notebook
and encoded with ffmpeg. Recording fails if a cell fails, or if one of its lines is too long for the
viewport, which would make the editor scroll horizontally.
