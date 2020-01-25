---
title: Installation
---

## Create a launcher

1. Set the desired version of Scala and Almond as environment variables:

```bash
$ SCALA_VERSION=@SCALA_VERSION@ ALMOND_VERSION=@VERSION@
```

<details>
<summary>Equivalent Windows command</summary>
```bat
> set SCALA_VERSION=@SCALA_VERSION@
> set ALMOND_VERSION=@VERSION@
```
</details>

The available versions of Scala and Almond are can be found [here](https://github.com/almond-sh/almond/releases).
Adjust `ALMOND_VERSION` and `SCALA_VERSION` at your convenience to meet your
needs. Not all combinations are guaranteed to be available. See the available
combinations [here](install-versions.md)).

2. Create a launcher via [coursier](http://get-coursier.io):

```bash
$ curl -Lo coursier https://git.io/coursier-cli
$ chmod +x coursier
$ ./coursier bootstrap @EXTRA_COURSIER_ARGS@\
    -r jitpack \
    -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
    sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
    -o almond
```

<details>
<summary>Equivalent Windows command</summary>
```bat
> bitsadmin /transfer downloadCoursierCli https://git.io/coursier-cli "%cd%\coursier"
> bitsadmin /transfer downloadCoursierBat https://git.io/coursier-bat "%cd%\coursier.bat"
> .\coursier bootstrap ^
    -r jitpack ^
    -i user -I user:sh.almond:scala-kernel-api_%SCALA_VERSION%:%ALMOND_VERSION% ^
    sh.almond:scala-kernel_%SCALA_VERSION%:%ALMOND_VERSION% ^
    -o almond
> .\almond --install
```
</details>

## Install the almond kernel

3. Run the launcher to install the almond kernel:

```bash
$ ./almond --install
```

<details>
<summary>Equivalent Windows command</summary>
```bat
> .\almond --install
```
</details>

4. Once the kernel is installed, you can use it within Jupyter or nteract.

If you are satisfied that the kernel is working properly, you may safely
remove the almond launcher: `rm -f almond`

## Getting help about the launcher

- Help: `./almond --help`
- Available options:

Once the kernel is installed, the generated launcher can then be safely removed, with `rm -f almond`.

## Update the almond kernel

To update the almond kernel, just re-install it, but passing the `--force` option to almond (like `./almond --install --force`). That will override any previous almond (or kernel with name `scala`).

## Uninstall the almond kernel

To uninstall the almond kernel, use `jupyter kernelspec remove scala`.
