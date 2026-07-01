ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:32485cd7ae73f79b7ac2ef8039bf343356b59327e78feaea6329fe18a12d8196
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
