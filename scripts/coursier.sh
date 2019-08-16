#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
mkdir -p "$DIR/target"

if [ ! -e "$DIR/target/coursier" ]; then
  curl -Lo "$DIR/target/coursier" "https://github.com/coursier/coursier/releases/download/v2.0.0-RC3-3/coursier"
  chmod +x "$DIR/target/coursier"
fi

exec "$DIR/target/coursier" "$@"
