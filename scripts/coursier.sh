#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.."; pwd)"
mkdir -p "$DIR/target"

if [ ! -e "$DIR/target/coursier" ]; then
  curl -Lo "$DIR/target/coursier" "https://github.com/coursier/coursier/raw/v1.1.0-M7/coursier"
  chmod +x "$DIR/target/coursier"
fi

exec "$DIR/target/coursier" "$@"
