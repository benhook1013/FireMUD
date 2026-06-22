ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:32572af515e7bb7bdecf93ff4d257ae388e7829289b10b527dd3958d2231a37b
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
