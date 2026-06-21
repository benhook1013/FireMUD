ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:a1bc2aa0180cea75cc6ac4cfd05da9279e138f0b7c31d1fd65f15d2eb127958e
FROM ${BASE_IMAGE}
USER root
WORKDIR /app
COPY *.jar /app/
RUN set -e; \
    jar="$(find /app -maxdepth 1 -type f -name 'game-session-service-*.jar' ! -name '*-plain.jar' ! -name '*-test-fixtures.jar' | head -n 1)"; \
    test -n "${jar}"; \
    mv "${jar}" /app/app.jar; \
    find /app -maxdepth 1 -type f -name '*.jar' ! -name 'app.jar' -delete
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
