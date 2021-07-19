#!/usr/bin/env bash
set -eu

cd "$(dirname "${BASH_SOURCE[0]}")/../examples"

OUTPUT="$(pwd)/output.ipynb"
export JUPYTER_PATH="$(pwd)/../out/jupyter-dir"

# let's switch back to the default scala version once the libs in the
# examples are fine with 2.13
SCALA_VERSION="2.12.12"
ALMOND_VERSION="$(cd .. && ./mill -i show 'scala0.scala-kernel['"$SCALA_VERSION"'].publishVersion')"

echo "Current Scala version is $SCALA_VERSION"
echo "Current version is $ALMOND_VERSION"

cleanup() {
  rm -f "$OUTPUT"
  rm -rf "$JUPYTER_PATH"
}

trap cleanup EXIT INT TERM

mkdir -p "$(pwd)/project/target"

echo "Generating bootstrap"

LAUNCHER="$(cd .. && ./mill -i launcher "$SCALA_VERSION")"

echo "Installing kernel"

KERNEL_ID="almond-sources-tmp"

"$LAUNCHER" \
  --jupyter-path "$JUPYTER_PATH/kernels" \
  --id "$KERNEL_ID" \
  --install --force \
  --trap-output \
  --predef-code 'sys.props("almond.ids.random") = "0"'

echo "publishLocal almond-scalapy"

cd .. && ./mill -i 'scala0.almond-scalapy['"$SCALA_VERSION"'].publishLocal' && cd -

ERRORS=0

while read f; do
  jupyter nbconvert \
    --to notebook \
    --execute \
    --ExecutePreprocessor.kernel_name="$KERNEL_ID" \
    "$f" \
    --output="$OUTPUT"

  sed -i "s@\\\\r\\\\n@\\\\n@g" "$OUTPUT"

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
