The examples in this directory are there mostly for test purposes.

See the [examples repository](https://github.com/almond-sh/examples) for
more detailed and user-friendly examples.

`./mill 'scala.examples[_].test'` runs them with each of the Scala versions of
`ScalaVersions.examples` (in `mill-build`), and checks that they give the outputs recorded in
the notebooks. These are the outputs of the first of those versions, the reference one (a
Scala 2.12 version). Notebooks that run differently with Scala 2.13 or Scala 3 (different
outputs, or sources that need to be adapted, like the dependencies of `plotly-scala.ipynb` on
Scala 3) have a copy in `scala-2.13` or `scala-3`, that's run and compared with on those versions
instead. Only the Scala version recorded in the notebook metadata may differ from what the tests
get.

To update the notebooks from what the tests get, run them with `ALMOND_UPDATE_EXAMPLES=1`
(`ALMOND_UPDATE_EXAMPLES=1 ./mill 'scala.examples[3.9.0].test'` for example). On Scala 2.13 or
Scala 3, this writes a copy in `scala-2.13` or `scala-3` when the outputs differ from the
reference notebook, and removes it when they don't anymore. To adapt the sources of a
notebook for one of these, edit its copy there (copy the reference notebook first if needed),
then update it like this. The tests fail if a copy isn't needed anymore, or has no reference
notebook.

The Jupyter setup used to run them (by `./mill 'scala.examples[_].test'`, and by the
`./mill dev.jupyter*` commands) is described by `pyproject.toml`, with exact versions
pinned in `uv.lock`. It is managed with [uv](https://docs.astral.sh/uv/): the build
downloads uv itself (see `UvLauncher` in `mill-build`) and runs
`uv run --project examples --frozen jupyter …`, so that nothing needs to be installed
beforehand. Set the `ALMOND_UV` environment variable to the path of a `uv` binary to use
that one instead of the downloaded one.

The dev.jupyter* commands starting JupyterLab also install the `lab` dependency group,
with JupyterLab extensions (the
[variable inspector](https://github.com/jupyterlab-contrib/jupyterlab-variableInspector), and the
front-ends of [Jupyter widgets](https://ipywidgets.readthedocs.io) and of
[anywidget](https://anywidget.dev)),
and the `ai` one, with [Jupyter AI](https://jupyter-ai.readthedocs.io/). The ACP agents its Claude and
Codex personas talk to are npm packages, described by `acp-agents/package.json`, with
exact versions pinned in `acp-agents/package-lock.json`. The build installs them with
`npm ci` (see `AcpAgents` in `mill-build`), if npm is available. To update them, run
```text
$ npm install --package-lock-only --save-exact @agentclientprotocol/claude-agent-acp@latest @agentclientprotocol/codex-acp@latest
```
from `acp-agents`.

`jupyterlab-overrides.json` changes the defaults of some JupyterLab settings (theme,
indentation, completions shown while typing, …). The dev.jupyter* commands copy it to the
`overrides.d` directory of the JupyterLab application settings, in the uv-managed
environment, and the `Dockerfile` at the root of the repository copies it to the same
place in the Docker images.

To update the pinned Python versions, run
```text
$ uv lock --upgrade
```
from this directory (or `uv lock --upgrade-package nbconvert` to bump a single package).
