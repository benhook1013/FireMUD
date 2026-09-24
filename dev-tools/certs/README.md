# Development Certificates

This directory contains the helper scripts for local development TLS material.

Generated certificate outputs in this directory are ignored by Git and should not be committed.

## Script Map

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/generate-dev-certs.sh --workload` signs a local development or test-only publication leaf with the exact SPIFFE URI and service DNS SANs. Hosted preview and dev-demo do not use this signing mode; their standalone workload leaves come from the protected `firemud-ca-issuer` through the scoped certificate writer.
- Standalone hosted runs retain the legacy shared bundle for non-publication workloads and consume only cert-manager's five runtime-facing `firemud-grpc-<workload>` leaf Secrets. They do not create or retain a CA private key in a runtime namespace; a missing or invalid issuer-projected leaf fails closed.
- `dev-tools/certs/ensure-dev-certs.sh` guarantees the canonical local smoke entrypoints have the required cert/key set in place before Compose boots.
- `dev-tools/certs/clean-dev-certs.sh` removes generated certificate files from `dev-tools/certs/` unless a target directory or `CERT_DIR` override is provided.
