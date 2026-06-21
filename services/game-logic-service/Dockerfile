ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:749613762dc7a1c688449f0672b71f6bbe2b680ea361022c40c9040ea7f4c25b
FROM ${BASE_IMAGE}
WORKDIR /app
COPY *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
