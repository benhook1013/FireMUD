# Shared base image for FireMUD services
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
