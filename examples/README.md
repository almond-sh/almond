The examples in this directory are there mostly for test purposes.

See the [examples repository](https://github.com/almond-sh/examples) for
more detailed and user-friendly examples.

The Jupyter setup used to run them (by `./mill scala.examples.test`, and by the
`./mill dev.jupyter*` commands) is described by `pyproject.toml`, with exact versions
pinned in `uv.lock`. It is managed with [uv](https://docs.astral.sh/uv/): the build
downloads uv itself (see `UvLauncher` in `mill-build`) and runs
`uv run --project examples --frozen jupyter …`, so that nothing needs to be installed
beforehand. Set the `ALMOND_UV` environment variable to the path of a `uv` binary to use
that one instead of the downloaded one.

The dev.jupyter* commands starting JupyterLab also install the `ai` dependency group,
with [Jupyter AI](https://jupyter-ai.readthedocs.io/). The ACP agents its Claude and
Codex personas talk to are npm packages, described by `acp-agents/package.json`, with
exact versions pinned in `acp-agents/package-lock.json`. The build installs them with
`npm ci` (see `AcpAgents` in `mill-build`), if npm is available. To update them, run
```text
$ npm install --package-lock-only --save-exact @agentclientprotocol/claude-agent-acp@latest @agentclientprotocol/codex-acp@latest
```
from `acp-agents`.

`jupyterlab-overrides.json` changes the defaults of some JupyterLab settings (theme,
indentation, …). The dev.jupyter* commands copy it to the `overrides.d` directory of the
JupyterLab application settings, in the uv-managed environment.

To update the pinned Python versions, run
```text
$ uv lock --upgrade
```
from this directory (or `uv lock --upgrade-package nbconvert` to bump a single package).
