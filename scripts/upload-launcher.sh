#!/usr/bin/env bash
set -eu

cd "$(dirname "${BASH_SOURCE[0]}")"

ALMOND_VERSION="$(git describe --tags --abbrev=0 --always | sed 's@^v@@')"

RELEASE_ID="$(http "https://api.github.com/repos/almond-sh/almond/releases?access_token=$GH_TOKEN" | jq -r '.[] | select(.name == "v'"$ALMOND_VERSION"'") | .id')"

echo "Release ID is $RELEASE_ID"

export OUTPUT="launcher"

# should work fine with sonatype releases repo
./generate-launcher.sh -f -r sonatype:releases


# wait for sync to Maven Central
ATTEMPT=0
while ! ./generate-launcher.sh -f; do
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
  --data-binary @launcher \
  -H "Content-Type: application/zip" \
  "https://uploads.github.com/repos/almond-sh/almond/releases/$RELEASE_ID/assets?name=almond&access_token=$GH_TOKEN"

