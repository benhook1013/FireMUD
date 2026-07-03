# Development Certificates

This directory contains the helper scripts for local development TLS material.

Generated certificate outputs in this directory are ignored by Git and should not be committed.

## Script Map

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/ensure-dev-certs.sh` guarantees the canonical local smoke entrypoints have the required cert/key set in place before Compose boots.
- `dev-tools/certs/clean-dev-certs.sh` removes generated certificate files from `dev-tools/certs/` unless a target directory or `CERT_DIR` override is provided.
