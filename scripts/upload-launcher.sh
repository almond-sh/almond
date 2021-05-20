#!/usr/bin/env bash
set -eu

# TODO Convert to a mill task

TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)")"

if [[ ${TAG} != v* ]]; then
  echo "Not on a git tag"
  exit 1
fi

export VERSION="$(echo "$TAG" | sed 's@^v@@')"

# config
REPO="almond-sh/almond"
NAME="almond"
CMD="./scripts/generate-launcher.sh -f --bat=true" # will work once sync-ed to Maven Central

# initial check with Sonatype staging (releases now redirects to Central)
mkdir -p target/launcher
export OUTPUT="target/launcher/$NAME"

# actual script
RELEASE_ID="$(curl "https://api.github.com/repos/$REPO/releases?access_token=$GH_TOKEN" | jq -r '.[] | select(.tag_name == "v'"$VERSION"'") | .id')"

if [ "$RELEASE_ID" = "" ]; then
  echo "Error: no release id found" 1>&2
  exit 1
fi

echo "Release ID is $RELEASE_ID"

# wait for sync to Maven Central
ATTEMPT=0
while ! $CMD; do
  if [ "$ATTEMPT" -ge 25 ]; then
    echo "Not synced to Maven Central after $ATTEMPT minutes, exiting"
    exit 1
  else
    echo "Not synced to Maven Central after $ATTEMPT minutes, waiting 1 minute"
    ATTEMPT=$(( $ATTEMPT + 1 ))
    sleep 60
  fi
done

echo "Uploading launcher"

curl \
  --data-binary "@$OUTPUT" \
  -H "Content-Type: application/zip" \
  "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$NAME&access_token=$GH_TOKEN"

echo "Uploading bat file"

curl \
  --data-binary "@$OUTPUT.bat" \
  -H "Content-Type: text/plain" \
  "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$NAME.bat&access_token=$GH_TOKEN"

