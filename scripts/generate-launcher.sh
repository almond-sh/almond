#!/bin/bash

VERSION=0.4.0-RC5
AMMONIUM_VERSION=0.8.1
SCALA_VERSION=2.11.8

# Passing the -s option to the command below generates a launcher embedding all its dependencies,
# but the resulting kernel fails to start for now.

exec coursier bootstrap \
  -r sonatype:releases -r sonatype:snapshots \
  -i ammonite \
  -I ammonite:org.jupyter-scala:ammonite-runtime_$SCALA_VERSION:$AMMONIUM_VERSION \
  -I ammonite:org.jupyter-scala:scala-api_$SCALA_VERSION:$VERSION \
  org.jupyter-scala:scala-cli_$SCALA_VERSION:$VERSION \
  -o jupyter-scala-launcher \
  "$@"
