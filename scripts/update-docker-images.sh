#!/usr/bin/env bash
set -eu

# TODO Convert to a mill task

# Builds the almond Docker images, one platform at a time, then merges them in multi-platform images
#
# Usage:
#   scripts/update-docker-images.sh build <dir>
#     Builds the images for the platform of the Docker daemon (or DOCKER_PLATFORM if set, like
#     linux/arm64), pushes them by digest only, and writes their digests and tags under <dir>.
#     Run that on each platform, then gather the content of the <dir> of each platform in a single
#     directory.
#   scripts/update-docker-images.sh merge <dir>
#     Creates the tagged multi-platform images, from the digests and tags written under <dir> by
#     the build command.

DOCKER_REPO=almondsh/almond

[[ $# -eq 2 ]] || { echo "Usage: $0 build|merge <dir>" ; exit 1; }
MODE="$1"
DIR="$2"
[[ $MODE == build || $MODE == merge ]] || { echo "Unrecognized command: $MODE (expected build or merge)" ; exit 1; }

echo "$DOCKER_PASSWORD" | docker login -u "$DOCKER_USERNAME" --password-stdin

# Builds an image for DOCKER_PLATFORM, pushes it by digest, and writes its digest and tags
# under DIR/<first tag, sanitized>/
# Usage: build_image <scala versions> <tag>... [-- <extra docker buildx build args>...]
build_image() {
  local scala_versions="$1"
  shift
  local tags=()
  while [[ $# -gt 0 && "$1" != "--" ]]; do
    tags+=("$1")
    shift
  done
  [[ $# -gt 0 ]] && shift
  local image_dir="$DIR/$(echo "${tags[0]}" | tr ':/' '__')"
  local platform_id="$(echo "$DOCKER_PLATFORM" | tr '/' '-')"
  mkdir -p "$image_dir"
  echo "Building ${tags[*]} for $DOCKER_PLATFORM"
  docker buildx build \
    --platform "$DOCKER_PLATFORM" \
    --build-arg ALMOND_VERSION="$ALMOND_VERSION" \
    --build-arg SCALA_VERSIONS="$scala_versions" \
    "$@" \
    --output "type=image,name=${DOCKER_REPO},push-by-digest=true,name-canonical=true,push=true" \
    --metadata-file "$image_dir/metadata-$platform_id.json" \
    .
  jq -r '."containerimage.digest"' "$image_dir/metadata-$platform_id.json" > "$image_dir/digest-$platform_id"
  rm -f "$image_dir/metadata-$platform_id.json"
  printf '%s\n' "${tags[@]}" > "$image_dir/tags"
}

build() {
  DOCKER_PLATFORM="${DOCKER_PLATFORM:-$(docker version -f '{{.Server.Os}}/{{.Server.Arch}}')}"

  SCALA212_VERSION="$(./mill --ticker false dev.scala212)"
  SCALA213_VERSION="$(./mill --ticker false dev.scala213)"
  SCALA3_VERSION="$(./mill --ticker false dev.scala3)"

  TAG="$(git describe --exact-match --tags --always "$(git rev-parse HEAD)" || true)"

  if [[ ${TAG} != v* ]]; then
    echo "Not on a git tag, creating snapshot image"
    ALMOND_VERSION="$(./mill show 'scala.scala-kernel['"$SCALA213_VERSION"'].publishVersion' | jq -r .)"
    IMAGE_NAME=${DOCKER_REPO}:snapshot
    ./mill '__['"$SCALA3_VERSION"'].publishLocal'
    ./mill '__['"$SCALA213_VERSION"'].publishLocal'
    ./mill '__['"$SCALA212_VERSION"'].publishLocal'
    cp -r $HOME/.ivy2/local/ ivy-local/
    build_image "$SCALA3_VERSION $SCALA213_VERSION $SCALA212_VERSION" ${IMAGE_NAME} \
      -- --build-arg LOCAL_IVY=yes
  else
    ALMOND_VERSION="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"
    echo "Creating release images for almond ${ALMOND_VERSION}"
    IMAGE_NAME=${DOCKER_REPO}:${ALMOND_VERSION}
    build_image "$SCALA3_VERSION" ${IMAGE_NAME}-scala-${SCALA3_VERSION}
    build_image "$SCALA213_VERSION" ${IMAGE_NAME}-scala-${SCALA213_VERSION}
    build_image "$SCALA212_VERSION" ${IMAGE_NAME}-scala-${SCALA212_VERSION}
    build_image "$SCALA3_VERSION $SCALA213_VERSION $SCALA212_VERSION" \
      ${IMAGE_NAME} ${DOCKER_REPO}:latest
  fi
}

# Creates a multi-platform image from the digests under image_dir, with the tags listed in its
# tags file
# Usage: merge_image <image_dir>
merge_image() {
  local image_dir="$1"
  local args=()
  local tag digest_file
  while read -r tag; do
    args+=(-t "$tag")
  done < "$image_dir/tags"
  for digest_file in "$image_dir"/digest-*; do
    args+=("${DOCKER_REPO}@$(cat "$digest_file")")
  done
  echo "Creating ${args[*]}"
  docker buildx imagetools create "${args[@]}"
}

merge() {
  local tags_files=("$DIR"/*/tags)
  [[ -f "${tags_files[0]}" ]] || { echo "No images found under $DIR" ; exit 1; }
  # the image with the "latest" tag is merged last, so that "latest" is only updated
  # if all the other images could be created
  local tags_file latest_image_dir=""
  for tags_file in "${tags_files[@]}"; do
    if grep -q -x "${DOCKER_REPO}:latest" "$tags_file"; then
      latest_image_dir="$(dirname "$tags_file")"
    else
      merge_image "$(dirname "$tags_file")"
    fi
  done
  if [[ -n "$latest_image_dir" ]]; then
    merge_image "$latest_image_dir"
  fi
}

"$MODE"
