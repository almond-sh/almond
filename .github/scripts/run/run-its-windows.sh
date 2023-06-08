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

"$SCALA_CLI" --power package .github/scripts/run --native-image -o "$RUN_APP"

trap "jps -mlv" EXIT

./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup212.*" > test-args-212.json
cat test-args-212.json
"$RUN_APP" test-args-212.json
checkResults

./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup213.*" > test-args-213.json
cat test-args-213.json
"$RUN_APP" test-args-213.json
checkResults

./mill -i show "scala.integration.test.testCommand" "almond.integration.KernelTestsTwoStepStartup3.*" > test-args-3.json
cat test-args-3.json
"$RUN_APP" test-args-3.json
checkResults
