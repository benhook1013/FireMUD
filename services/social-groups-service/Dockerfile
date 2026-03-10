ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest
FROM ${BASE_IMAGE}
WORKDIR /app
COPY services/social-groups-service/build/libs/*.jar app.jar
COPY dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY docker/start-service.sh /usr/local/bin/start-service.sh
RUN chmod +x /usr/local/bin/wait-for-it.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
