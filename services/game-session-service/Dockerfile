ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:8b118fcc94cef90c3e437042d53fbae542d280963137d6a88857dd9b4543fde3
FROM ${BASE_IMAGE}
USER root
WORKDIR /app
COPY --chmod=644 *.jar /app/
RUN set -e; \
    jar="$(find /app -maxdepth 1 -type f -name 'game-session-service-*.jar' ! -name '*-plain.jar' ! -name '*-test-fixtures.jar' | head -n 1)"; \
    test -n "${jar}"; \
    mv "${jar}" /app/app.jar; \
    find /app -maxdepth 1 -type f -name '*.jar' ! -name 'app.jar' -delete; \
    chown firemud:firemud /app/app.jar; \
    chmod 644 /app/app.jar
COPY --from=repo_root --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --from=repo_root --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
USER firemud
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
