ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:a1bc2aa0180cea75cc6ac4cfd05da9279e138f0b7c31d1fd65f15d2eb127958e
FROM ${BASE_IMAGE}
WORKDIR /app
COPY *.jar app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
