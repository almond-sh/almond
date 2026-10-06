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

See [here](install-versions.md) for the compatible Almond versions / Scala
versions.

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
