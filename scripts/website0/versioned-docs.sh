#!/usr/bin/env bash
set -euv

# TODO Convert to mill tasks

# No argument: copy the versioned docs of $VERSIONED_DOCS_DIR, a checkout of
#   almond-sh/versioned-docs, into $WEBSITE_DIR
# --update: same, then add the docs of the version of the current git tag to $WEBSITE_DIR
# --push: push the versioned docs of $WEBSITE_DIR to $VERSIONED_DOCS_REPO, using $GH_TOKEN
#
# --update runs the website npm packages, so it doesn't push anything itself, and
# --push runs none of them, so that they never run alongside $GH_TOKEN.

if [ $# = 0 ]; then
  MODE=copy
elif [ $# = 1 ]; then
  if [ "$1" = --update ]; then
    MODE=update
  elif [ "$1" = --push ]; then
    MODE=push
  else
    echo "Unrecognized argument: $1" 1>&2
    exit 1
  fi
else
  echo "Too many arguments passed (expected one or zero arguments)" 1>&2
  exit 1
fi

if [ "$MODE" != copy ]; then
  TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)")"
  if [[ ${TAG} != v* ]]; then
    echo "Not on a git tag"
    exit 1
  fi
  VERSION="$(echo "$TAG" | sed 's@^v@@')"
fi

if [ "$MODE" = push ]; then
  REMOTE="https://x-access-token:${GH_TOKEN}@github.com/$VERSIONED_DOCS_REPO.git"

  mkdir -p target
  rm -rf target/versioned-docs
  git clone "$REMOTE" -b main target/versioned-docs

  cp -R "$WEBSITE_DIR"/version* target/versioned-docs/
  cd target/versioned-docs
  git config user.name "GitHub Actions"
  git config user.email "actions@github.com"
  git add version*
  git commit -m "Add doc for $VERSION"

  git push origin main

  cd -
  rm -rf target/versioned-docs
else
  cp -R "$VERSIONED_DOCS_DIR/versioned_docs" "$VERSIONED_DOCS_DIR/versioned_sidebars" \
    "$VERSIONED_DOCS_DIR/versions.json" "$WEBSITE_DIR/"

  if [ "$MODE" = update ]; then
    cd "$WEBSITE_DIR"
    yarn run version "$VERSION"
    cd -
  fi
fi
