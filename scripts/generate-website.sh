#!/usr/bin/env bash
set -e

# FIXME Lots of duplications with https://github.com/coursier/coursier/blob/3309b64102678550b1393c524dddf2b71fb9d931/scripts/generate-website.sh

cd "$(dirname "${BASH_SOURCE[0]}")"

# Assumes 'sbt interpreter-api/exportVersions' has been run

VERSION="$(cat ../modules/shared/interpreter-api/target/version)"
echo "Current version is $VERSION"

SCALA_VERSION="$(cat ../modules/shared/interpreter-api/target/scala-version)"

if echo "$VERSION" | grep -q -- '-SNAPSHOT$'; then
  EXTRA_COURSIER_ARGS="-r sonatype:snapshots "
else
  EXTRA_COURSIER_ARGS=""
fi

echo "Processing Markdown files"

# first processing md files via https://github.com/olafurpg/mdoc
# requires the cache modules and its dependencies to have been published locally
# with
#   sbt interpreter-api/publishLocal scala-kernel-api/publishLocal almond-spark/publishLocal
../scripts/coursier.sh launch \
  "com.geirsson:mdoc_$SCALA_VERSION:0.5.0" \
  "sh.almond:scala-kernel-api_$SCALA_VERSION:$VERSION" \
  -- \
    --in ../docs/pages \
    --out ../docs/processed-pages \
    --site.VERSION "$VERSION" \
    --site.SCALA_VERSION "$SCALA_VERSION" \
    --site.EXTRA_COURSIER_ARGS "$EXTRA_COURSIER_ARGS"

echo "Generating website"

cd ../docs/website
npm install
yarn run build
cd -

../scripts/relativize.sh ../docs/website/build

DIR="$(cd ../docs/website/build/almond; pwd)"

echo
echo "Generated website available under $DIR"


cat << EOF
Open the generated website with

  npx http-server docs/website/build/almond

EOF
