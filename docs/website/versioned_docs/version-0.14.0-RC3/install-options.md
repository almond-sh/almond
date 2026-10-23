---
title: Options
---

Passing flags, like `--flag`, sets them to true. Use `--flag=false` (note the `=` sign)
to set them to false.

## Help

#### `--help`

Prints the available options and exits.


## Installation

#### `--install`

Has to be passed to install the kernel.

#### `--force`

Erase a previously installed kernel with the same ID.

#### `--id`

Set the kernel id, like `--id custom-id`. This id is used to construct the
[path where this kernel's spec is going to be installed](https://jupyter-client.readthedocs.io/en/5.2.3/kernels.html#kernel-specs). Use like
```bash
--id scala212
```

Default: `scala`

#### `--display-name`

Set the displayed name for this kernel. This is the name that appears in the Jupyter
menus, etc. Use like
```bash
--display-name "Scala (2.12)"
```

Default: `Scala`

#### `--global`

Whether to install this kernel system-wide or not.

System-wide kernels get installed under `/usr/local/share/jupyter/kernels` (Linux and OS X). User-wide ones
(default) under `~/.local/share/jupyter/kernels` (Linux) or `~/Library/Jupyter/kernels` (Mac).

#### `--jupyter-path`
Path to your Jupyter kernels directory, e.g. `/opt/conda/share/jupyter/kernels`

#### `--logo`

Path to a 64 x 64 PNG file. Set to an empty string to remove the default logo, like `--logo ""`. (Defaults to a scala logo embedded with the kernel.)

#### `--arg` / `--command`

Customize the command to spawn the kernel.

By default, it is assumed coursier or a coursier launcher
is involved during the installation. This allows to get the command that spawns the kernel for install. (Actual executable is located
via the `coursier.mainJar` Java property, and its arguments via `coursier.main.arg-0`,  `coursier.main.arg-1`, ... Java properties.) This command is then used to later start actual kernels.

To override that behavior, pass `--arg`s or `--command` options. `--arg` allows to pass each argument individually, like
```bash
--arg "/path to/executable" --arg first-arg --arg second-arg
```

`--command` allows to pass a command as a whole, like
```bash
--command "/path/to/executable first-arg second-arg"
```
which is latter split at space chars.

#### `--copy-launcher`

Whether to copy the kernel launcher in the kernel installation directory.

Default: true if `--arg` and `--command` aren't specified, false else.


## Scala-related

#### `--predef-code`

Run some code right before the session starts. Makes the kernel start fail if the predef
doesn't compile or throws an exception. Use like
```bash
--predef-code "
  import scala.collection.JavaConverters._
"
```

## Dependency-related

#### `--extra-repository`

Add some Maven repositories right when a kernel starts. (`~/.ivy2/local` and Maven Central are
added by default.) Use like
```bash
--extra-repository https://oss.sonatype.org/content/repositories/snapshots
--extra-repository sonatype:staging
```

#### `--auto-dependency`

#### `--force-property`

#### `--profile`


## Internals

#### `--log`

Sets the kernel log level. Can be any of
- `none`,
- `error`,
- `warn` (default),
- `info`,
- `debug`.

#### `--special-loader`

#### `--use-thread-interrupt`

Whether to use 'Thread.interrupt' method or deprecated 'Thread.stop' method (default) when interrupting kernel.

## Jupyter-related

#### `--interrupt-via-message`

Whether to ask front-ends to send interrupt messages via SIGINT (default)
or [a zeromq message](https://jupyter-client.readthedocs.io/en/5.2.3/messaging.html#kernel-interrupt).

#### `--banner`

Add extra messages to the banner of this kernel (shown via Help > About in Jupyter classic).
Use like
```bash
--banner "
Extra info
Some more
"
```

#### `--link`

Add entries to the Help menu (Jupyter classic). Use like
```bash
--link "https://github.com|GitHub"
--link "https://google.com|Google"
```

#### `--connection-file`

## Other

#### `--toree-magics`

Enable experimental support for [Toree](https://toree.apache.org) magics.

Simple line magics such as `%AddDeps` (always assumed to be transitive as of writing this, `--transitive` is just ignored),
`%AddJar`, and cell magics such as `%%html` or `%%javascript` are supported. Note that `%%javascript` only works from Jupyter
classic, as JupyterLab doesn't allow for random javascript code execution.
