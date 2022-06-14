#!/usr/bin/env bash
set -euv

# TODO Convert to mill tasks

if [ $# = 0 ]; then
  UPDATE=0
elif [ $# = 1 ]; then
  if [ "$1" = --update ]; then
    UPDATE=1
  else
    echo "Unrecognized argument: $1" 1>&2
    exit 1
  fi
else
  echo "Too many arguments passed (expected one or zero arguments)" 1>&2
  exit 1
fi

if [ "$UPDATE" = 1 ]; then
  TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)")"
  if [[ ${TAG} != v* ]]; then
    echo "Not on a git tag"
    exit 1
  fi

  REMOTE="https://${GH_TOKEN}@github.com/$VERSIONED_DOCS_REPO.git"
else
  REMOTE="https://github.com/$VERSIONED_DOCS_REPO.git"
fi

mkdir -p target
git clone "$REMOTE" -b master target/versioned-docs
if [ -d target/versioned-docs/versioned_docs ]; then
  cp -R target/versioned-docs/versioned_docs "$WEBSITE_DIR/"
fi
if [ -d target/versioned-docs/versioned_sidebars ]; then
  cp -R target/versioned-docs/versioned_sidebars "$WEBSITE_DIR/"
fi
if [ -f target/versioned-docs/versions.json ]; then
  cp target/versioned-docs/versions.json "$WEBSITE_DIR/"
fi

if [ "$UPDATE" = 1 ]; then
  git config --global user.name "GitHub Actions"
  git config --global user.email "actions@github.com"

  VERSION="$(echo "$TAG" | sed 's@^v@@')"

  cd "$WEBSITE_DIR"
  yarn run version "$VERSION"
  cd -

  cp -R "$WEBSITE_DIR"/version* target/versioned-docs/
  cd target/versioned-docs
  git add version*
  git commit -m "Add doc for $VERSION"

  git push origin master

  cd -
  rm -rf target/versioned-docs
fi
