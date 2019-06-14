#!/usr/bin/env bash
set -e

cd "$(dirname "${BASH_SOURCE[0]}")/.."

export FORCE_SIMPLE_VERSION=1

# let's switch back to the default scala version once the libs in the
# examples are fine with 2.13.0
sbt ++2.12.8 \
  interpreter-api/exportVersions \
  publishLocal

./scripts/validate-examples.sh

