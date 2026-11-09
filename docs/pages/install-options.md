---
title: Options
---

These are the options of the kernel launcher installed by the default
[installation command](quick-start-install.md). Many of them are also accepted by the
[newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher),
either directly, or after an extra `--` (see [below](#newer-launcher-options)).

Pass these options along `--install`, so that they get written in the kernel spec, and
are passed to the kernel each time Jupyter starts it.

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
[path where this kernel's spec is going to be installed](https://jupyter-client.readthedocs.io/en/stable/kernels.html#kernel-specs). Use like
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

On Windows, like Jupyter itself since jupyter_core 5.8, system-wide kernels get installed under
the `share\jupyter\kernels` directory of the Python environment of Jupyter, like
`C:\Python313\share\jupyter\kernels`. Almond infers that environment from `CONDA_PREFIX` or from
the location of the `jupyter` command (see `--jupyter-command`). `%PROGRAMDATA%\jupyter\kernels`
is only used if the `JUPYTER_USE_PROGRAMDATA` environment variable is set, as non-admin users
can create directories under `%PROGRAMDATA%`.

#### `--jupyter-path`

Path to your Jupyter kernels directory, e.g. `/opt/conda/share/jupyter/kernels`

#### `--jupyter-command`

The `jupyter` command that Almond runs to find the directories Jupyter looks for kernels in,
either a name to look for in the `PATH` or a path (default: `jupyter`). Pass an empty value not
to run it. See [Where the kernel gets installed](install-advanced.md#where-the-kernel-gets-installed).

#### `--require-jupyter`

Make the installation fail if the `jupyter` command can't be run, rather than falling back to
the default Jupyter directories.

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

#### `--env`

Set an environment variable in the kernel spec, that Jupyter sets when starting the kernel.
Can be repeated. Use like
```bash
--env SPARK_HOME=/opt/spark --env COURSIER_REPOSITORIES="ivy2Local|https://artifacts.company.com/maven"
```

#### `--copy-launcher`

Whether to copy the kernel launcher in the kernel installation directory.

Default: true if `--arg` and `--command` aren't specified, false else.


## Scala-related

#### `--silent-imports`

Suppress the automatic output for `import` statements. Disabled by default.
Enable it when installing the kernel:

```bash
cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install --silent-imports
```

Add `--force` when replacing an existing kernel installation.

To limit automatic output for values as well, use `--output-style`.

#### `--output-style`

Change how the results of cells are displayed, for example to reduce the output
chatter described in [issue #256](https://github.com/almond-sh/almond/issues/256).
Accepts:
- `default`: display each value defined or computed in a cell, along with its name
  and type (`a: Int = 1`)
- `last`: only display the last value defined or computed in a cell, along with its
  name and type. Expressions (not assigned to a `val`) with a rich display, such as
  `Markdown("…")` or `Html("…")`, are still shown
- `python`: display results like the Python kernel does, that is only display the
  value of the last expression of a cell, without its name and type

Enable it when installing the kernel:

```bash
cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install --output-style python
```

The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
also accepts this option directly:

```bash
cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --install --output-style python
```

Add `--force` when replacing an existing kernel installation, then start a new
kernel session.

The output style can also be changed at any point in a session, for the cells that
follow, with a using directive:

```scala
//> using outputStyle python
```

With any output style, all statements still execute, and the variables they define
remain available in subsequent cells. Explicit output, such as `println` and
`display()` calls, is still shown.

##### `last`

For example, this cell displays only `second: Int = 2`:

```scala
val first = 1
val second = first + 1
```

Import statements and class, method, and type definitions are not echoed. Trailing
imports or definitions do not replace the last value. If the last value has type
`Unit`, no automatic result is displayed. Rich values such as `almond.display.Html`
are displayed automatically only when they are the last value in the cell.

##### `python`

For example, this cell displays nothing:

```scala
val records = (0 until 10).map(i => (i, i * i))
val evenRecords = records.filter(_._1 % 2 == 0)
```

and this one only displays `Vector((0, 0), (2, 4))`:

```scala
evenRecords.take(2)
```

Nothing is displayed for `val` definitions, imports, or class, method, and type
definitions, nor for expressions that are not the last statement of a cell. Like
in the Python kernel, the value of the last expression isn't displayed either if
the cell ends with a semicolon:

```scala
evenRecords.take(2);
```

If the last expression has type `Unit`, nothing is displayed. Rich values such as
`almond.display.Html` are displayed automatically when they are the last
expression of the cell.

#### `--last-value-only`

Kept for compatibility, same as `--output-style last`.

#### `--predef-code`

Run some code right before the session starts. Makes the kernel start fail if the predef
doesn't compile or throws an exception. Use like
```bash
--predef-code "
  import scala.jdk.CollectionConverters._
"
```

#### `--predef`

Run the code of a file right before the session starts, like `--predef-code` does. Can be
repeated. Use like
```bash
--predef /path/to/predef.sc
```

#### `--color`

Whether to color the results of cells, like `n: Int = 2` (default: true). Disable with
```bash
--color=false
```

#### `--compile-only`

Only compile the code of cells, without running it.

#### `--disable-cache`

Disable the compilation cache of Ammonite.

#### `--auto-update-vars`

Whether the output of top-level `var`s should be updated when they change later on (default: true).
See [Auto-updated variables](api-jupyter.md#auto-updated-variables). Disable with
```bash
--auto-update-vars=false
```

#### `--auto-update-lazy-vals`

Whether the output of top-level `lazy val`s should be updated when they're computed (default: true).
See [Auto-updated variables](api-jupyter.md#auto-updated-variables).

## Dependency-related

#### `--extra-repository`

Add some Maven repositories right when a kernel starts. (`~/.ivy2/local` and Maven Central are
added by default.) Use like
```bash
--extra-repository https://central.sonatype.com/repository/maven-snapshots
--extra-repository https://artifacts.company.com/maven
```

#### `--dependency`

Add dependencies to the class path of user code when a kernel starts, before predef code
and cells are run. Accepts the same dependency syntax as `//> using dep` directives,
can be repeated, and is also available as `--dep`. Use like
```bash
--dependency org.typelevel::cats-core:2.12.0
--dep com.lihaoyi::os-lib:0.11.4
```
Dependencies are resolved with the repositories passed via `--extra-repository`.
The kernel fails to start if they can't be resolved.

#### `--extra-class-path`

Add JARs or directories of class files to the class path of user code when a kernel starts.
Accepts the same format as `java -cp`, and is also available as `--extra-cp`. Use like
```bash
--extra-class-path /path/to/lib.jar:/path/to/other-lib.jar
```
[Inspections](usage-completion.md#inspections) show the documentation of the classes
of these JARs only if their source JARs are passed too, like
```bash
--extra-class-path /path/to/lib.jar:/path/to/lib-sources.jar
```

#### `--auto-dependency`

Automatically add a dependency when a given module gets loaded, like
```bash
--auto-dependency "org.typelevel::cats-core=>org.typelevel::cats-effect:3.5.4"
```
Can be repeated. By default, `sh.almond::almond-spark` gets added when Spark is loaded (see
[Spark](usage-spark.md)), and `sh.almond::almond-scalapy` when ScalaPy is loaded. Disable these
defaults with `--default-auto-dependencies=false`.

#### `--force-property`

Force a Maven property during dependency resolution, like `--force-property key=value`. Can be
repeated.

#### `--profile`

Enable a Maven profile during dependency resolution, like `--profile name`, or disable it, like
`--profile '!name'`. Can be repeated.


## Internals

#### `--log`

Sets the kernel log level. Can be any of
- `none`,
- `error`,
- `warn` (default),
- `info`,
- `debug`.

#### `--log-to`

Write the kernel logs to a file, rather than to the standard error, like
```bash
--log debug --log-to /tmp/almond.log
```

#### `--quiet`

Whether not to copy what user code prints to the standard output and error of the kernel
process (default: true). What user code prints is sent to the front-end either way.

#### `--trap-output`

Don't send what user code prints to the standard output and error to the front-end. It
goes to the standard output and error of the kernel process instead.

#### `--specific-loader`

Whether to load user code from the class loader of the user-facing modules of Almond, rather
than from the context class loader (default: true). See
[Isolation of Almond internal dependencies](install-advanced.md#isolation-of-almond-internal-dependencies).

#### `--use-thread-interrupt`

Whether to stop running cells with `Thread.interrupt`, rather than with the deprecated
`Thread.stop` (default) when interrupting the kernel. As `Thread.stop` isn't supported
any more from JDK 20 on, interrupt messages (see [`--interrupt-via-message`](#--interrupt-via-message))
always use `Thread.interrupt` on these JDKs. Note that `Thread.interrupt` only stops code
that checks for interruptions, like `Thread.sleep` or blocking I/O calls.

## Jupyter-related

#### `--interrupt-via-message`

Whether to ask front-ends to interrupt the kernel by sending it
[an interrupt message](https://jupyter-client.readthedocs.io/en/stable/messaging.html#kernel-interrupt),
rather than a SIGINT signal (default).

#### `--banner`

Add extra messages to the banner of this kernel (shown via Help > About the Scala Kernel in JupyterLab).
Use like
```bash
--banner "
Extra info
Some more
"
```

#### `--link`

Add entries to the Help menu of front-ends. Use like
```bash
--link "https://github.com|GitHub"
--link "https://google.com|Google"
```

#### `--connection-file`

Path to the connection file that Jupyter passes to the kernel when starting it. This is
written in the kernel spec upon installation, and doesn't need to be passed by hand.

#### `--username`

Set the user name in the headers of the messages sent by the kernel. Defaults to
the name of the user running the kernel. Pass it along `--install` to write it in the kernel spec, like
```bash
--install --username jupyter
```

#### `--max-message-size`

Maximum size of the ZeroMQ messages accepted by the kernel, `500m` by default. Peers sending
larger messages get disconnected. Accepts sizes in bytes, optionally with a `k`, `m`, or `g` suffix
(powers of 1024), or `none` to accept messages of any size. Pass it along `--install` to write it
in the kernel spec, like
```bash
--install --max-message-size 2g
```

#### `--variable-inspector`

Whether to enable support for the
[variable inspector](https://github.com/jupyterlab-contrib/jupyterlab-variableInspector)
JupyterLab extension. By default, it gets enabled when the extension asks for it. Disable it with
`--variable-inspector=false`.

#### `--scalafmt`

Whether to format cells with [scalafmt](https://scalameta.org/scalafmt) when front-ends ask for it,
like the [almond-scalafmt](https://github.com/almond-sh/almond-scalafmt) JupyterLab extension does
(default: true).

#### `--use-notebook-coursier-logger`

Show the progress of dependency downloads in the notebook, rather than in the kernel logs.

#### `--metabrowse`

Kept for compatibility, currently has no effect.

## Other

#### `--toree-magics`

Enable experimental support for [Toree](https://toree.apache.org) magics.

Simple line magics such as `%AddDeps` (always assumed to be transitive as of writing this, `--transitive` is just ignored),
`%AddJar`, and cell magics such as `%%html` or `%%javascript` are supported.
See [Enabling Toree compatibility](install-advanced.md#enabling-toree-compatibility) for more details.

#### `--toree-api`

Enable basic support for the Toree API. See [Toree API](install-advanced.md#toree-api).

#### `--toree-compatibility`

Same as `--toree-magics` and `--toree-api` together.

## Newer launcher options

The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
accepts most of the options above directly, along with a few options of its own:

- `--scala`: the default Scala version of the notebooks, that users can change with a
  `//> using scala` directive,
- `--java-opt`: a Java option to pass to the kernel, like `--java-opt -Xmx4g`
  (see [Memory](install-advanced.md#memory)), can be repeated,
- `--extra-startup-class-path`: JARs to add to the class path of `java` itself
  (see [Custom URL protocol support](install-advanced.md#custom-url-protocol-support)),
- `--shared-dependencies`: dependencies to add to the user-facing part of the Almond class path.

The other options of the kernel can be passed after an extra `--`, like
```text
$ cs launch --use-bootstrap sh.almond::launcher:@VERSION@ -- --scala @SCALA213_VERSION@ --install -- --toree-api
```

Run the launchers with `--help` to list all the options they accept.
