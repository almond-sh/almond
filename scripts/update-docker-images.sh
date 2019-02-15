#!/usr/bin/env bash
set -euv

SCALA212_VERSION="$(grep -oP '(?<=def scala212 = ")[^"]*(?<!")' project/Settings.scala)"
SCALA211_VERSION="$(grep -oP '(?<=def scala211 = ")[^"]*(?<!")' project/Settings.scala)"

VERSION="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"

mkdir -p target
cd target

git clone "https://$GH_TOKEN@github.com/almond-sh/docker-images.git" -b template
cd docker-images

BRANCHES=()

for sv in "$SCALA211_VERSION" "$SCALA212_VERSION"; do

  BRANCH="almond-$VERSION-scala-$sv"
  git checkout -b "$BRANCH" template
  BRANCHES+=("$BRANCH")

  mv Dockerfile Dockerfile.tmp
  cat Dockerfile.tmp |
    sed 's@{SCALA_VERSION}@'"$sv"'@g' |
    sed 's@{VERSION}@'"$VERSION"'@g' > Dockerfile
  rm -f Dockerfile.tmp

  git add Dockerfile
  git commit -m "almond $VERSION, scala $sv"
done

DEFAULT_BRANCH="almond-$VERSION-scala-$SCALA212_VERSION"

BRANCH="almond-$VERSION"
git checkout -b "$BRANCH" "$DEFAULT_BRANCH"
BRANCHES+=("$BRANCH")

BRANCH="latest"
git checkout -b "$BRANCH" "$DEFAULT_BRANCH"
BRANCHES+=("$BRANCH")

for b in "${BRANCHES[@]}"; do
  if [ "$b" = "latest" ]; then
    git push -f origin "$b"
  else
    git push origin "$b"
  fi
done
