ARG BASE_IMAGE=ghcr.io/benhook1013/firemud-base:latest@sha256:a7309b5c6c785ea9cf220faa672080cf386a414e458fc8a544b882a00e57f7ec
FROM ${BASE_IMAGE}

WORKDIR /opt/firemud
COPY --chown=0:0 services/game-session-service/build/distributions/game-session-migration-driver/ /opt/firemud/game-session-migration-driver/
RUN chmod -R a+rX /opt/firemud/game-session-migration-driver \
    && chmod -R a-w /opt/firemud/game-session-migration-driver \
    && chmod a+x /opt/firemud/game-session-migration-driver/bin/game-session-migration-driver

USER 999:999
ENTRYPOINT ["/opt/firemud/game-session-migration-driver/bin/game-session-migration-driver"]
