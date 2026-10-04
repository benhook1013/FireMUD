ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:a7309b5c6c785ea9cf220faa672080cf386a414e458fc8a544b882a00e57f7ec
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
