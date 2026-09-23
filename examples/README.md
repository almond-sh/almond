The examples in this directory are there mostly for test purposes.

See the [examples repository](https://github.com/almond-sh/examples) for
more detailed and user-friendly examples.

The Jupyter setup used to run them (by `./mill scala.examples.test`, and by the
`./mill dev.jupyter*` commands) is described by `pyproject.toml`, with exact versions
pinned in `uv.lock`. It is managed with [uv](https://docs.astral.sh/uv/): the build
runs `uv run --project examples --frozen jupyter …`, so that only uv needs to be installed.

To update the pinned versions, run
```text
$ uv lock --upgrade
```
from this directory (or `uv lock --upgrade-package nbconvert` to bump a single package).
