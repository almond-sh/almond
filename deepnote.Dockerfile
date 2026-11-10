# Image with almond kernels for Deepnote (https://deepnote.com), to be used there as a custom
# environment image. Unlike the main Dockerfile, it's based on Deepnote's own Python image: Deepnote
# brings and starts its own Jupyter server, so the image only needs the kernels, and what Deepnote
# expects from images (https://deepnote.com/docs/custom-environment).
#
# Expects ALMOND_VERSION and SCALA_VERSIONS to be set as build args, like the main Dockerfile:
# docker build -f deepnote.Dockerfile --build-arg ALMOND_VERSION=0.15.1 --build-arg SCALA_VERSIONS="2.13.18 3.9.0" .
#
# deepnote/python images are linux/amd64 only, so is this image.

# Pinned by digest, for the same reasons as the base image of the main Dockerfile (Dependabot updates it)
FROM deepnote/python:3.12@sha256:6244475cac520ed1cbc0d2f928faae71f5d739d3d6eb7c032900974fcc0a022d

# Deepnote's images set DEEPNOTE_PYTHON_KERNEL_ONLY=true, which makes Deepnote ignore non-Python
# kernels (Deepnote's own R image empties it too). DEFAULT_KERNEL_NAME is the kernel Deepnote uses
# for new notebooks, and for notebooks that don't say which kernel they use.
# The kernel launchers load almond's JARs from the coursier cache: it's put at a fixed location,
# filled when building the image, rather than under the home directory of whoever builds or runs
# the image.
ENV DEEPNOTE_PYTHON_KERNEL_ONLY="" \
    DEFAULT_KERNEL_NAME="scala213" \
    COURSIER_CACHE="/opt/coursier/cache"

RUN apt-get -y update && \
    apt-get install --no-install-recommends -y \
      openjdk-17-jre-headless \
      ca-certificates-java && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Native coursier launcher as "cs", same version as in the main Dockerfile.
# The SHA-256 checksum is the one of the COURSIER_VERSION launcher, and needs to be updated along with it.
ARG COURSIER_VERSION=2.1.26
ARG CS_X86_64_SHA256=348e37bc2a8c706640e6b032c4551a0e055a7f1537485b4a91540e9d3598ec6d
RUN set -eu; \
    curl -fLo /tmp/cs.gz https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux.gz; \
    printf '%s  %s\n' "$CS_X86_64_SHA256" /tmp/cs.gz | sha256sum --strict -c -; \
    gzip -d < /tmp/cs.gz > /usr/local/bin/cs; \
    rm -f /tmp/cs.gz; \
    chmod +x /usr/local/bin/cs

ARG ALMOND_VERSION
# Set to a single Scala version string or list of Scala versions separated by a space.
# i.e SCALA_VERSIONS="2.12.19 2.13.11"
ARG SCALA_VERSIONS
# The kernel specs go in a system-wide Jupyter directory: Deepnote runs its Jupyter server from a
# virtualenv it creates when starting, which looks for kernels there.
COPY scripts/install-kernels.sh /tmp/almond-install/
RUN cd /tmp/almond-install && \
    JUPYTER_KERNELS_DIR=/usr/local/share/jupyter/kernels ./install-kernels.sh && \
    cd / && \
    rm -rf /tmp/almond-install && \
    chmod -R a+rX /usr/local/share/jupyter/kernels && \
    chmod -R a+rwX "$COURSIER_CACHE"
