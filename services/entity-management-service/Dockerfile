ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:56f2800b1548e0844b63477fec3fb4e044e19bbfc35aed531c8640b62cf1d295
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
