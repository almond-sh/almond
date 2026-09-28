# Authenticated HTTP proxy the proxy tests put between Almond and Maven Central: an Apache httpd
# forward proxy, that only lets requests through with the credentials of proxy.conf.
#
# Adapted from https://github.com/bahamat/docker-authenticated-proxy, like the proxy of the
# coursier tests.
FROM alpine:3.21.2

RUN apk add --no-cache apache2 apache2-proxy apache2-utils && \
    mkdir -p /run/apache2 && \
    htpasswd -bc /etc/apache2/htpasswd jack insecure
COPY proxy.conf /etc/apache2/conf.d/zz-almond-proxy.conf

EXPOSE 80
CMD ["httpd", "-D", "FOREGROUND"]
