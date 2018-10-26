---
title: Generate the website
---

Install pre-requisites with
```bash
$ sbt \
    interpreter-api/exportVersions \
    interpreter-api/publishLocal \
    scala-kernel-api/publishLocal
```

Then run
```bash
$ scripts/generate-website.sh
```

If the generation is successful, this should print instructions to run
a small webserver serving the website, like
```bash
$ npx http-server docs/website/build/almond
```

This command should itself print the address to access the local website,
like `http://127.0.0.1:8080`.

## Watch sources

Pass `--watch` to `generate-website.sh` above,
```bash
$ scripts/generate-website.sh --watch
```

In another terminal, go under the `docs/website` directory, and run
```bash
$ yarn start
```

This should open a browser window, pointing at the locally running website.
