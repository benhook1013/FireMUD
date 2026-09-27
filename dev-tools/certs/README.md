# Development Certificates

This directory contains the helper scripts for local development TLS material.

Generated certificate outputs in this directory are ignored by Git and should not be committed.

## Script Map

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/generate-dev-certs.sh --workload` signs a local development or test-only leaf with the exact SPIFFE URI and service DNS SANs. The generic generator keeps workload private keys mode `0600`; `ensure-dev-certs.sh` changes permissions only on its ignored local Compose runtime projection so non-root containers can read their own local keys. Hosted preview and dev-demo do not use this signing mode for runtime issuance; their standalone workload leaves come from the protected `firemud-ca-issuer` through the scoped certificate writer.
- Standalone hosted runs retain the legacy shared bundle for non-publication workloads and consume only cert-manager's five runtime-facing `firemud-grpc-<workload>` leaf Secrets. They do not create or retain a CA private key in a runtime namespace; a missing or invalid issuer-projected leaf fails closed.
- `dev-tools/certs/ensure-dev-certs.sh` guarantees the canonical local Compose entrypoints have the shared cert/key set and distinct Account, Game Session, and Social Groups workload identities before Compose boots. It validates the local CA, URI SAN, DNS SAN, and key pair, then creates `dev-tools/certs/local-runtime/` with only runtime-facing certificate material. The projection is ignored by Git and never contains `ca.key`; the host-side CA private key remains mode `0600`.
- `dev-tools/certs/clean-dev-certs.sh` removes only its listed top-level files and the known workload-leaf/runtime-projection filenames, then removes those directories only when empty. Unexpected files and symlinks are preserved and reported; cleanup does not recurse destructively.
