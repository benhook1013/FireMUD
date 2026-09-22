ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:f0d6a0b499ee730f414a11d2b2c4a5b03b30cfe3450eed229945da2a7cbfc39a
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
