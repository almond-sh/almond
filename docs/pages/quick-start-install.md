---
title: Installation
---

## Install the Almond kernel

Almond is a kernel for [Jupyter](https://jupyter.org), that needs to be installed
beforehand, like with `pip install jupyterlab` (see the
[JupyterLab installation instructions](https://jupyterlab.readthedocs.io/en/stable/getting_started/installation.html)
for other ways to install it).

Almond can be fetched and installed with the
[coursier](https://get-coursier.io/docs/cli-installation) command-line, `cs`, like
```text
$ curl -fL "https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz" | gzip -d > cs
$ chmod +x cs
$ ./cs launch --use-bootstrap almond -- --install
```

On other platforms, replace `cs-x86_64-pc-linux.gz` with
- `cs-aarch64-pc-linux.gz` on Linux ARM64,
- `cs-aarch64-apple-darwin.gz` on Apple Silicon Macs,
- `cs-x86_64-apple-darwin.gz` on Intel Macs.

If `cs` is already installed on your system, just run
`cs launch --use-bootstrap almond -- --install`.
The rest of this documentation assumes `cs` is on your `PATH`. To install it there, run
```text
$ ./cs install --setup && ./cs install cs
```
(`.\cs install --setup` then `.\cs install cs` on Windows), then open a new terminal.

To install Almond versions older than 0.15.0, use a coursier version newer than 2.1.26
(`cs version` prints it): coursier 2.1.26 and earlier don't set up the class loaders of
these Almond versions correctly.

Note the `--` before `--install`, separating the arguments passed to Almond
from the ones handled by coursier.

Almond runs on a Java Virtual Machine (JVM), that you don't need to install yourself:
`cs` uses the JVM of your system if it finds one, and downloads one otherwise.
The kernel then runs on the JVM it was installed with, whether it's on your `PATH` or not,
and gets it as `JAVA_HOME`. To install it with another JVM, pass `--jvm` to `cs`, like
```text
$ ./cs launch --use-bootstrap --jvm 21 almond -- --install
```
(Almond runs on Java 8 or later, but Scala 3.8 and later require Java 17.)

JVMs that `cs` downloads land in the coursier cache, and are unpacked in the coursier
archive cache, under
- `~/.cache/coursier/v1` and `~/.cache/coursier/arc` on Linux,
- `~/Library/Caches/Coursier/v1` and `~/Library/Caches/Coursier/arc` on macOS,
- `%LOCALAPPDATA%\Coursier\Cache\v1` and `%LOCALAPPDATA%\Coursier\Cache\arc` on Windows.

The `COURSIER_CACHE` and `COURSIER_ARCHIVE_CACHE` environment variables change these locations.
As the kernel runs the JVM from the archive cache, don't remove that JVM from it, or
re-install the kernel afterwards.

You can specify explicit Almond and / or Scala versions, like
```text
$ ./cs launch --use-bootstrap almond:@VERSION@ --scala @SCALA213_VERSION@ -- --install
```

Short Scala versions, like just `2.13` or `3`, are accepted too.
The available versions of Almond can be found [here](https://github.com/almond-sh/almond/releases).
Not all Almond and Scala versions combinations are available.
See the possible combinations [here](install-versions.md).


<details>
<summary>Equivalent Windows commands</summary>

These work both from the Command Prompt and from PowerShell (Windows 10 or later).

```bat
> curl.exe -fLo cs-x86_64-pc-win32.zip https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-win32.zip
> tar -xf cs-x86_64-pc-win32.zip
> move cs-x86_64-pc-win32.exe cs.exe
> .\cs launch --use-bootstrap almond -- --install
```

</details>

Once the kernel is installed, you can use it from JupyterLab, Jupyter Notebook, or any other
Jupyter front-end. Check that Jupyter finds it with
```text
$ jupyter kernelspec list
Available kernels:
  python3    /usr/local/share/jupyter/kernels/python3
  scala      /home/user/.local/share/jupyter/kernels/scala
```

## Getting help about the launcher

Pass `--help` instead of `--install`, like
```text
$ ./cs launch --use-bootstrap almond -- --help
```

## Update the Almond kernel

To update the Almond kernel, just re-install it, but passing the `--force` option to Almond (like `./cs launch --use-bootstrap almond -- --install --force`). That will override any previous Almond (or kernel with name `scala`).

## Uninstall the Almond kernel

To uninstall the Almond kernel, use `jupyter kernelspec remove scala`.

## What happens during installation

### Downloads

`cs launch --use-bootstrap almond` resolves the Almond kernel and its dependencies from
Maven Central (or from the repositories in
[`COURSIER_REPOSITORIES`](install-advanced.md#using-custom-maven-repositories)), and downloads
their JARs, along with their source JARs, to the
[coursier cache](https://get-coursier.io/docs/cache.html#location) of the user running the
command:

| OS | Default coursier cache |
|----|------------------------|
| Linux | `~/.cache/coursier/v1` |
| macOS | `~/Library/Caches/Coursier/v1` |
| Windows | `%LOCALAPPDATA%\Coursier\Cache\v1` |

Set the `COURSIER_CACHE` environment variable to use another directory. Files already in the
cache are used as is, rather than downloaded again. The cache is shared with other tools that rely
on coursier, like sbt, Mill, or Scala CLI. If Maven Central can only be reached through a proxy or a
mirror, see [Proxies and mirrors](install-proxies.md).

### Launchers

`cs` then writes a launcher, `coursier-launch-….jar`, to the temporary directory of the JVM
(`/tmp` on Linux, `$TMPDIR` on macOS, `%TEMP%` on Windows). That launcher is a small JAR that
doesn't contain Almond itself: it lists the URLs of the Almond JARs (see
[below](#when-the-kernel-starts)). `cs` runs it with `java -jar`, passing it the
arguments after `--`, and deletes it when the installation is done.

With `--install`, Almond creates the kernel directory, like
`~/.local/share/jupyter/kernels/scala` on Linux (see
[Where the kernel gets installed](install-advanced.md#where-the-kernel-gets-installed)), and
writes three files in it:
- `launcher.jar`, a copy of the launcher it was started from (not done if the launch command is
  passed explicitly via `--arg` or `--command`, see [`--copy-launcher`](install-options.md#--copy-launcher)),
- `kernel.json`, the kernel spec, that tells Jupyter how to start the kernel,
- `logo-64x64.png`, the logo shown by front-ends.

`kernel.json` contains the command that Jupyter runs to start the kernel, like
```json
{
  "argv": [
    "java",
    "-cp",
    "/home/user/.local/share/jupyter/kernels/scala/launcher.jar",
    "coursier.bootstrap.launcher.Launcher",
    "--connection-file",
    "{connection_file}"
  ],
  "display_name": "Scala",
  "language": "scala"
}
```
The [options](install-options.md) passed along `--install` are added to that command, except the
ones that only matter for the installation itself (`--id`, `--display-name`, `--jupyter-path`,
`--env`, …). Environment variables passed via `--env` are written in the `env` field of
`kernel.json`. When installing in the user kernel directory, `kernel.json` is made readable by
its owner only, as these can contain secrets.

`java` gets looked up in the `PATH` of Jupyter when it starts the kernel. To use another JVM,
pass the whole command via [`--arg` or `--command`](install-options.md#--arg----command).

When generating a launcher on your own with `cs bootstrap … -o almond`, it's written at the
path passed to `-o`, and its JARs are downloaded to the coursier cache too. As `launcher.jar`
is a copy of it, that launcher can be removed after the installation.

### When the kernel starts

When a notebook is opened, Jupyter writes a connection file, listing the ports the kernel should
listen on, in its runtime directory (`jupyter --runtime-dir` prints it), and runs the command of
`kernel.json` with `{connection_file}` replaced by its path. Then:
- the launcher reads the URLs of the Almond JARs it lists, and looks for them in the coursier
  cache, downloading those that are missing. Nothing is downloaded on the first startup for the user
  that installed Almond, but users that didn't install it have their own coursier cache,
  and get the JARs downloaded to it upon their first startup (see
  [All-included launcher](install-other.md#all-included-launcher) to avoid that). The same
  happens if the JARs got removed from the cache since the installation,
- the launcher loads these JARs in two class loaders, so that the internal dependencies of Almond
  aren't visible from notebooks (see
  [Isolation of Almond internal dependencies](install-advanced.md#isolation-of-almond-internal-dependencies)),
  and runs the Almond kernel from them,
- the kernel reads the connection file, listens on the ports it lists, and starts answering
  Jupyter. In parallel, it creates the Scala compiler, and runs the [predef](install-options.md#--predef)
  code, if any,
- when cells run, the kernel writes the classes it compiles from them in a new temporary
  directory, `almond-output…`. Dependencies added from notebooks, with `//> using dep` or
  `import $ivy`, are downloaded to the coursier cache too. Almond also keeps the Ammonite
  compilation cache (see [`--disable-cache`](install-options.md#--disable-cache)) in
  `~/.cache/almond/ammonite` on Linux, `~/Library/Caches/Almond/ammonite` on macOS, and
  `%LOCALAPPDATA%\Almond\cache\ammonite` on Windows.

The kernel logs go to its standard error, which ends up in the terminal Jupyter runs in.
See [`--log`](install-options.md#--log) to get more details.

### With the newer launcher

The [newer launcher](install-advanced.md#creating-an-almond-launcher-and-installing-it---newer-launcher)
gets installed the same way, but what Jupyter starts then is a small launcher, rather than
the kernel itself. That launcher answers Jupyter on its own, and reads the
[launcher directives](usage-directives.md#launcher-directives) of the first cells, like
`//> using scala` or `//> using jvm`. Upon the first cell with actual code, it:
- resolves the Almond kernel for the requested Scala version, and downloads its JARs to the
  coursier cache, showing progress in the cell output if anything needs to be downloaded,
- if `//> using jvm` was used, downloads that JVM via coursier, if it's not already there, to
  the coursier archive cache (`~/.cache/coursier/arc` on Linux,
  `~/Library/Caches/Coursier/arc` on macOS, `%LOCALAPPDATA%\Coursier\Cache\arc` on Windows),
- writes a launcher for that kernel, `almond….jar`, to the temporary directory, alongside
  JSON files with the messages it received but didn't process yet, and with the kernel options
  of the directives, if any,
- stops listening on the ports of the connection file, and starts the kernel with that launcher,
  in a new JVM process. That JVM is the one of `//> using jvm` or `//> using javaCmd`, or the
  `java` command of the `PATH` (see [Memory](install-advanced.md#memory) for its default Java
  options).

The kernel listens on the same ports, then runs the cell that started it, and the following ones.
From the point of view of Jupyter, both processes are the same kernel. The launcher process waits
for the kernel to exit, and shuts it down if it's itself shut down. It deletes its temporary
files upon exit.
