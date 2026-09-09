ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:334cf732bd2fb41d9fc5e96e32c3eedbdb5aa59aeced3f1a62659cf68c4860fc
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
