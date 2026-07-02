#!/usr/bin/env bash
set -eu

trap "jps -mlv" EXIT

# The -j 1 is temporary. When run in parallel, several localRepo tasks might try to publish
# a module twice in parallel, which triggers FileAlreadyExistsException-s on Windows.
# Refactoring localRepo with the newer Mill publishStage stuff should help address that, and
# allow to drop the -j 1.

# Optional first argument: a test group (212, 213 or 3) so that the integration tests can be
# split across parallel CI jobs. When no group is passed, all tests are run (as before).
# On Windows, only the "two step startup" tests are run, matching the previous behaviour.
group="${1:-all}"

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  mill=./mill.bat
  windows=true
else
  mill=./mill
  windows=false
fi

case "$group" in
  212)
    tests="almond.integration.KernelTestsTwoStepStartup212.*"
    $windows || tests="almond.integration.KernelTestsSimple212.* $tests"
    ;;
  213)
    tests="almond.integration.KernelTestsTwoStepStartup213.*"
    $windows || tests="almond.integration.KernelTestsSimple213.* almond.integration.KernelTestsTwoStepStartup.* $tests"
    ;;
  3)
    tests="almond.integration.KernelTestsTwoStepStartup3.*"
    $windows || tests="almond.integration.KernelTestsSimple3.* $tests"
    ;;
  all)
    if $windows; then
      tests="almond.integration.KernelTestsTwoStepStartup212.* almond.integration.KernelTestsTwoStepStartup213.* almond.integration.KernelTestsTwoStepStartup3.*"
    else
      tests=""
    fi
    ;;
  *)
    echo "Unknown test group: $group (expected 212, 213, 3 or all)" >&2
    exit 1
    ;;
esac

$mill -i -j 1 "scala.integration.test.testForked" $tests
