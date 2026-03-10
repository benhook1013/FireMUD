# Shared base image for FireMUD services
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/benhook1013/FireMUD"
RUN addgroup --system --gid 1000 firemud \
    && adduser --system --uid 1000 --ingroup firemud firemud
WORKDIR /app
USER firemud
