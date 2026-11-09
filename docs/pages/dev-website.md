---
title: Generate the website
---

The website relies on [Docusaurus](https://docusaurus.io). Make sure you have installed
Node.js (18 or later) and *npm*.

The Markdown sources of the documentation are in `docs/pages`. They're processed by
[mdoc](https://scalameta.org/mdoc), that writes its output in `docs/processed-pages`, which
Docusaurus then reads. The commands below run mdoc, and install the website dependencies
with `npm ci` if needed, before running Docusaurus.

## Watch sources

To run the Docusaurus development server, that hot-reloads pages upon changes in `docs/pages`
or `docs/website`, run
```text
$ ./mill -w docs.devServer
```

Like `dev.jupyter` (see [Installing from sources](dev-from-sources.md)), this starts the server in
the background, and keeps it running when the command re-runs upon changes. The website is then
available at `http://localhost:3000/`. Stop the server with
```text
$ ./mill docs.devServerStop
```

The search index is computed when building the website, so search doesn't work with the
development server. Build the website, as described below, to check it.

## Build and serve the website

To build the website, and get the command serving it, run
```text
$ ./mill show docs.serveCmd
```

Like `dev.jupyterCmd`, this prints a shell command line as a JSON string, that can be
passed to `eval`:
```text
$ ( eval "$(./mill show docs.serveCmd | jq -r .)" )
```

## Options

`docs.devServer` and `docs.serveCmd` accept
- `--host=…`: the address to bind to (`localhost` by default),
- `--port=…`: the port to listen on (3000 by default),
- `--base-address=…`: the address the website is accessed from, like `https://host/some/path/`,
  when it is served behind a reverse proxy.

Like
```text
$ ./mill -w docs.devServer --host=0.0.0.0 --port=3001
```

## Versioned docs

The docs of former versions live in [almond-sh/versioned-docs](https://github.com/almond-sh/versioned-docs).
To have them in your local website, check them out and copy them to `docs/website` with

```text
$ git clone https://github.com/almond-sh/versioned-docs.git target/versioned-docs
$ WEBSITE_DIR=docs/website VERSIONED_DOCS_DIR=target/versioned-docs scripts/website0/versioned-docs.sh
```

before generating the website.

## Lower-level commands

`docs.generate` only runs mdoc by default. Pass it `--npm-install` to install the website
dependencies, and `--yarn-run-build` to build the website with *yarn* afterwards:
```text
$ ./mill -i docs.generate --npm-install --yarn-run-build
```
The built website can then be served with
```text
$ cd docs/website
$ npm run serve
```
