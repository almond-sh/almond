#!/bin/bash

VERSION=${VERSION:-0.3.0-M3}

if [ "$1" = "--2.10" ]; then
  SCALA_VERSION=2.10.6
  OUTPUT=jupyter-scala-2.10
  EXTRA="
    -I jupyter-scala-compile:org.scala-lang:scala-compiler:$SCALA_VERSION \
    -I jupyter-scala-compile:org.scalamacros:quasiquotes_2.10:2.0.1 \
  "
else
  SCALA_VERSION=2.11.8
  OUTPUT=jupyter-scala
  EXTRA=
fi

"$(dirname "$0")/../coursier" bootstrap \
  com.github.alexarchambault.jupyter:scala-cli_$SCALA_VERSION:$VERSION \
  -I jupyter-scala-compile:com.github.alexarchambault.jupyter:scala-api_$SCALA_VERSION:$VERSION \
  -I jupyter-scala-macro:org.scala-lang:scala-compiler:$SCALA_VERSION \
  $EXTRA \
  -i jupyter-scala-compile,jupyter-scala-macro \
  -r central \
  -r sonatype:releases \
  -r https://dl.bintray.com/scalaz/releases \
  -d "\${user.home}/.jupyter-scala/bootstrap" \
  -f -o "$OUTPUT" \
  -M jupyter.scala.JupyterScala \
  "$@"
