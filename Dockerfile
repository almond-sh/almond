# Dockerfile with support for creating images with kernels for multiple Scala versions.
# Expects ALMOND_VERSION and SCALA_VERSIONS to be set as build arg, like this:
# docker build --build-arg ALMOND_VERSION=0.13.11 --build-arg SCALA_VERSIONS="2.12.19 2.13.11" .

# Set LOCAL_IVY=yes to have the contents of ivy-local copied into the image.
# Can be used to create an image with a locally built almond that isn't on maven central yet.
ARG LOCAL_IVY=no

FROM jupyter/base-notebook as coursier_base

USER root

RUN apt-get -y update && \
    apt-get install --no-install-recommends -y \
      curl \
      openjdk-17-jre-headless \
      ca-certificates-java && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

RUN curl -fL https://github.com/coursier/coursier/releases/download/v2.1.25/cs-$(uname -m)-pc-linux.gz | \
      gzip -d > /usr/local/bin/coursier && \
    chmod +x /usr/local/bin/coursier

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
