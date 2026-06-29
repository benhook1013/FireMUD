ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:32572af515e7bb7bdecf93ff4d257ae388e7829289b10b527dd3958d2231a37b
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
