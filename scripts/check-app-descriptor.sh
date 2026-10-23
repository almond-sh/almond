#!/usr/bin/env bash
set -euo pipefail

# Checks that the almond app descriptor under app/ (that the dev.jupyter* commands install
# kernels with) is the same as the one on the main branch of coursier/apps (that users get when
# running "cs launch almond" or "cs install almond"). Both are compared as JSON, so that
# differences in formatting or key order don't matter.

cd "$(dirname "${BASH_SOURCE[0]}")/.."

URL="https://raw.githubusercontent.com/coursier/apps/main/apps/resources/almond.json"

UPSTREAM="$(mktemp)"
trap 'rm -f "$UPSTREAM"' EXIT
curl -fsSL --retry 3 -o "$UPSTREAM" "$URL"

if ! diff -u --label "$URL" --label app/almond.json <(jq -S . "$UPSTREAM") <(jq -S . app/almond.json); then
  echo >&2
  echo "Error: app/almond.json differs from $URL" >&2
  echo "Update the latter with a pull request to https://github.com/coursier/apps, or the former." >&2
  exit 1
fi

echo "app/almond.json matches $URL"
