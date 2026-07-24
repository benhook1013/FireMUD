ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:b25b8980d6b681961d5f5dcdb1db1aec56aae5134978b07fa5e7d5e3106eb96a
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
