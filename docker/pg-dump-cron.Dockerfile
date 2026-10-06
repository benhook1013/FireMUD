FROM postgres:18@sha256:fc973eb97c9fd04bfa1840e0f510719a584ccb3be8debfe6a4144637a9dfe8cf
USER root
RUN apt-get update -y \
  && apt-get install -y --no-install-recommends cron \
  && rm -rf /var/lib/apt/lists/*
COPY dev-tools/backups/pg-dump-rotate.sh /usr/local/bin/pg-dump-rotate.sh
RUN chmod +x /usr/local/bin/pg-dump-rotate.sh
COPY docker/pg-dump-cron.crontab /etc/cron.d/pg-dump
RUN chmod 0644 /etc/cron.d/pg-dump && crontab /etc/cron.d/pg-dump
CMD ["cron", "-f"]
