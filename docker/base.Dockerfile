# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.4_7-jre@sha256:131166eb43967b8496fbb63bb430151a5de9ef5ece383b4aebe6c121da72cc74
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
