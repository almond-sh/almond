---
title: Generate the website
---

Make sure you have installed *npm* and yarn.

Then run
```text
$ ./mill -i docs.generate --npm-install --yarn-run-build
```

If the generation is successful, you can run a small webserver to serve the website locally, like

```bash
$ npx http-server docs/website/build
```

This command should print the address to access the local website, like `http://127.0.0.1:8080`.

## Watch sources

To watch sources and rebuild/hot-reload on changes in `docs/pages` or `docs/website` run

```text
$ ./mill -i -w docs.generate --npm-install --yarn-run-build --watch
```

This should open a browser window, pointing at the locally running website.
