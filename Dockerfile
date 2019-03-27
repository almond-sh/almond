# Dockerfile with support for creating images with kernels for multiple Scala versions.
# Expects ALMOND_VERSION and SCALA_VERSIONS to be set as build arg, like this:
# docker build --build-arg ALMOND_VERSION=0.3.1 --build-arg SCALA_VERSIONS="2.11.12 2.12.8" .

# Set LOCAL_IVY=yes to have the contents of ivy-local copied into the image.
# Can be used to create an image with a locally built almond that isn't on maven central yet.
ARG LOCAL_IVY=no

FROM almondsh/almond:coursier as local_ivy_yes
USER $NB_UID
ONBUILD RUN mkdir -p .ivy2/local/
ONBUILD COPY --chown=1000:100 ivy-local/ .ivy2/local/

FROM almondsh/almond:coursier as local_ivy_no

FROM local_ivy_${LOCAL_IVY}
ARG ALMOND_VERSION
# Set to a single Scala version string or list of Scala versions separated by a space.
# i.e SCALA_VERSIONS="2.11.12 2.12.8"
ARG SCALA_VERSIONS
USER $NB_UID
COPY scripts/install-kernels.sh .
RUN ./install-kernels.sh && \
    rm install-kernels.sh && \
    rm -rf .ivy2
