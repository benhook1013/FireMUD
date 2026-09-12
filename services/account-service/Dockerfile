ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:dba57ca6c760042b6aa5024b68b0df16a918e8dd985ef5b4423acc58b5705e83
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
