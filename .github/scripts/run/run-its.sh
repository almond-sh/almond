#!/usr/bin/env bash
set -eu

SCALA_CLI="scala-cli"
RUN_APP="./run"

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  SCALA_CLI="$SCALA_CLI.bat"
  RUN_APP="$RUN_APP.exe"
fi

checkResults() {
  RESULTS="$(jq -r '.[1] | map(.status) | join("\n")' < test-output.json | sort -u)"
  if [ "$RESULTS" != "Success" ]; then
    exit 1
  fi
}

"$SCALA_CLI" --cli-version 1.12.0 --power \
  package \
    --server=false \
    .github/scripts/run \
    --native-image \
    -o "$RUN_APP" \
    -- \
      --no-fallback

trap "jps -mlv" EXIT

./mill -i show "scala.integration.test.testCommand" > test-args.json
cat test-args.json
"$RUN_APP" test-args.json
checkResults
