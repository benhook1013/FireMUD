ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:d531d90a481a71f49eef3e7dfe270d9327583d25b26cefda63e37bdc95cfa0e9
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
