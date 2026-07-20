# ADR 0164: Exclusive Environment-Bound TCP Proxy Trust

## Status

Accepted

Supersedes [ADR 0010](./adr-0010-tcp-proxy-identity-canonicalization.md).

## Decision Record

- Decision date: 2026-07-21
- Decision key: `EDGE-03`
- Disposition: `revised`
- Primary capability: `SF-1.3` transport and workload trust
- Affected capabilities: `PO-2.1`, `PO-2.2`, `PO-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of bridge impersonation, environment isolation, certificate rotation, migration and break-glass operation, header spoofing, and current deployment reality

## Context

ADR 0010 selected URI SAN as the preferred production identity but defined DNS SAN and fingerprint as ordered fallbacks. That permits several trust authorities to be active at once and makes a weak or stale fallback silently widen acceptance. The hosted deployment currently uses plaintext `ws://` plus an insecure CIDR covering the pod network, while the nominal mTLS Service forwards to the same application port without establishing a demonstrated client-certificate listener. The application matcher also accepts fingerprint before SAN when several lists are populated. Documentation that calls the full player-facing boundary implemented is therefore inaccurate.

## Decision

Player-facing TCP Proxy traffic enters Gateway through a dedicated internal-only `wss://` listener that requires a client certificate. Gateway accepts the bridge only when the certificate:

- chains to the trust bundle or issuer explicitly assigned to that deployment environment;
- is valid for client authentication; and
- contains the exact allowlisted environment-specific URI SAN/SPIFFE workload identity for TCP Proxy.

Trust profiles are explicit and mutually exclusive:

- `production_uri` is the steady-state player-facing profile and accepts only the exact URI identity.
- `migration_dns` temporarily substitutes an exact DNS SAN allowlist. It requires an owner, reason, and expiry and cannot coexist with URI or fingerprint matching.
- `breakglass_fingerprint` temporarily accepts one explicitly pinned leaf fingerprint. It requires an incident reference and expiry and cannot coexist with either SAN mode.
- `development_cidr` permits insecure source-CIDR trust only in local development or isolated automated tests. Hosted, hobby/self-hosted player-facing, staging, and production profiles prohibit it.

Startup or admission fails closed when the selected profile is incomplete, expired, invalid for the environment, or accompanied by settings for another profile. There is no silent any-of or ordered fallback across identities.

Every public listener strips inbound `X-Proxy-*`, gateway-owned canonical identity headers, and `X-Firemud-*` admission headers before admission, rate-limit key derivation, or forwarding. Only the authenticated internal bridge path reconstructs canonical headers from the verified peer and validated proxy metadata. NetworkPolicy and internal Services remain defense in depth, not workload authentication.

## Consequences

- Compromise of an unrelated pod or certificate from another environment cannot impersonate TCP Proxy merely by reaching Gateway or presenting proxy headers.
- Certificate migration and break-glass operation require explicit expiring configuration and operational evidence.
- The application and deployment must expose real peer-certificate identity to the trust filter and prove listener separation; naming a plaintext Service `-mtls` is insufficient.
- Existing local CIDR-based test paths may remain, but environment validation must prevent their promotion into any player-facing profile.
- The current hosted values, Gateway listener wiring, trust-mode matcher, status documentation, and end-to-end proof require implementation convergence.

## Alternatives Considered

### Ordered URI, DNS, Then Fingerprint Fallback

This is operationally convenient during rotation, but a forgotten fallback silently expands the trust set. Explicit one-mode transitions are slightly more work and materially easier to audit.

### Network or CIDR Trust in Production

This is simple but grants bridge authority to every reachable workload in the trusted range. It is unsuitable for headers that bypass ordinary gameplay connect-token admission.

### Shared Certificate Authority Without Exact Workload Identity

Chain validation provides encryption and issuer trust but does not distinguish TCP Proxy from other certificate holders. It is rejected.

## Implementation and Proof Obligations

Implementation must provide a dedicated TLS listener, environment-scoped trust material and URI identities, exclusive profile validation, expiry enforcement, public-header stripping before consumers, and removal of hosted plaintext/CIDR bridge trust. Proof must cover wrong environment, wrong workload under the same CA, multiple configured profiles, expired migration and break-glass modes, public header spoofing, missing peer-certificate context, certificate rotation, and fail-closed startup/admission.

## Reversibility and Revisit Triggers

Identity syntax or certificate delivery may change without weakening the exclusive workload-identity invariant. Revisit only if the deployment platform supplies an equivalently strong authenticated workload identity to Gateway; document its exact binding and proof rather than adding another implicit fallback.

