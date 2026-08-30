ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:7aa4be1ee4ab1d4abccb4a961e7c42b7e789a9c4a27f0293c7e262e71d88bd87
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
