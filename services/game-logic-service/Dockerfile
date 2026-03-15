ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest
FROM ${BASE_IMAGE}
WORKDIR /app
COPY services/game-logic-service/build/libs/*.jar app.jar
COPY --chmod=755 dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY --chmod=755 docker/start-service.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
