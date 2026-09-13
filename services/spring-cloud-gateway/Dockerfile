ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:16d64ee3e02506a4b11a03856f89d9223c18b695520169faa5a83caab4d0ac4d
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
