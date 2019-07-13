---
title: Generate the website
---

Make sure you have installed *Ammonite*, *npm* and yarn.

Then run
```bash
amm scripts/site.sc --npmInstall true --yarnRunBuild true --publishLocal true
```

If you're getting an error message like `Cannot resolve $file import: almond/scripts/website/Website.sc`
make sure you have checked out the git submodule for the website script:

```bash
git submodule init
git submodule update
```

If the generation is successful, you can run a small webserver to serve the website locally, like

```bash
$ npx http-server docs/website/build
```

This command should print the address to access the local website, like `http://127.0.0.1:8080`.

## Watch sources

To watch sources and rebuild/hot-reload on changes in `docs/pages` or `docs/website` run

```bash
$ amm scripts/site.sc --yarnRunBuild true --watch true
```

This should open a browser window, pointing at the locally running website.
