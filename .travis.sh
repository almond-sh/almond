#!/usr/bin/env bash
set -e

gpg --list-secret
gpg --version
gpg2 --list-secret
gpg2 --version

sbt ++$TRAVIS_SCALA_VERSION'!' "show version" test mimaReportBinaryIssues
