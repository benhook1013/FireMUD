ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:8b118fcc94cef90c3e437042d53fbae542d280963137d6a88857dd9b4543fde3
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
