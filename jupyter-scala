#!/bin/bash

VERSION=0.4.0-RC3
AMMONIUM_VERSION=0.8.1
SCALA_VERSION=2.11.8 # Set to 2.12.1 for Scala 2.12

exec coursier launch \
  -r sonatype:releases -r sonatype:snapshots \
  -i ammonite \
  -I ammonite:org.jupyter-scala:ammonite-runtime_$SCALA_VERSION:$AMMONIUM_VERSION \
  -I ammonite:org.jupyter-scala:scala-api_$SCALA_VERSION:$VERSION \
  org.jupyter-scala:scala-cli_$SCALA_VERSION:$VERSION \
  -- \
    --id scala \
    --name "Scala" \
    "$@"
