#!/usr/bin/env bash
set -eu

ALMOND_VERSION="$(git describe --tags --abbrev=0 --always | sed 's@^v@@')"
SCALA_VERSION="2.12.8" # FIXME Get from .travis.yml or via sbt

OUTPUT="${OUTPUT:-almond}"

coursier bootstrap \
  -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
  --no-default -r central -r jitpack \
  sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
  -o "$OUTPUT" \
  --embed-files=false --sources --default=true \
  "$@"
