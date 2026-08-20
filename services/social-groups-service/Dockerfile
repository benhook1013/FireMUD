ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:f15a0044c59f9cdbfd29b060f507290a14ad8c53fda328a26533260dc215dbe7
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
