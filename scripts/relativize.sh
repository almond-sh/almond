#!/usr/bin/env bash
set -e

"$(dirname "${BASH_SOURCE[0]}")/coursier.sh" launch com.lihaoyi:ammonite_2.12.6:1.2.1 -M ammonite.Main -- \
  "$(dirname "${BASH_SOURCE[0]}")/relativize.sc" "$@"
