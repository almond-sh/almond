#!/usr/bin/env bash
set -eu

[ -z "$SCALA_VERSIONS" ] && { echo "SCALA_VERSIONS is empty" ; exit 1; }
[ -z "$ALMOND_VERSION" ] && { echo "ALMOND_VERSION is empty" ; exit 1; }
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
  coursier bootstrap \
      -r jitpack \
      -i user -I user:sh.almond:scala-kernel-api_${SCALA_SUFFIX}:${ALMOND_VERSION} \
      sh.almond:scala-kernel_${SCALA_SUFFIX}:${ALMOND_VERSION} \
      --scala ${SCALA_FULL_VERSION} \
      --default=true --sources \
      -o almond "${EXTRA_ARGS[@]}"
  ./almond --install --log info --metabrowse --id scala${SCALA_MAJOR_VERSION_TRIMMED} --display-name "Scala ${SCALA_MAJOR_VERSION}"
  rm -f almond
done
echo Installation was successful
