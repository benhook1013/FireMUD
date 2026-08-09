ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:e813a42bd6127f6ea5a70ea6c475355de6a7c17363f65c71bbe51c22f5cda75d
FROM ${BASE_IMAGE}
WORKDIR /app
COPY --chown=firemud:firemud --chmod=644 *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
