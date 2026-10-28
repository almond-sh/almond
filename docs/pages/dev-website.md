---
title: Generate the website
---

The website relies on [Docusaurus](https://docusaurus.io). Make sure you have installed
Node.js (18 or later), *npm* and yarn.

Then run
```text
$ ./mill -i docs.generate --npm-install --yarn-run-build
```

If the generation is successful, you can serve the website locally with

```bash
$ cd docs/website
$ yarn run serve
```

This command should print the address to access the local website, like `http://localhost:3000/`.

The search index is computed when building the website, so search only works on the built
website, and not when watching sources (see below).

The Markdown sources of the documentation are in `docs/pages`. They're processed by
[mdoc](https://scalameta.org/mdoc), that writes its output in `docs/processed-pages`, which
Docusaurus then reads.

## Versioned docs

The docs of former versions live in [almond-sh/versioned-docs](https://github.com/almond-sh/versioned-docs).
To have them in your local website, check them out and copy them to `docs/website` with

```text
$ git clone https://github.com/almond-sh/versioned-docs.git target/versioned-docs
$ WEBSITE_DIR=docs/website VERSIONED_DOCS_DIR=target/versioned-docs scripts/website0/versioned-docs.sh
```

before generating the website.

## Watch sources

To watch sources and rebuild/hot-reload on changes in `docs/pages` or `docs/website` run

```text
$ ./mill -i -w docs.generate --npm-install --yarn-run-build --watch
```

This should open a browser window, pointing at the locally running website.
