# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.4_7-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
