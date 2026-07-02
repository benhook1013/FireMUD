# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.3_9-jre@sha256:d0eb1b9018b3044da1b7346f39e945f71095749853d69a3aa16b8c99dad9bb45
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
