#!/bin/bash
set -e

VERSION=0.4.2
AMMONIUM_VERSION=0.8.3-1
SCALA_VERSION=2.11.11 # Set to 2.12.2 for Scala 2.12

TYPELEVEL_SCALA=false # If true, set SCALA_VERSION above to a both ammonium + TLS available version (e.g. 2.11.8)

EXTRA_OPTS=()

if which coursier >/dev/null; then
  GLOBAL_COURSIER=true
  COURSIER=coursier
else
  GLOBAL_COURSIER=false
  COURSIER="$(mktemp -t coursier.XXXXXXXX)"
  trap "{ rm -f \"$COURSIER\"; }" EXIT
  echo "Getting coursier launcher..." 2>&1
  curl -s -L -o "$COURSIER" https://github.com/coursier/coursier/raw/v1.0.0-RC1/coursier
  echo "Done" 2>&1
  chmod +x "$COURSIER"
fi

if [ "$TYPELEVEL_SCALA" = true ]; then
  EXTRA_OPTS+=(
    -E org.scala-lang:scala-compiler \
    -E org.scala-lang:scala-library \
    -E org.scala-lang:scala-reflect \
    -I ammonite:org.typelevel:scala-compiler:$SCALA_VERSION \
    -I ammonite:org.typelevel:scala-library:$SCALA_VERSION \
    -I ammonite:org.typelevel:scala-reflect:$SCALA_VERSION \
  )
fi

"$COURSIER" launch \
  -r sonatype:releases -r sonatype:snapshots \
  -i ammonite \
  -I ammonite:org.jupyter-scala:ammonite-runtime_$SCALA_VERSION:$AMMONIUM_VERSION \
  -I ammonite:org.jupyter-scala:scala-api_$SCALA_VERSION:$VERSION \
  ${EXTRA_OPTS[@]} \
  org.jupyter-scala:scala-cli_$SCALA_VERSION:$VERSION \
  -- \
    --id scala \
    --name "Scala" \
    "$@"

if [ "$GLOBAL_COURSIER" = false ]; then
  rm -f "$COURSIER"
fi
