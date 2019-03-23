# Dockerfile with support for creating images with kernels for multiple Scala versions.
# Expects ALMOND_VERSION and SCALA_VERSIONS to be set as build arg, like this:
# docker build --build-arg ALMOND_VERSION=0.3.1 --build-arg SCALA_VERSIONS="2.11.12 2.12.8" .

# Set LOCAL_IVY=yes to have the contents of ivy-local copied into the image.
# Can be used to create an image with a locally built almond that isn't on maven central yet.
ARG LOCAL_IVY=no

FROM almondsh/almond:coursier as local_ivy_yes
USER $NB_UID
RUN mkdir -p $HOME/.ivy2/local/
ONBUILD COPY --chown=1000:100 ivy-local/ $HOME/.ivy2/local/

FROM almondsh/almond:coursier as local_ivy_no

FROM local_ivy_${LOCAL_IVY}
ARG ALMOND_VERSION
# Set to a single Scala version string or list of Scala versions separated by a space.
# i.e SCALA_VERSIONS="2.11.12 2.12.8"
ARG SCALA_VERSIONS
USER $NB_UID
RUN [ -z "$SCALA_VERSIONS" ] && echo "SCALA_VERSIONS is empty" && exit 1; \
    [ -z "$ALMOND_VERSION" ] && echo "ALMOND_VERSION is empty" && exit 1; \
    for SCALA_FULL_VERSION in ${SCALA_VERSIONS}; do \
        # remove patch version
        SCALA_MAJOR_VERSION=${SCALA_FULL_VERSION%.*}; \
        # remove all dots for the kernel id
        SCALA_MAJOR_VERSION_TRIMMED=$(echo ${SCALA_MAJOR_VERSION} | tr -d .); \
        echo Installing almond ${ALMOND_VERSION} for Scala ${SCALA_FULL_VERSION}; \
        coursier bootstrap \
            -r jitpack \
            -i user -I user:sh.almond:scala-kernel-api_${SCALA_FULL_VERSION}:${ALMOND_VERSION} \
            sh.almond:scala-kernel_${SCALA_FULL_VERSION}:${ALMOND_VERSION} \
            --default=true --sources \
            -o almond && \
        ./almond --install --log info --metabrowse --id scala${SCALA_MAJOR_VERSION_TRIMMED} --display-name "Scala ${SCALA_MAJOR_VERSION}" && \
        rm -f almond; \
    done; \
    rm -rf .ivy2
