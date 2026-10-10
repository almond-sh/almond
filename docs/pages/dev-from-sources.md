---
title: Installing from sources
---

## Prerequisites

You don't need to install a JVM to build almond. Its Mill launcher downloads Mill as a
native executable, and Mill downloads the JVMs the build needs on its own: the one Mill
runs on, and the JDK 17 the oldest Scala 2 versions are built with (see
[below](#list-available-scala-versions)).

The only exceptions are Linux distributions whose GLIBC is older than 2.39, and Windows on
ARM: the native Mill executable can't run there, so the launcher falls back to a JVM-based
Mill, that needs a `java` command (Java 17 or later) on the `PATH`. You can install one with
your OS package manager (`apt`, `dnf`, …), or with the
[coursier](https://get-coursier.io/docs/cli-installation.html#native-launcher) command-line
and its [`cs java` command](https://get-coursier.io/docs/cli-java.html#setting-a-default-jvm-version).

Check-out the sources with git:
```text
$ git clone https://github.com/almond-sh/almond.git
$ cd almond
```

Almond is built with [mill](https://com-lihaoyi.github.io/mill). A mill
launcher ships with almond, so that you don't need to install mill yourself.
We list below useful commands, to help get you started using mill to build almond.

## Run a Jupyter notebook server without installing a kernel

```text
$ ./mill -i dev.jupyter
```

This should
- build almond, and publish its modules to a local Maven repository (`local-repo.localRepo`),
- install kernels for Scala 2.13 and Scala 3 from it, with the same command as the
  [installation instructions](quick-start-install.md), plus that repository, like
  `cs launch --use-bootstrap almond:<version> --scala 2.13.18 --channel app -r file:///…/local-repo -- --install …`,
  along with a "special" kernel, that runs the almond launcher, then
- start JupyterLab in the `notebooks` directory, in the background.

The [example notebooks](#validate-the-example-notebooks) are available from JupyterLab,
under `examples`: `notebooks/examples` is a symbolic link to the `examples` directory,
so that changes made to them from JupyterLab end up in the almond sources. Where
symbolic links can't be created (Windows without developer mode, say), the notebooks
get copied there instead. `notebooks/examples` is left as is if it already exists:
remove it to have it re-created.

The `almond` app descriptor these commands use is `app/almond.json`, rather than the one
of the [coursier apps channel](https://github.com/coursier/apps), that the installation
instructions use. The two are expected to be the same, which the release CI checks
(see `scripts/check-app-descriptor.sh`). When changing one, change the other too.

Like `runBackground` in Mill, this command returns once JupyterLab is started, and
prints the URLs it can be reached at. JupyterLab keeps running in the background, so
that you can keep using mill (to rebuild the kernels for example). Running the
command again restarts JupyterLab, and
```text
$ ./mill dev.jupyterStop
```
stops it. Its output goes to log files printed by the command. The command also
returns their paths, so that `./mill show` prints them as JSON, which allows to
start JupyterLab and follow its output in one go:
```text
$ ./mill show dev.jupyter | jq -r '.[]' | xargs tail -f
```

Neither JupyterLab nor Python need to be installed: the command downloads
[uv](https://docs.astral.sh/uv/), which then sets up a Python environment with the
Jupyter versions pinned in `examples/uv.lock` on the fly. To use a `uv` binary
you already have instead, set the `ALMOND_UV` environment variable to its path.

From the JupyterLab instance, select the kernel "Scala 2.13.18 (sources)" or
"Scala 3.9.0 (sources)" (the exact Scala versions can differ, see the
[Scala versions](#list-available-scala-versions) commands below).

JupyterLab starts with a few settings changed from its defaults: its theme follows
the system one (light or dark), editors indent with 2 spaces, and
[completions are shown while typing](usage-completion.md). These are listed in
`examples/jupyterlab-overrides.json`. Anything you change in the settings still takes
precedence.

JupyterLab comes with the
[variable inspector](https://github.com/jupyterlab-contrib/jupyterlab-variableInspector)
extension, and the kernels are started with `--variable-inspector`, which enables almond's
support for it. Open it with "Open Variable Inspector", from the context menu of a notebook
or from the command palette: it lists the variables defined in the notebook, along with
their types and values.

JupyterLab also comes with [Jupyter AI](https://jupyter-ai.readthedocs.io/), which lets
you chat with Claude and Codex. Open the "Jupyter Chat" panel from the left sidebar
(speech bubbles icon), click "New chat", then pick an agent from the menu at the
bottom left of the message box (it shows "No one" initially). Chats are saved as
`.chat` files, next to the notebooks.

Jupyter AI talks to Claude and Codex through the [ACP](https://agentclientprotocol.com/)
agents `claude-agent-acp` and `codex-acp`, that the command installs with npm, at the versions pinned in
`examples/acp-agents/package-lock.json` (nothing gets installed globally). This
requires Node.js 22 or later on the `PATH`: without npm, the command only prints a
warning, and these two personas aren't available. The agents use your existing
Claude Code and Codex credentials (or the `ANTHROPIC_API_KEY` / `OPENAI_API_KEY`
environment variables).

The same server also serves the Jupyter Notebook UI (the classic, single-document
one, [Notebook 7](https://jupyter-notebook.readthedocs.io/)) under `/tree`, next to
JupyterLab under `/lab`. Switch between them from the View menu ("Launch Jupyter
Notebook File Browser" or "Open in Jupyter Notebook" in JupyterLab, "Open in
JupyterLab" in the notebook UI), or change the URL by hand. To land on the classic
UI by default, pass `--classic`:
```text
$ ./mill -i dev.jupyter --classic
```

Optionally, pass a Scala version and / or JupyterLab options, like
```text
$ ./mill -i dev.jupyter 2.12.21
$ ./mill -i dev.jupyter --ip=192.168.0.1
$ ./mill -i dev.jupyter 2.12.21 --ip=192.168.0.1
```
(If specified, the Scala version needs to be passed first.) A Scala version replaces the
default one with the same binary version (`2.13.17` replaces the Scala 2.13 kernel, say), or
gets its own kernel next to the default ones (`2.12.21` adds a Scala 2.12 kernel).

If you reach JupyterLab through a reverse proxy that handles HTTPS (Tailscale
serve for example), pass the address you use in your browser with `--base-address`:
```text
$ ./mill -i dev.jupyter --base-address=https://pc-home.tail381281.ts.net:36227 --no-browser
```
JupyterLab then displays its URLs with that address, accepts requests and websocket
connections coming through it, and trusts the `X-Forwarded-*` headers set by the
proxy. Other options (like `--no-browser` above, or `--port=…` to pick the local
port the proxy forwards to) are passed to JupyterLab as is.

### Use the scalafmt extension from its sources

To work on [almond-scalafmt](https://github.com/almond-sh/almond-scalafmt), the
JupyterLab extension formatting cells with scalafmt, point the `ALMOND_SCALAFMT_EXTENSION`
environment variable at its sources (an absolute path, or a path relative to the almond
sources):
```text
$ git clone https://github.com/almond-sh/almond-scalafmt.git work/almond-scalafmt
$ ALMOND_SCALAFMT_EXTENSION=work/almond-scalafmt ./mill -i dev.jupyter
```
`dev.jupyter` and `dev.jupyterCmd` then build
the extension, and enable it in JupyterLab and in the Jupyter Notebook UI. The extension
needs to be a JupyterLab 4 prebuilt extension (with a `jupyterlab.outputDir` field in its
`package.json`), and building it requires Node.js on the `PATH`. Its sources are copied
under `out/dev/scalafmtExtension.dest` and built there, with the `jlpm` command of the
Python environment of JupyterLab (nothing gets written to the extension sources). If
the directory `ALMOND_SCALAFMT_EXTENSION` points at doesn't exist, the commands print a
warning and run without the extension.

After changing the extension sources, run the command again: it rebuilds the extension
(keeping its dependencies around, so that this only takes a few seconds), and
`dev.jupyter` restarts JupyterLab with it. Reload JupyterLab from your
browser to pick it up.

## Get the command to run JupyterLab yourself

```text
$ ./mill show dev.jupyterCmd
```

This installs the kernels like `dev.jupyter` does, but
instead of starting JupyterLab, it prints the shell command line to do so, as a JSON
string: a `cd` to the workspace, the environment variables to set, then the command
itself, quoted as needed for POSIX shells:
```text
"cd /path/to/almond && JAVA_HOME=… PATH=… JUPYTER_PATH=… …/uv run --project /path/to/almond/examples --frozen jupyter lab …"
```

Pass it to `eval` to run JupyterLab, with jq for example:
```text
$ ( eval "$(./mill show dev.jupyterCmd | jq -r .)" )
```
(The subshell keeps the `cd` from changing the current directory of your shell.)
Like `dev.jupyter`, it accepts a Scala version, `--classic`, `--base-address`, and
JupyterLab options.

`dev.jupyterFast` and `dev.jupyterCmdFast` are the same as `dev.jupyter` and `dev.jupyterCmd`.

## Build a kernel launcher

```text
$ ./mill dev.launcherFast
```

Once done building, this should print the path to the kernel launcher, like
`out/scala/scala-kernel/2.13.3/launchers/2.13.18/unixLauncherFast/dest/launcher` (2.13.3
being the Scala version the modules published for Scala 2.13 are built with, 2.13.18 the one
the kernel runs).

Optionally, pass a Scala version, like
```text
$ ./mill dev.launcherFast --scalaVersion 2.12.21
```

You can then run that launcher to install it on your system:
```text
$ out/scala/scala-kernel/2.13.3/launchers/2.13.18/unixLauncherFast/dest/launcher --install
```
Pass `--help` or see [this page](install-options.md) for the available options.

## Watch for source changes

You can re-build a launcher upon source changes with
```text
$ ./mill -w dev.launcherFast
```

If you [ran a JupyterLab server from the almond sources](#run-a-jupyter-notebook-server-without-installing-a-kernel),
its kernels load the almond JARs from the local repository. Re-publish them there upon source
changes with
```text
$ ./mill -w local-repo.localRepo
```
then restart the kernel from a notebook via JupyterLab to pick them up.

## Useful commands

### List available Scala versions
```text
$ ./mill -i dev.scalaVersions
2.13.18
2.13.17
…
```

The modules we publish are cross-published for binary Scala versions, and built with the
oldest full Scala version we support for each of them - those are the cross values to pass
to the modules themselves, while their tests are cross-built over the full Scala versions:
```text
$ ./mill -i dev.binaryScalaVersions
3.3.8
2.13.3
2.12.8
```

The oldest Scala 2 versions we support (2.12.x before 2.12.18, 2.13.x before 2.13.11) can't
run on the recent JDK the build runs on (they need JDK 17 at most): Mill downloads a JDK 17
to compile and test the modules built with them, and `dev.jupyter*` run their kernels with it
(all of them, when one of the kernels of a JupyterLab server runs such a version).
Scala 2.12.8 compiles with a small patch of its own class path handling, that lets it expand
macros on JDK 15+ (see `mill-build/scalac-patches`).

### Print the latest supported Scala 2.13 version
```text
$ ./mill --ticker false dev.scala213
2.13.18
```

### Print the latest supported Scala 2.12 version
```text
$ ./mill --ticker false dev.scala212
2.12.21
```

### Compile all modules for a Scala version
```text
$ ./mill '__[2.13.3].compile'
```

### Compile all modules for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.3].compile'
```

### Compile all tests for a Scala version
```text
$ ./mill '__[2.13.3].test.compile'
$ ./mill '__.test[2.13.18].compile'
```

### Compile all tests for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.3].test.compile'
```

### Run all tests for a Scala version and watch source changes
```text
$ ./mill -w '__[2.13.3].test'
$ ./mill -w '__.test[2.13.18]'
```

### Only run the tests affected by the changes of a branch

Once your changes are committed, record the state of the build at the commit your branch
started from with
```text
$ .github/scripts/selective-prepare.sh "$(git merge-base master HEAD)"
```
then run the tests affected by the changes since then with
```text
$ ./mill selective.run '__[2.13.14].test'
$ ./mill selective.run '__.test[2.13.18].testForked'
```
Use `selective.resolve` rather than `selective.run` to only print the tasks that would be run.
This is what CI does on pull requests.

### Compile specific modules
```text
$ ./mill 'scala.scala-kernel[2.13.3].compile'
```

### Generate Metals configuration files

It is recommended to generate Metals configuration files manually, rather
than letting Metals load the project itself. In order to do that, run
```text
$ ./mill mill.contrib.Bloop/install
```

If you're using Metals from VSCode, you can then run the
"Metals: Connect to build server" command to take into account the newly
generated files.

If the command above takes too long to run, comment out Scala versions in
`deps.sc`. If no 2.12 versions are left, also comment out the few 2.12-specific
projects in `build.sc` (look for `212` to find them). Same if no 2.13 versions
are left (look for `213` to spot 2.13-specific projects).

### Generate IntelliJ IDEA configuration files

It is recommended to [manually generate IntelliJ configuration files](https://com-lihaoyi.github.io/mill/mill/Installation_IDE_Support.html#_intellij_idea_support),
rather than letting IntelliJ load the project itself. In order to do that, run
```text
$ ./mill mill.scalalib.GenIdea/idea
```

You can then open the project in IntelliJ.
IntelliJ should also automatically pick those files when they are overwritten.

Just like for Metals above, you may benefit from disabling all but one Scala
version (see above for more details).

## Validate the example notebooks

Example notebooks live under `examples/`. These are run
on the CI using nbconvert, and the resulting outputs are
compared to the committed ones. Any difference results
in the examples job on the CI to fail.

To validate the examples locally, run
```text
$ ./mill -i scala.examples.test
```

Optionally, you can pass a glob to filter notebook names:
```text
$ ./mill -i scala.examples.test 'almond.examples.Examples.plotly*'
```

## Run the proxy and mirror tests

The [proxies and mirrors](install-proxies.md) instructions are tested with Docker: the kernels get
installed and run from containers that can't reach Maven Central, except through an authenticated
proxy or a repository mirror. With Docker running, run them with
```text
$ ./mill -i -j 1 scala.proxy-tests.test
```

These build a couple of images on first run (a JDK with `cs` and Jupyter, and the proxy), and pull
the `nginx` and `alpine` ones. Each test starts from an empty coursier cache, and downloads
everything the kernel needs through the proxy or mirror, so expect a few minutes per test.
