ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:630e27c71296cfdbebdc35cc173ad5b18f4fb4e23f5b6df024de4ebeb1f3cffc
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
