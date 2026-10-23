---
title: Installation
---

## Install the almond kernel

A Java Virtual Machine (JVM) needs to be installed on your system. You
can check if a JVM is installed by running
```text
$ java -version
openjdk version "21.0.11" 2026-04-21 LTS
OpenJDK Runtime Environment Temurin-21.0.11+10 (build 21.0.11+10-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.11+10 (build 21.0.11+10-LTS, mixed mode, sharing)
```
If you don't have a JVM yet, we recommend installing [Eclipse Temurin](https://adoptium.net)
version 17 or later. (Almond runs on Java 8 or later, but Scala 3.8 and later require Java 17.)

Almond can then be fetched and installed with the
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

Installing Almond versions older than 0.15.0 requires a coursier version newer than 2.1.26.
Older versions don't set up the class loaders of these Almond versions correctly.

Note the `--` before `--install`, separating the arguments passed to Almond
from the ones handled by coursier.

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

Once the kernel is installed, you can use it within Jupyter or nteract.

## Getting help about the launcher

Pass `--help` instead of `--install`, like
```text
$ ./cs launch --use-bootstrap almond -- --help
```

## Update the almond kernel

To update the almond kernel, just re-install it, but passing the `--force` option to almond (like `./cs launch --use-bootstrap almond -- --install --force`). That will override any previous almond (or kernel with name `scala`).

## Uninstall the almond kernel

To uninstall the almond kernel, use `jupyter kernelspec remove scala`.
