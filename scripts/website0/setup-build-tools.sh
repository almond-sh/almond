#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd)"
"$DIR/cs-setup.sh"

mkdir -p bin
export PATH="$(pwd)/bin:$PATH"

./cs install --install-dir bin sbt-launcher:1.2.22 coursier:2.0.7 cs:2.0.7
./cs bootstrap -o bin/amm ammonite:1.6.2 --scala 2.12
