ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:52c3987ea2e607683807b8fb708b408479d45ad43eccbea3dfddacab191d5dd5
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 hosted-environment-identity-controller.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
