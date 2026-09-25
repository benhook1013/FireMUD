# Build with web-client as the Docker context because the repository-root
# .dockerignore deliberately excludes frontend source from service images.
FROM node:24.21.0-alpine AS build

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Type checking runs in the frontend CI lane; the Docker context intentionally
# stays self-contained and does not include the shared TypeScript config.
RUN npm exec -- vite build

FROM ghcr.io/nginx/nginx-unprivileged:1.30.5-alpine3.24@sha256:daa17b944bac2b578e962da4c61ad72a59233b3c63abea17113acaf4e6b9aea4

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/

USER 101
EXPOSE 8080
