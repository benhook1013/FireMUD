ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:4e39fb9f94e68844a897ebcb1397da1c1ee89c6cfcf43f7385a002d8744afaa2
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
