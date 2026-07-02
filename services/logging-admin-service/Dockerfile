ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:d559a36cc65180d63c1625d435c1ae03fb081717e3e926c3e900e90c7adebf12
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
