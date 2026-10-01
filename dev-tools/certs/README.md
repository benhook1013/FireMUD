# Development Certificates

This directory contains the helper scripts for local development TLS material.

Generated certificate outputs in this directory are ignored by Git and should not be committed.

## Script Map

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/generate-dev-certs.sh --workload` signs local development or test-only gRPC workload leaves for the five publication services, Account, Game Session, and Social Groups with the exact SPIFFE URI and service DNS SANs. The Account and Game Session leaves support the canonical local Compose gameplay proof, while the remaining local Compose services may use the shared development leaf. Hosted preview and dev-demo do not use this signing mode; their standalone workload leaves come from the protected `firemud-ca-issuer` through the scoped certificate writer.
- Standalone hosted runs retain the legacy shared bundle for other gRPC workloads and consume eight cert-manager-issued runtime-facing `firemud-grpc-<workload>` leaf Secrets: five publication workloads, Account, Game Session, and Social Groups. They do not create or retain a CA private key in a runtime namespace; a missing or invalid issuer-projected leaf fails closed.
- `dev-tools/certs/ensure-dev-certs.sh` guarantees the canonical local smoke entrypoints have the required cert/key set in place before Compose boots.
- `dev-tools/certs/ensure-dev-certs.sh --compose-mtls <run-owned-fixture-root>` creates the dedicated canonical smoke fixture beneath the active run-owned Compose state directory. The authority and CA private key remain in a host-only mode-0700 directory; each of the eleven Java application containers receives a read-only `/app/certs` mount containing only that service's leaf key, certificate, and public CA certificate. Account, Game Session, and Entity Management receive distinct `dev`-namespace SPIFFE leaves; the remaining services use the shared local development leaf. The fixture is intentionally retained while its Compose project may be restarted and should be removed only after that exact project is torn down.
- The three canonical runtime-proof entrypoints layer `docker/docker-compose.grpc-mtls.override.yml` last. Ordinary `docker/docker-compose.override.yml` remains the plaintext local debugging profile. Hosted certificate provisioning is separate: hosted runtime namespaces receive cert-manager-issued standalone workload leaves and never mount the local CA private key.
- `dev-tools/certs/clean-dev-certs.sh` removes generated certificate files from `dev-tools/certs/` unless a target directory or `CERT_DIR` override is provided.
