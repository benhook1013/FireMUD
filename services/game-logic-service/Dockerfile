ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:69fabb648faeb95d99a4602e5b913f6a3986f3797f478211a63a641b76df75fb
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
