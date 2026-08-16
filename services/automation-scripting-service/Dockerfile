ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:0502ec31cbcf622bc7fe9934d4cee04e4dff3c923331980324ef4f1723c3be3b
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
