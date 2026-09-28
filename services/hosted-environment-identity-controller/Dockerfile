ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:630e27c71296cfdbebdc35cc173ad5b18f4fb4e23f5b6df024de4ebeb1f3cffc
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
