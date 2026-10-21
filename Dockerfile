# Dockerfile with support for creating images with kernels for multiple Scala versions.
# Expects ALMOND_VERSION and SCALA_VERSIONS to be set as build arg, like this:
# docker build --build-arg ALMOND_VERSION=0.13.11 --build-arg SCALA_VERSIONS="2.12.19 2.13.11" .

# Set LOCAL_IVY=yes to have the contents of ivy-local copied into the image.
# Can be used to create an image with a locally built almond that isn't on maven central yet.
ARG LOCAL_IVY=no

# Pinned by digest, so that rebuilding an almond version gives the same base image, and that a tag
# moved on quay.io can't change what ends up in the images (Dependabot updates it)
FROM quay.io/jupyter/base-notebook:lab-4.6.4@sha256:8c2070865d058ddee0570fd32a245856376bda5d17c4112e88c404885ba47c4a as coursier_base

USER root

RUN apt-get -y update && \
    apt-get install --no-install-recommends -y \
      curl \
      openjdk-17-jre-headless \
      ca-certificates-java && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Native coursier launcher as "cs", JVM one as "coursier"
# On ARM64, we use the "container" native launcher, built with GraalVM container support disabled,
# as GraalVM native images can crash when reading cgroup files in containers
# (https://github.com/coursier/coursier/pull/2742)
# The SHA-256 checksums are those of the COURSIER_VERSION launchers, and need to be updated along with it
# (they're listed on the GitHub release page, or can be computed with sha256sum).
ARG COURSIER_VERSION=2.1.26
ARG CS_X86_64_SHA256=348e37bc2a8c706640e6b032c4551a0e055a7f1537485b4a91540e9d3598ec6d
ARG CS_AARCH64_SHA256=5408497d21a728d02333d455f79cc8e445f863adef4fcc75b8ece955fc3ac1e8
ARG COURSIER_SHA256=eb0c35379e76f44404f777ab0a2335ca5326f6b7721b8cdfa873e87bd9f4ac0c
RUN set -eu; \
    case "$(uname -m)" in \
      x86_64) CS_LAUNCHER=cs-x86_64-pc-linux.gz; CS_SHA256="$CS_X86_64_SHA256" ;; \
      aarch64) CS_LAUNCHER=cs-aarch64-pc-linux-container.gz; CS_SHA256="$CS_AARCH64_SHA256" ;; \
      *) echo "Unsupported CPU architecture: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    curl -fLo /tmp/cs.gz https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/${CS_LAUNCHER}; \
    curl -fLo /usr/local/bin/coursier https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier; \
    printf '%s  %s\n' "$CS_SHA256" /tmp/cs.gz "$COURSIER_SHA256" /usr/local/bin/coursier | sha256sum --strict -c -; \
    gzip -d < /tmp/cs.gz > /usr/local/bin/cs; \
    rm -f /tmp/cs.gz; \
    chmod +x /usr/local/bin/cs /usr/local/bin/coursier

USER $NB_UID

FROM coursier_base as local_ivy_yes
USER $NB_UID
ONBUILD RUN mkdir -p .ivy2/local/
ONBUILD COPY --chown=1000:100 ivy-local/ .ivy2/local/

FROM coursier_base as local_ivy_no

FROM local_ivy_${LOCAL_IVY}
ARG ALMOND_VERSION
# Set to a single Scala version string or list of Scala versions separated by a space.
# i.e SCALA_VERSIONS="2.12.19 2.13.11"
ARG SCALA_VERSIONS
USER $NB_UID
COPY scripts/install-kernels.sh .
RUN ./install-kernels.sh && \
    rm install-kernels.sh && \
    rm -rf .ivy2

# Default JupyterLab settings (completions shown while typing, 2-space indentation, …),
# the same ones as the dev.jupyter* Mill commands use. Settings changed by users still take precedence.
COPY --chown=1000:100 examples/jupyterlab-overrides.json jupyterlab-overrides.json
RUN mkdir -p "${CONDA_DIR}/share/jupyter/lab/settings/overrides.d" && \
    mv jupyterlab-overrides.json "${CONDA_DIR}/share/jupyter/lab/settings/overrides.d/almond.json"
