# ADR 0010: TCP Proxy Identity Canonicalization for Gateway Header Trust

## Status

Superseded by [ADR 0169](./adr-0169-exclusive-environment-bound-tcp-proxy-trust.md)

## Context

Gateway header trust depends on authenticating the TCP Proxy identity on the internal mTLS WebSocket hop. The architecture documented URI SAN, DNS SAN, and fingerprint allowlist modes, but lacked a single normative decision that defined production canonical identity and fallback boundaries.

Without that decision, environments can drift toward inconsistent trust posture and operationally expensive certificate pinning.

## Decision

The canonical production identity for TCP Proxy on the Gateway mTLS listener is URI SAN (SPIFFE-style):

- Expected format: `spiffe://firemud/ns/<namespace>/sa/tcp-proxy-service`
- Matching is exact against configured allowlist entries.
- Certificates are issued by cert-manager under the platform trust root (`firemud-ca-issuer`), with client-auth usage.

Fallback modes are constrained:

- DNS SAN allowlisting is transitional only for migration windows where URI SAN issuance is not yet fully available.
- SHA-256 fingerprint allowlisting is break-glass only for incidents and is not an accepted steady-state production mode.

Gateway trust evaluation order is:

- Validate certificate chain and client-auth constraints.
- Evaluate URI SAN allowlist.
- If URI SAN mismatch and transitional DNS mode is enabled, evaluate DNS SAN allowlist.
- If both SAN modes fail and break-glass is explicitly enabled, evaluate fingerprint allowlist.
- If no mode matches, reject handshake and do not promote `X-Proxy-*` headers.

## Consequences

- Production onboarding and cert rotation procedures must provision URI SAN identities for TCP Proxy.
- DNS SAN and fingerprint modes require explicit operational justification and should be tracked to closure.
- Header trust behavior is consistent across environments and easier to audit.

## References

- `design/architecture/system-architecture-gateway.md`
- `design/architecture/microservices/spring-cloud-gateway/README.md`
- `design/architecture/microservices/tcp-proxy-service/README.md`
- `design/architecture/system-architecture-security.md`
