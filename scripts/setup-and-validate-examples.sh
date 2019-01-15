#!/usr/bin/env bash
set -e

cd "$(dirname "${BASH_SOURCE[0]}")/.."

export FORCE_SIMPLE_VERSION=1

sbt \
  interpreter-api/exportVersions \
  publishLocal

./scripts/validate-examples.sh

