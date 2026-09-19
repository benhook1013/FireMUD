# Development Certificates

This directory contains the helper scripts for local development TLS material.

Generated certificate outputs in this directory are ignored by Git and should not be committed.

## Script Map

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/generate-dev-certs.sh --workload` signs one distinct gRPC publication leaf from the stable standalone CA, with the exact SPIFFE URI SAN and service DNS SANs required by the protected readers. The hosted helper invokes this mode only when a projected leaf Secret is absent.
- Standalone hosted runs retain each generated source leaf as `<runtime>-grpc-<workload>` and project it to the runtime-facing `firemud-grpc-<workload>` Secret; a missing source or projection fails closed rather than minting replacement material over an existing identity.
- `dev-tools/certs/ensure-dev-certs.sh` guarantees the canonical local smoke entrypoints have the required cert/key set in place before Compose boots.
- `dev-tools/certs/clean-dev-certs.sh` removes generated certificate files from `dev-tools/certs/` unless a target directory or `CERT_DIR` override is provided.
