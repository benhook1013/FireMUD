ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:7112556c3f66634788f4c457cd6c78524953640c0696b10c62902bdd3fbea32c
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
