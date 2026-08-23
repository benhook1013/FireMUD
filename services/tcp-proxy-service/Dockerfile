ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:64a20240e098e4cd9ba4d72acb7a111e2c3d6242ae81898b4618ae7bf02c5235
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
