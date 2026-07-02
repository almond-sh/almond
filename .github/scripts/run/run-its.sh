#!/usr/bin/env bash
set -eu

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
