#!/usr/bin/env bash
set -eu

cd "$(dirname "${BASH_SOURCE[0]}")/../examples"

OUTPUT="$(pwd)/output.ipynb"
export JUPYTER_PATH="$(pwd)/../project/target/jupyter"

# Assumes 'sbt interpreter-api/exportVersions' has been run
SCALA_VERSION="$(cat ../modules/shared/interpreter-api/target/scala-version)"
ALMOND_VERSION="$(cat ../modules/shared/interpreter-api/target/version)"

echo "Current Scala version is $SCALA_VERSION"
echo "Current version is $ALMOND_VERSION"

cleanup() {
  rm -f "$OUTPUT"
  rm -rf "$JUPYTER_PATH"
}

trap cleanup EXIT INT TERM

mkdir -p "$(pwd)/project/target"

echo "Generating bootstrap"

../scripts/coursier.sh bootstrap \
  -r sonatype:releases \
  -r jitpack \
  -i user -I user:sh.almond:scala-kernel-api_$SCALA_VERSION:$ALMOND_VERSION \
  --sources --default=true \
  --embed-files=false \
  sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
  -o "$(pwd)/../project/target/launcher" \
  -f

echo "Installing kernel"

KERNEL_ID="almond-sources-tmp"

"$(pwd)/../project/target/launcher" \
  --jupyter-path "$JUPYTER_PATH/kernels" \
  --id "$KERNEL_ID" \
  --install --force \
  --trap-output \
  --predef-code 'sys.props("almond.ids.random") = "0"'

ERRORS=0

while read f; do
  jupyter nbconvert \
    --to notebook \
    --execute \
    --ExecutePreprocessor.kernel_name="$KERNEL_ID" \
    "$f" \
    --output="$OUTPUT"

  if cmp -s "$f" "$OUTPUT"; then
    echo "$f OK"
  else
    echo "$f differs"
    echo
    diff -u "$f" "$OUTPUT"
    echo
    echo

    ERRORS=$(( $ERRORS + 1 ))
  fi
done < <(find . -type f -name "*.ipynb")

if [ "$ERRORS" -gt 0 ]; then
  echo "Found $ERRORS error(s)"
  exit 1
fi
