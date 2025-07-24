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

"$SCALA_CLI" --power package --server=false .github/scripts/run --native-image -o "$RUN_APP" -- --no-fallback

trap "jps -mlv" EXIT

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  ./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup212.*" > test-args-212.json
  ./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup213.*" > test-args-213.json
  ./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup3.*" > test-args-3.json

  cat test-args-212.json
  "$RUN_APP" test-args-212.json
  checkResults

  cat test-args-213.json
  "$RUN_APP" test-args-213.json
  checkResults

  cat test-args-3.json
  "$RUN_APP" test-args-3.json
  checkResults
else
  ./mill -i show "scala.integration.test.testCommand" > test-args.json
  cat test-args.json
  "$RUN_APP" test-args.json
  checkResults
fi
