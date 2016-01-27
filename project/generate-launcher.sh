#!/bin/bash

VERSION=0.3.0-SNAPSHOT

  #--no-default \
"$(dirname "$0")/../coursier" bootstrap \
  com.github.alexarchambault.jupyter:jupyter-scala-cli_2.11.7:$VERSION \
  -r central \
  -r https://dl.bintray.com/scalaz/releases \
  -D "\${user.home}/.jupyter-scala/bootstrap/$VERSION" \
  -f -o jupyter-scala \
  -M jupyter.scala.JupyterScala
