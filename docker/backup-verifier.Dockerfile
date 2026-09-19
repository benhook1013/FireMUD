# syntax=docker/dockerfile:1

FROM velero/velero:v1.18.2@sha256:37396519f399536e5f01427d723565ae69294ec3fb5625cf1c87c09eaa9de16b AS velero-cli

FROM public.ecr.aws/aws-cli/aws-cli:2.36.49@sha256:f42bf088cb1456ba9e179ce71fdeb22cc46ff64ea1e3aeae8251ff81391f5bb1

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
