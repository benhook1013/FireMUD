ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:cb8962799e29b9414caf4a2a2d06b94f1efbe4073d9eb69bdf85edc5b3f9929d
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
