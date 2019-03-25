#!/usr/bin/env bash
set -euv

if [[ ${TRAVIS_TAG} != v* ]]; then
  echo "Not on a git tag"
  exit 1
fi

SCALA212_VERSION="$(grep -oP '(?<=def scala212 = ")[^"]*(?<!")' project/Settings.scala)"
SCALA211_VERSION="$(grep -oP '(?<=def scala211 = ")[^"]*(?<!")' project/Settings.scala)"

ALMOND_VERSION="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"

DOCKER_REPO=almondsh/almond
IMAGE_NAME=${DOCKER_REPO}:${ALMOND_VERSION}

sbt '+ publishLocal'
cp -r $HOME/.ivy2/local/ ivy-local/

docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} --build-arg=LOCAL_IVY=yes \
  --build-arg SCALA_VERSIONS="$SCALA211_VERSION $SCALA212_VERSION" -t ${IMAGE_NAME} .
docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} --build-arg=LOCAL_IVY=yes \
  --build-arg SCALA_VERSIONS="$SCALA211_VERSION" -t ${IMAGE_NAME}-scala-${SCALA211_VERSION} .
docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} --build-arg=LOCAL_IVY=yes \
  --build-arg SCALA_VERSIONS="$SCALA212_VERSION" -t ${IMAGE_NAME}-scala-${SCALA212_VERSION} .

echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

docker push ${IMAGE_NAME}-scala-${SCALA211_VERSION}
docker push ${IMAGE_NAME}-scala-${SCALA212_VERSION}
docker push ${IMAGE_NAME}

docker tag ${IMAGE_NAME} ${DOCKER_REPO}:latest
docker push ${DOCKER_REPO}:latest
