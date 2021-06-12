FROM node:16.3-alpine


RUN apk update
RUN apk add libtool automake autoconf gcc g++ make gawk nasm --no-cache

WORKDIR /app/website

COPY ./pages /app/pages
COPY ./website /app/website
RUN yarn install

