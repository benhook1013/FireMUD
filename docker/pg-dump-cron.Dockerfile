FROM postgres:18@sha256:4aabea78cf39b90e834caf3af7d602a18565f6fe2508705c8d01aa63245c2e20
USER root
RUN apt-get update -y \
  && apt-get install -y --no-install-recommends cron \
  && rm -rf /var/lib/apt/lists/*
COPY dev-tools/backups/pg-dump-rotate.sh /usr/local/bin/pg-dump-rotate.sh
RUN chmod +x /usr/local/bin/pg-dump-rotate.sh
COPY docker/pg-dump-cron.crontab /etc/cron.d/pg-dump
RUN chmod 0644 /etc/cron.d/pg-dump && crontab /etc/cron.d/pg-dump
CMD ["cron", "-f"]
