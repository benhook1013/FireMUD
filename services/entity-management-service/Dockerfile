ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:8b5034cfd1f05154593549b5b5bd1bb900ea91c3649994716c4e1a804a6ff7e2
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
