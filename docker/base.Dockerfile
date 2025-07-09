# Shared base image for FireMUD services
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.source="https://github.com/firedevops/FireMUD"
RUN addgroup --system firemud && adduser --system --ingroup firemud firemud
WORKDIR /app
USER firemud
