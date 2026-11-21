# syntax=docker/dockerfile:1@sha256:572cebbc2b15e6f84c077742952674889cb63cd5e9b80abdffcbe587b6f19183

FROM velero/velero:v1.18.4@sha256:c89fb5b6d1fd6afd368e0f483e6f5555fd62851a1eada8ac5c72482e674ca17b AS velero-cli

FROM docker.io/amazon/aws-cli:2.42.1@sha256:b0102d2269f87d8531d931c1a59e248101ace586886e6c077058d94c1c37b846

USER root

ENV HOME=/tmp \
    AWS_PAGER="" \
    AWS_CLI_AUTO_PROMPT=off

WORKDIR /opt/firemud/backups

COPY --from=velero-cli /velero /usr/local/bin/velero
COPY dev-tools/backups/verify-backups.sh /opt/firemud/backups/verify-backups.sh
COPY dev-tools/backups/pg-dump-s3-selection.shlib /opt/firemud/backups/pg-dump-s3-selection.shlib

RUN chmod 0755 /usr/local/bin/velero /opt/firemud/backups/verify-backups.sh \
    && chmod 0644 /opt/firemud/backups/pg-dump-s3-selection.shlib

# The verifier has no write requirement. A numeric UID keeps the image usable
# with restricted Kubernetes security contexts without relying on /etc/passwd.
USER 65532:65532

ENTRYPOINT ["/bin/bash", "/opt/firemud/backups/verify-backups.sh"]
