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

# Detect the OS up-front so both the check below and the test selection further down can use Mill.
if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "MINGW" ]; then
  mill=./mill.bat
  windows=true
else
  mill=./mill
  windows=false
fi

# Every integration test class must be assigned to a group in the case statement below.
# known_classes is the source of truth: we ask Mill which test classes it discovers and fail if any
# of them is missing here, so a newly added (or renamed) test class can't silently be left out of a
# group. The set of classes is the same on every OS, so we only run this check on Linux to avoid
# paying for it three times.
if [ "$(uname -s)" = "Linux" ]; then
  known_classes="
almond.integration.KernelTestsSimple212
almond.integration.KernelTestsSimple213
almond.integration.KernelTestsSimple3
almond.integration.KernelTestsTwoStepStartup
almond.integration.KernelTestsTwoStepStartup212
almond.integration.KernelTestsTwoStepStartup213
almond.integration.KernelTestsTwoStepStartup3
"

  # ./mill show scala.integration.test.discoveredTestClasses prints a JSON array of class names. Mill
  # logs to stderr, so stdout is just the JSON, which jq turns into one class name per line.
  discovered_json="$($mill -i show scala.integration.test.discoveredTestClasses)"
  actual_classes="$(echo "$discovered_json" | jq -r '.[]' | sort -u)"
  expected_classes="$(echo "$known_classes" | grep -vE '^[[:space:]]*$' | sort -u)"

  unknown_classes="$(comm -23 <(echo "$actual_classes") <(echo "$expected_classes"))"
  if [ -n "$unknown_classes" ]; then
    echo "Found integration test class(es) not assigned to a group in $0:" >&2
    echo "$unknown_classes" | sed 's/^/  /' >&2
    echo >&2
    echo "Add each of them to the 'known_classes' list and to the matching 'case' branch" >&2
    echo "in .github/scripts/run/run-its.sh (and, if needed, a new GROUP in .github/workflows/ci.yml)." >&2
    exit 1
  fi
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
