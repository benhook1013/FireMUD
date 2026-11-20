# Build from the repository root: docker build -f web-client/Dockerfile .
# Dockerfile.dockerignore admits only frontend inputs and their shared TS config.
FROM node:24.21.0-alpine@sha256:ebfe2f90462722a7a4de65e91990e97fe0d401c70e0e762c5b53302f905ec1c1 AS build

WORKDIR /app/web-client
COPY web-client/package.json web-client/package-lock.json ./
RUN npm ci
COPY config/ts/tsconfig.base.json /app/config/ts/tsconfig.base.json
COPY web-client/ ./
# Vite also resolves the shared TS config; type checking runs in the frontend CI lane.
RUN npm exec -- vite build

FROM ghcr.io/nginx/nginx-unprivileged:1.30.5-alpine3.24@sha256:daa17b944bac2b578e962da4c61ad72a59233b3c63abea17113acaf4e6b9aea4

COPY web-client/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/web-client/dist/ /usr/share/nginx/html/

USER 101
EXPOSE 8080
