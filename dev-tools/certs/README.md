# Development Certificates

This folder tracks the helper scripts that generate local development TLS material and ignores the generated certificate outputs themselves.

Tracked files:

- `generate-dev-certs.sh`
- `clean-dev-certs.sh`

Ignored generated outputs include files such as:

- `ca.crt`, `ca.key`
- `client.crt`, `client.key`
- `server.crt`, `server.key`
- `dev-ca.pem`, `dev-cert.pem`, `dev-key.pem`

Default behavior:

- `dev-tools/certs/generate-dev-certs.sh` writes into `dev-tools/certs/` when no explicit target is provided.
- `dev-tools/certs/clean-dev-certs.sh` removes generated files from `dev-tools/certs/` unless `CERT_DIR` is overridden.

Do not commit generated certificate material from this directory.
