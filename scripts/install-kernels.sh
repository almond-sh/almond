#!/usr/bin/env bash
set -eu

[ -z "$SCALA_VERSIONS" ] && { echo "SCALA_VERSIONS is empty" ; exit 1; }
[ -z "$ALMOND_VERSION" ] && { echo "ALMOND_VERSION is empty" ; exit 1; }

# coursier command to use (the native launcher in the Docker image), override with COURSIER=/path/to/coursier if needed
COURSIER="${COURSIER:-cs}"
# oldest coursier version known to work with the options passed below
COURSIER_MIN_VERSION="2.1.25"
command -v "$COURSIER" >/dev/null || { echo "coursier (${COURSIER}) not found" ; exit 1; }
COURSIER_VERSION=$("$COURSIER" version 2>/dev/null | tail -n 1)
if [[ $(printf '%s\n%s\n' "$COURSIER_MIN_VERSION" "$COURSIER_VERSION" | sort -V | head -n 1) != "$COURSIER_MIN_VERSION" ]]; then
  echo "coursier ${COURSIER_VERSION} is too old, coursier >= ${COURSIER_MIN_VERSION} is required"
  exit 1
fi

for SCALA_FULL_VERSION in ${SCALA_VERSIONS}; do
  # remove patch version
  SCALA_MAJOR_VERSION=${SCALA_FULL_VERSION%.*}
  # remove all dots for the kernel id
  SCALA_MAJOR_VERSION_TRIMMED=$(echo ${SCALA_MAJOR_VERSION} | tr -d .)
  # the suffix of the modules we publish, which are cross-published for binary Scala versions,
  # and the Scala modules to force to the full Scala version (like the almond launcher does)
  if [[ ${SCALA_FULL_VERSION} == 3.* ]]; then
    SCALA_SUFFIX=3
    SCALA_MODULES=(scala3-library_3 scala3-compiler_3 scala3-interfaces)
  else
    SCALA_SUFFIX=${SCALA_MAJOR_VERSION}
    SCALA_MODULES=(scala-library scala-compiler scala-reflect)
  fi
  echo Installing almond ${ALMOND_VERSION} for Scala ${SCALA_FULL_VERSION}
  EXTRA_ARGS=()
  for SCALA_MODULE in "${SCALA_MODULES[@]}"; do
    EXTRA_ARGS+=(--force-version "org.scala-lang:${SCALA_MODULE}:${SCALA_FULL_VERSION}")
  done
  if [[ ${ALMOND_VERSION} == *-SNAPSHOT ]]; then
    EXTRA_ARGS+=('--standalone')
  fi
  # scala-kernel-api is loaded in a class loader shared with user code
  "$COURSIER" bootstrap \
      -r jitpack \
      sh.almond:scala-kernel_${SCALA_SUFFIX}:${ALMOND_VERSION} \
      --shared sh.almond:scala-kernel-api_${SCALA_SUFFIX} \
      --scala ${SCALA_FULL_VERSION} \
      --default=true --sources \
      -o almond "${EXTRA_ARGS[@]}"
  ./almond --install --log info --metabrowse --id scala${SCALA_MAJOR_VERSION_TRIMMED} --display-name "Scala ${SCALA_MAJOR_VERSION}"
  rm -f almond
done
echo Installation was successful
