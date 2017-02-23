#!/bin/bash
set -ev

export TRAVIS_SCALA_VERSION="$1"
TRAVIS_PULL_REQUEST="$2"
TRAVIS_BRANCH="$3"

PWD="$(cd "$(dirname "$0")"; pwd)"
SBT="sbt ++${TRAVIS_SCALA_VERSION}"

$SBT publishLocal

"$PWD/run-tests"

if [ "$TRAVIS_PULL_REQUEST" == "false" -a "$TRAVIS_BRANCH" == "master" ]; then
  $SBT publish
fi
