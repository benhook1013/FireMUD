# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM velero/velero:v1.18.4@sha256:c89fb5b6d1fd6afd368e0f483e6f5555fd62851a1eada8ac5c72482e674ca17b AS velero-cli

FROM docker.io/amazon/aws-cli:2.37.9@sha256:92de75724b6a746951f0e8b915d86bbccd7cb55aff96cd0cb4f7017272160780

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
