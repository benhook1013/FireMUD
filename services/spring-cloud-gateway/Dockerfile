ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:52c3987ea2e607683807b8fb708b408479d45ad43eccbea3dfddacab191d5dd5
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
