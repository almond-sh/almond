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

# The -j 1 is temporary. When run in parallel, several localRepo tasks might try to publish
# a module twice in parallel, which triggers FileAlreadyExistsException-s on Windows.
# Refactoring localRepo with the newer Mill publishStage stuff should help address that, and
# allow to drop the -j 1.

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  ./mill.bat -i -j 1 "scala.integration.test.testForked" "almond.integration.KernelTestsTwoStepStartup212.*"
  ./mill.bat -i -j 1 "scala.integration.test.testForked" "almond.integration.KernelTestsTwoStepStartup213.*"
  ./mill.bat -i -j 1 "scala.integration.test.testForked" "almond.integration.KernelTestsTwoStepStartup3.*"
else
  ./mill -i -j 1 "scala.integration.test.testForked"
fi
