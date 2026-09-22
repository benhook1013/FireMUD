# syntax=docker/dockerfile:1@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32

FROM velero/velero:v1.18.3@sha256:b839e52bc2c69eb3b5a84b010b8b3c7f714f3c5ef50b77ec7770a382a8f2e0ab AS velero-cli

FROM public.ecr.aws/aws-cli/aws-cli:2.37.0@sha256:337494c2047176fe9abcf45a5d1eaf1c2c62cae40953284fb1143b5c6170f065

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
