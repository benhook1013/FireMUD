# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

FROM velero/velero:v1.18.3@sha256:b839e52bc2c69eb3b5a84b010b8b3c7f714f3c5ef50b77ec7770a382a8f2e0ab AS velero-cli

FROM docker.io/amazon/aws-cli:2.37.6@sha256:82905aa8fdab6e2403b40dceec12dc33aea2535a4c2a6f13d596d98501951105

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
