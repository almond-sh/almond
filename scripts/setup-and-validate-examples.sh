#!/usr/bin/env bash
set -e

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ "$TRAVIS_BRANCH" != "" ]; then
  source scripts/setup-sbt-extra.sh
fi

export FORCE_SIMPLE_VERSION=1

sbt \
  interpreter-api/exportVersions \
  publishLocal

./scripts/validate-examples.sh

