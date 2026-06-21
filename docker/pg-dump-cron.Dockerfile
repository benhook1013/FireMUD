FROM postgres:16@sha256:081f1bc7bd5e143dbb6e487b710bbc27712cdcfaced4c071b8e47349aa1b4171
USER root
RUN apt-get update -y \
  && apt-get install -y --no-install-recommends cron \
  && rm -rf /var/lib/apt/lists/*
COPY dev-tools/backups/pg-dump-rotate.sh /usr/local/bin/pg-dump-rotate.sh
RUN chmod +x /usr/local/bin/pg-dump-rotate.sh
COPY docker/pg-dump-cron.crontab /etc/cron.d/pg-dump
RUN chmod 0644 /etc/cron.d/pg-dump && crontab /etc/cron.d/pg-dump
CMD ["cron", "-f"]
