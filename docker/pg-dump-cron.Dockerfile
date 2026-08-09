FROM postgres:18@sha256:d129b9577d274bb96cbd44d902bdeb1b935c89247d161241e9154cba64e13df4
USER root
RUN apt-get update -y \
  && apt-get install -y --no-install-recommends cron \
  && rm -rf /var/lib/apt/lists/*
COPY dev-tools/backups/pg-dump-rotate.sh /usr/local/bin/pg-dump-rotate.sh
RUN chmod +x /usr/local/bin/pg-dump-rotate.sh
COPY docker/pg-dump-cron.crontab /etc/cron.d/pg-dump
RUN chmod 0644 /etc/cron.d/pg-dump && crontab /etc/cron.d/pg-dump
CMD ["cron", "-f"]
