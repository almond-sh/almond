#!/usr/bin/env bash
set -eu

# TODO Convert to a mill task

SCALA212_VERSION="$(./mill dev.scala212)"
SCALA213_VERSION="$(./mill dev.scala213)"
SCALA3_VERSION="$(./mill dev.scala3)"

DOCKER_REPO=almondsh/almond

echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)" || true)"

if [[ ${TAG} != v* ]]; then
  echo "Not on a git tag, creating snapshot image"
  ALMOND_VERSION="$(./mill show 'scala.scala-kernel['"$SCALA213_VERSION"'].publishVersion' | jq -r .)"
  IMAGE_NAME=${DOCKER_REPO}:snapshot
  ./mill '__['"$SCALA3_VERSION"'].publishLocal'
  ./mill '__['"$SCALA213_VERSION"'].publishLocal'
  ./mill '__['"$SCALA212_VERSION"'].publishLocal'
  cp -r $HOME/.ivy2/local/ ivy-local/
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} --build-arg=LOCAL_IVY=yes \
    --build-arg SCALA_VERSIONS="$SCALA3_VERSION $SCALA213_VERSION $SCALA212_VERSION" \
    -t ${IMAGE_NAME} .
  docker push ${IMAGE_NAME}
else
  ALMOND_VERSION="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"
  echo "Creating release images for almond ${ALMOND_VERSION}"
  IMAGE_NAME=${DOCKER_REPO}:${ALMOND_VERSION}
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA3_VERSION $SCALA213_VERSION $SCALA212_VERSION" \
    -t ${IMAGE_NAME} .
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA3_VERSION" -t ${IMAGE_NAME}-scala-${SCALA3_VERSION} .
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA213_VERSION" -t ${IMAGE_NAME}-scala-${SCALA213_VERSION} .
  docker build --build-arg ALMOND_VERSION=${ALMOND_VERSION} \
    --build-arg SCALA_VERSIONS="$SCALA212_VERSION" -t ${IMAGE_NAME}-scala-${SCALA212_VERSION} .

  docker push ${IMAGE_NAME}-scala-${SCALA3_VERSION}
  docker push ${IMAGE_NAME}-scala-${SCALA213_VERSION}
  docker push ${IMAGE_NAME}-scala-${SCALA212_VERSION}
  docker push ${IMAGE_NAME}
  docker tag ${IMAGE_NAME} ${DOCKER_REPO}:latest
  docker push ${DOCKER_REPO}:latest
fi
