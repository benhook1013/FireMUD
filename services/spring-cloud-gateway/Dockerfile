ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:4401a778d8713c21e212bf7c9b2842ce0c850244e63663a9a6dee424f425845b
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
