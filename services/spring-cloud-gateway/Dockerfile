FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
COPY ../../dev-tools/wait-for-it.sh /usr/local/bin/wait-for-it.sh
COPY ../../docker/start-service.sh /usr/local/bin/start-service.sh
RUN chmod +x /usr/local/bin/wait-for-it.sh /usr/local/bin/start-service.sh
ENTRYPOINT ["/usr/local/bin/start-service.sh"]
