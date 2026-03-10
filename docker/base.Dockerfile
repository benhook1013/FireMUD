# Shared base image for FireMUD services
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN groupadd --system --gid 1000 firemud \
    && useradd --system --uid 1000 --gid 1000 --create-home firemud
WORKDIR /app
USER firemud
