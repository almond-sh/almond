# Image the OSS Scanner (https://github.com/anthropics/oss-scanner) builds almond in, then scans it
# in, without network access. See threat_model.md, next to this file, for what it holds and how to
# use it.
#
# The build is the one of CI (.github/workflows/ci.yml): Mill, on JDK 21. Mill downloads everything
# else - its own JVM, the dependencies, and the JDK 17 that the oldest Scala 2 versions we support
# are compiled and tested with - to the coursier and Mill caches under /root/.cache, while there is a
# network.
FROM eclipse-temurin:21-jdk-noble
ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8
# git: the build computes the almond version from the git history.
# python3-jupyter-client: a Jupyter client, to talk to a running kernel over ZeroMQ.
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git python3 python3-jupyter-client \
    && rm -rf /var/lib/apt/lists/*

# scanner contract: the checkout lives inside the image, at /src
COPY . /src
WORKDIR /src

# The commands of the oss-scanner module of build.mill compute the Scala versions they work with
# from mill-build/src/almondbuild/ScalaVersions.scala, rather than this file naming them.

# Compile the modules and their tests, for the latest Scala 2.13, 3 and 2.12 versions. A compilation
# failure fails the build.
RUN ./mill -i --ticker false oss-scanner.compile

# A kernel that can be started on its own, for the Scala version dev.launcher defaults to (the latest
# 2.13 one), as almond-kernel on the PATH.
RUN launcher="$(./mill -i --ticker false dev.launcher)" \
    && test -f "$launcher" \
    && ln -s "$launcher" /usr/local/bin/almond-kernel

# Run the tests of the latest Scala 2.13 and 3 versions the way CI does. Some of them fetch
# dependencies at run time (`import $ivy`, `//> using dep`), so running them here gets those into
# the coursier cache too. A failing test doesn't fail the build, and the time limit keeps the build
# within the 45 minutes the scanner gives it.
RUN timeout 1200 ./mill -i --ticker false oss-scanner.test \
    || echo "WARNING: the tests reported failures"

# The scan has no network: have coursier only use what is in its cache from here on, so that
# nothing waits on a repository it can't reach.
ENV COURSIER_MODE=offline

# Show that tests run with what the image holds.
RUN ./mill -i --ticker false oss-scanner.smokeTest \
    || echo "WARNING: the offline test run reported failures"
