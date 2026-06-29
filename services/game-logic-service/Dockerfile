ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:dbb1ed94142ea67e39cd6f17b8ca811d2b8fcfd5a4c4c9eef1877eb1dae06db0
FROM ${BASE_IMAGE}
WORKDIR /app
COPY *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
