# Image the proxy / mirror tests install and run Almond in: a JDK, a cs launcher, and Jupyter, from
# the uv-managed environment the example notebooks are run with (examples/pyproject.toml and its
# lock file, that the tests copy in the build context).
#
# Everything the image needs is fetched while building it, from the regular network. The tests
# then run containers of this image on a network that can't reach the outside, so that the only
# way to Maven Central is the proxy or mirror under test.

ARG UV_VERSION
FROM ghcr.io/astral-sh/uv:${UV_VERSION} AS uv

FROM eclipse-temurin:21-jdk

ARG CS_VERSION

# A cs launcher: the tests install Almond with it, like the documentation says to, and the
# launchers it generates look it up in the PATH to read the Scala CLI configuration file
RUN set -eu; \
    case "$(uname -m)" in \
      x86_64) arch=x86_64 ;; \
      aarch64 | arm64) arch=aarch64 ;; \
      *) echo "Unsupported CPU architecture: $(uname -m)" >&2; exit 1 ;; \
    esac; \
    curl -fL "https://github.com/coursier/coursier/releases/download/v${CS_VERSION}/cs-${arch}-pc-linux.gz" \
      | gzip -d > /usr/local/bin/cs; \
    chmod +x /usr/local/bin/cs

# Jupyter, from the environment of examples/pyproject.toml, so that the tests run the notebooks
# the same way the example tests do.
COPY --from=uv /uv /usr/local/bin/uv
# uv puts the Python it downloads under the home directory by default, but the tests mount a
# directory of their own on /root, which would hide it, and break the virtual environment below
ENV UV_PYTHON_INSTALL_DIR=/opt/uv/python
COPY pyproject.toml uv.lock /opt/jupyter/
RUN cd /opt/jupyter && uv sync --frozen
ENV PATH="/opt/jupyter/.venv/bin:${PATH}"
