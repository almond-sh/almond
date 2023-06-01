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

"$SCALA_CLI" --power package --server=false .github/scripts/run --native-image -o "$RUN_APP"

function exitHook() {
  echo jps -mlv
  jps -mlv
}
trap exitHook EXIT

# Seems native-image sends its output to stdout, which borks the command JSON output
# So we run the show command a first time, so that native-image can run, before actually
# saving its output.
./mill -i show "scala.integration.native.test.testCommand"
./mill -i show "scala.integration.native.test.testCommand" > test-args.json
cat test-args.json
"$RUN_APP" test-args.json
checkResults
