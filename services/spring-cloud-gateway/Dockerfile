ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:15567f9481c5ee462d53f73dc2d3ce98a28d453347c677482b8f068b6d86ccda
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
