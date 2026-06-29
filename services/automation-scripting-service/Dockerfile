ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:b915fe6cd7bde7ca8f649b7b40b70e9673fddae01be109414fb3c41c9e75a8d3
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
