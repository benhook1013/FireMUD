FROM postgres:18@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42
USER root
RUN apt-get update -y \
  && apt-get install -y --no-install-recommends cron \
  && rm -rf /var/lib/apt/lists/*
COPY dev-tools/backups/pg-dump-rotate.sh /usr/local/bin/pg-dump-rotate.sh
RUN chmod +x /usr/local/bin/pg-dump-rotate.sh
COPY docker/pg-dump-cron.crontab /etc/cron.d/pg-dump
RUN chmod 0644 /etc/cron.d/pg-dump && crontab /etc/cron.d/pg-dump
CMD ["cron", "-f"]
