# Shared base image for FireMUD services
FROM eclipse-temurin:25.0.3_9-jre@sha256:6e9581a150f9ad80d9154f6c9dc4e5df0d4f5eb545e788340e2271e2fb5d3870
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system firemud \
    && useradd --system --gid firemud --create-home firemud
WORKDIR /app
USER firemud
