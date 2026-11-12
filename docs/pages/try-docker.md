---
title: docker
---

Docker images of almond are automatically published on
[dockerhub](https://hub.docker.com/r/almondsh/almond) upon each release.

Run the latest version with
```
$ docker run -it --rm -p 8888:8888 almondsh/almond:latest
```

Run a specific version with
```
$ docker run -it --rm -p 8888:8888 almondsh/almond:@LATEST_RELEASE@
```

Run a specific version with a specific Scala version with
```
$ docker run -it --rm -p 8888:8888 almondsh/almond:@LATEST_RELEASE@-scala-@SCALA_VERSION@
```

The `latest` and `@LATEST_RELEASE@` images have kernels for Scala 3, Scala 2.13 and Scala 2.12,
while the `@LATEST_RELEASE@-scala-…` ones only have a kernel for the Scala version in their tag.
See [here](install-versions.md) for the compatible Almond versions / Scala
versions.

The images are based on the `base-notebook` image of the
[Jupyter Docker Stacks](https://jupyter-docker-stacks.readthedocs.io), and run Jupyter as the
`jovyan` user, from `/home/jovyan`. To work on notebooks of your machine, mount their directory
under it, like
```
$ docker run -it --rm -p 8888:8888 -v "$PWD:/home/jovyan/work" almondsh/almond:latest
```

JupyterLab in these images shows [completions while typing](usage-completion.md),
follows the system theme, and indents with 2 spaces by default.

## Memory

The kernels of the Docker images don't set a maximum heap size, so the JVM defaults to a quarter of
the memory available to the container. To change it, pass Java options to the kernels via the
`JDK_JAVA_OPTIONS` environment variable, like
```
$ docker run -it --rm -p 8888:8888 -e JDK_JAVA_OPTIONS=-Xmx8g almondsh/almond:latest
```
(`JAVA_OPTS` isn't read by the kernels of the Docker images: Jupyter runs `java` directly,
which only picks up `JDK_JAVA_OPTIONS` and `JAVA_TOOL_OPTIONS`. Only the
[launcher](install-advanced.md#memory) reads `JAVA_OPTS`.)

When limiting the memory of the container, give the kernels a share of that limit rather than
a fixed size, like
```
$ docker run -it --rm -p 8888:8888 -m 16g -e JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=75 almondsh/almond:latest
```
