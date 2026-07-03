ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:0f13d297c1ed16d1bb8b17f452e56cbafc67e9f37b9c8c61cff73c0b35ac82db
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
