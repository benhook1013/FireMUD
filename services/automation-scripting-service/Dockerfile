ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:0c9e1442f0196e0b1cf9822e181152ff8106221df1263842a31269180c4d2c5a
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
