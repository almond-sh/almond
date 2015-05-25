FROM jupyter_scala/base
MAINTAINER Alexandre Archambault <alexandre.archambault@gmail.com>

ENV SCALA211_VERSION 2.11.6
ENV SCALA210_VERSION 2.10.5
ENV JUPYTER_SCALA_VERSION 0.2.0-SNAPSHOT

RUN curl -s https://oss.sonatype.org/content/repositories/snapshots/com/github/alexarchambault/jupyter/jupyter-scala-cli_${SCALA211_VERSION}/${JUPYTER_SCALA_VERSION}/jupyter-scala_${SCALA211_VERSION}-${JUPYTER_SCALA_VERSION}.tar.xz | tar -Jx -C /opt/
RUN curl -s https://oss.sonatype.org/content/repositories/snapshots/com/github/alexarchambault/jupyter/jupyter-scala-cli_${SCALA210_VERSION}/${JUPYTER_SCALA_VERSION}/jupyter-scala_${SCALA210_VERSION}-${JUPYTER_SCALA_VERSION}.tar.xz | tar -Jx -C /opt/

RUN /opt/jupyter-scala_${SCALA211_VERSION}-${JUPYTER_SCALA_VERSION}/bin/jupyter-scala
RUN /opt/jupyter-scala_${SCALA210_VERSION}-${JUPYTER_SCALA_VERSION}/bin/jupyter-scala


CMD ["/notebook.sh"]
