# FireMUD Operator Credentials Runbook

This runbook describes how operators obtain, rotate, and revoke the credentials used to access FireMUD’s internal-only control-plane surfaces (for example diagnostic endpoints and management APIs protected by mTLS).

This document is intentionally narrow and operational. For the architecture and trust model, see `design/architecture/system-architecture-security.md`.

## Operator Client Certificates (mTLS)

Operator access to internal-only endpoints relies on mTLS client certificates.

### Issuance

- Operator certificates must include `clientAuth` extended key usage.
- Operator certificates should be issued by cert-manager using a dedicated issuer and profile that is separate from workload identities.
- Operator certificates must chain to the CA bundle that internal services trust for operator endpoints (see `FIREMUD_GRPC_CA_CERT_PATH` expectations in `system-architecture-security.md`).

### Storage and Distribution

- Store the operator certificate and private key in a dedicated Kubernetes Secret that is not mounted by normal workloads.
- Restrict which service accounts can read that Secret using Kubernetes RBAC.
- Restrict which pods can reach operator endpoints using NetworkPolicies; possession of a valid certificate must not be sufficient outside approved network placement.
- For workstation use, export the credential from the Secret and store it in a secure operator-only location (password manager, encrypted vault, or OS keychain). Do not commit exported keys to the repository.

### Rotation

Rotate operator certificates:

- On a fixed cadence, and
- Immediately after personnel changes, suspected device compromise, or any incident involving operator surfaces.

Rotation steps:

1. Issue a new operator client certificate and write it into the operator credential Secret.
2. Distribute the new credential to approved operator tools/workstations.
3. Remove/revoke the previous credential by removing it from the allowed trust path (or by rotating the operator issuer/CA, depending on how issuance is configured).
4. Verify access from an approved operator surface and verify that normal workloads cannot reach operator endpoints.

### Revocation / Incident Response

If an operator credential is suspected compromised:

- Rotate immediately (issue new cert, distribute, revoke old).
- Tighten NetworkPolicies/allowlists so that access is limited to the smallest possible operator blast radius during the response window.
- Record which credential was rotated and why in the incident record.

## Related Documentation

- `design/architecture/system-architecture-security.md`
- [Operations documentation](../operations/README.md)
