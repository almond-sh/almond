#!/usr/bin/env bash
set -e

sbt ++$TRAVIS_SCALA_VERSION'!' "show version" test
