ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:fc8294149501079331955b554e9d513daa365ef57a38ac49cc4fb0cb3666d502
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
