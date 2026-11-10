---
title: Multiple kernels
---

Several versions of Almond can be installed side-by-side. This is useful e.g. to have kernels
for several Scala versions, or to test newer / former Almond versions.

This page first details the `cs` commands used to install Almond, then shows how to install
it for a single Scala version, and for several Scala versions.

## Anatomy of the installation command

Almond is installed with commands like
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install --id scala213 --display-name "Scala 2.13"
```

Each part of it plays a different role:

- `cs launch`: the [coursier](https://get-coursier.io/docs/cli-launch) command that fetches an application
  and runs it. Here, the application is Almond itself, and running it with `--install` installs it as
  a Jupyter kernel.
- `--use-bootstrap`: makes `cs` run Almond via a launcher it generates on-the-fly. When installing itself,
  Almond copies that launcher next to the `kernel.json` file of the kernel, so that Jupyter can later
  start it (see [About the installation process](install-advanced.md#about-the-installation-process)).
  That launcher also isolates the internal dependencies of Almond from users
  (see [here](install-advanced.md#isolation-of-almond-internal-dependencies)).
- `almond:@VERSION@`: the application to run. `almond` is the name of an app descriptor from the
  [coursier apps channel](https://github.com/coursier/apps), that lists the dependencies of Almond
  (`sh.almond::scala-kernel`), the ones that are user-facing (`sh.almond::scala-kernel-api`), and the
  repositories they're fetched from. `:@VERSION@` selects an Almond version. Without it, as in just
  `almond`, the latest Almond release is used.
- `--scala @SCALA213_VERSION@`: the Scala version Almond is installed for. coursier uses it to pick the
  Almond modules built for that Scala version (`scala-kernel_@SCALA213_BINARY_VERSION@`, `scala-kernel_3`, …),
  and the Scala library version. Short versions, like `2.13` or `3`, are accepted too. Not all Almond and
  Scala version combinations exist, see [Versions](install-versions.md).
- `--`: separates the arguments of `cs` (before it) from the arguments passed to Almond (after it).
  Almond options passed before `--` would be interpreted by `cs`, and would fail or do something else.
- `--install`: asks Almond to install itself as a Jupyter kernel, rather than to run as a kernel.
- `--id scala213`: the kernel id. It's the name of the directory the kernel spec is written to
  (like `~/.local/share/jupyter/kernels/scala213` on Linux), and the name Jupyter uses to refer to the kernel
  (in `jupyter kernelspec` commands, or in the metadata of notebooks). Defaults to `scala`. Stick to
  lower case letters, digits, `-`, `_`, and `.` in it.
- `--display-name "Scala 2.13"`: the name of the kernel in the front-ends (Jupyter launcher, kernel menus, …).
  Defaults to `Scala`.

More options can be passed after `--`, like `--force` to overwrite an already installed
kernel with the same id. Run Almond with `--help` rather than `--install` to list them,
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --help
```
and see [Options](install-options.md).

## Installing Almond for a single Scala version

If you only need one Scala version, the default kernel id and display name are fine. Just pass the
Scala version you'd like via `--scala`, like
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install
```

This installs a kernel with id `scala`, that appears as "Scala" in front-ends.

To later switch to another Scala or Almond version, re-run the same command with the new version(s),
and pass `--force`, so that the new kernel replaces the former one,
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala 3 -- --install --force
```

Without `--force`, the installation fails if a kernel with the same id is already installed.
Notebooks referencing the `scala` kernel then run with the newly installed version.

## Installing Almond for several Scala versions

### One kernel per Scala version

To have several Scala versions available, install a kernel per Scala version. The kernels
**must** have different ids, so that they're installed in different directories, and **should**
have different display names, so that users can tell them apart in front-ends.

For example, let's install kernels for Scala 3, Scala 2.13, and Scala 2.12:
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala 3 \
    -- --install --id scala3 --display-name "Scala 3"
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ \
    -- --install --id scala213 --display-name "Scala 2.13"
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA212_VERSION@ \
    -- --install --id scala212 --display-name "Scala 2.12"
```

Only the `--scala` value on the `cs` side, and the `--id` and `--display-name` values on the Almond side,
change from one command to the other.

You can also keep a kernel with the default id, `scala`, and add other ones next to it,
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA212_VERSION@ \
    -- --install --id scala212 --display-name "Scala (2.12)"
```

Here, the first kernel gets the default id `scala` and display name "Scala", and
the second one is installed along side it, in a `scala212` directory, and appears in front-ends
under the name "Scala (2.12)".

The same applies to different Almond versions, to try a newer Almond version without replacing the
current one for example,
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ \
    -- --install --id scala213-new --display-name "Scala 2.13 (Almond @VERSION@)"
```

### Managing the installed kernels

List the installed kernels with
```text
$ jupyter kernelspec list
Available kernels:
  python3     /home/me/.venv/share/jupyter/kernels/python3
  scala212    /home/me/.local/share/jupyter/kernels/scala212
  scala213    /home/me/.local/share/jupyter/kernels/scala213
  scala3      /home/me/.local/share/jupyter/kernels/scala3
```

To update one of them, re-run the command that installed it, with the new versions, and
`--force` added after `--`, like
```text
$ cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA212_VERSION@ \
    -- --install --force --id scala212 --display-name "Scala 2.12"
```

To remove one of them, pass its id to `jupyter kernelspec remove`, like
```text
$ jupyter kernelspec remove scala212
```

### One kernel, Scala version chosen in notebooks

Alternatively, the [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
allows to install a single kernel, and to pick the Scala version from notebooks. Install it with
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --install --scala @SCALA213_VERSION@
```

Note that the Scala version is passed to Almond, after `--`, rather than to `cs`: the launcher
runs with its own Scala version, and starts an Almond kernel for the Scala version of the notebook
later on. The version passed via `--scala` is the default one, used when notebooks don't specify one.
Notebooks can then use another Scala version with a directive in their first cells, like
```scala
//> using scala "@SCALA212_VERSION@"
```

The launcher accepts the same `--id` and `--display-name` options as above, so that it can
be installed along side kernels for specific Scala versions, like
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ \
    -- --install --id scala-launcher --display-name "Scala (any version)"
```

(Without `--scala`, the default Scala version of the launcher is the Scala 3 version Almond
was released with.)
