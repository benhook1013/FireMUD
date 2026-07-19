# ADR 0022: Account Authority and Gameplay Session Ownership

## Status

Accepted

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AA-1.3` Authentication, recovery, security policy, and account data rights
- Affected capabilities: `AA-1.2`, `AA-1.5`, `AA-2.3`, `SF-1.3`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-03`

## Context

FireMUD needs one unambiguous authority for durable account security state while allowing Game Session to own the short-lived gameplay state it alone can interpret. Gateway must protect the edge without becoming a second account, membership, or gameplay-policy authority.

The architecture already describes this split and the admission path substantially follows it. Immediate revocation, token rotation, and membership-version proof remain incomplete in the implementation. Those gaps do not justify moving authority to whichever component currently has the most convenient local data.

## Decision

### Account Service Authority

Account Service owns:

- global account identity, lifecycle, credentials, recovery, and configured authentication modes;
- token issuance profiles, signing keys, JWKS publication, the issued-token registry, and account/tenant/membership revocation authority;
- account-to-tenant membership, tenant roles, gameplay-admission eligibility, realm-access grants, and runtime entitlement truth; and
- monotonic membership and entitlement versions used by consumers to reject stale authority.

Account Service owns durable issuer/account/tenant/membership auth generations and is the sole writer of `session:auth:generation:*` projections. Other services request revocation through owned APIs or events and reconcile against Account Service rather than creating local competing authority. [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) replaces timestamp watermark ordering.

### Game Session Authority

Game Session owns:

- the protocol `LOGIN` and `PLAY` state transitions;
- the active `{tenantId, gameInstanceId, characterId}` gameplay binding and its bounded Redis indexes;
- reconnect, takeover, and gameplay-session lifecycle state; and
- typed unsigned player execution context for downstream gameplay calls under the trusted workload contract in [ADR 0024](./adr-0024-trusted-gameplay-workload-delegation.md).

Game Session consumes authoritative Account membership, entitlement, and revocation state. It stores only the token identity and freshness metadata needed to validate a binding, such as token hash, issued-at time, and `membershipVersion`; it must not make a persisted raw backend JWT the durable session authority.

### Gateway Boundary

Gateway is not a general identity, membership, entitlement, or gameplay-binding authority. It may perform only the authentication work owned by its bounded edge surfaces:

- validate and replay-protect short-lived gameplay connect tokens, then sign the internal connect context;
- require or validate credentials for Gateway-owned management routes according to their explicit route class; and
- strip untrusted identity/scope headers before forwarding.

These checks do not allow Gateway to issue account authority, infer membership, or bind a player to gameplay. Internal gameplay services authenticate the concrete mTLS caller, enforce the method allowlist, and validate player execution context and domain scope.

### Failure and Freshness Rules

- Admission and sensitive mutations fail closed when authoritative membership, entitlement, token-profile, issued-token registry, or applicable auth-generation state cannot be established.
- `membershipVersion` advances on every membership or role change that can alter gameplay or tenant authority; a database row identifier that does not advance is not a valid version.
- Ongoing sessions must consume revocation and membership changes through bounded indexes/events plus authoritative reconciliation; JWT expiry alone is insufficient for immediate revocation.
- Design acceptance and implementation status remain separate. Missing auth-generation enforcement, raw-JWT persistence, or non-monotonic versions are recorded implementation gaps, not alternate authority.

## Consequences

- Each security fact has one writer and one reconciliation source, reducing split-brain authorization.
- Game Session can manage low-latency gameplay lifecycle without owning credentials or durable tenant roles.
- Gateway remains replaceable and cannot silently grant access from edge-local state.
- Account Service is a critical dependency and concentrated security boundary. It requires high availability, recovery procedures, key protection, auditability, and fail-closed consumer behavior.
- Revocation propagation and versioned reconciliation add Redis, event, integration-test, and operational overhead.

## Alternatives Considered

### Dedicated IAM and Token Service

A dedicated IAM service could own signing, token registry, auth generations, and validation policy while Account retained profile and billing state. This narrows Account's security surface but adds a critical service and consistency boundaries between credentials, membership, billing, and revocation. FireMUD will not introduce it before a demonstrated scaling, isolation, or security-ownership need.

### Gateway-Owned Identity and Authorization

Centralizing identity at Gateway simplifies edge routing but cannot safely authorize internal calls or own gameplay lifecycle without turning the edge into a broad policy and domain-data authority.

### TTL-Only Tokens

Relying only on short token expiry removes registry and auth-generation checks but cannot provide the required immediate account, tenant, or membership revocation behavior.

## Implementation and Proof Obligations

- Enforce strict token profile, audience, issued-token registry, and applicable auth-generation checks in shared middleware rather than signature-only parsing.
- Implement all Account-owned durable generation writers/projections and prove logout-all, account, tenant, membership, and signing-key compromise revocation paths.
- Replace persisted raw gameplay JWTs with token hash/issued-at identity and freshly rebound backend credentials.
- Make `membershipVersion` and entitlement version/sequence monotonic state-change values rather than row identifiers.
- Prove `PLAY`, reconnect/resume, role changes, membership removal, and billing cutoff consume current Account authority and fail closed when it is unavailable.
- Prove Gateway cannot create account or gameplay authority and that untrusted identity/scope headers cannot reach internal services as trusted context.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/architecture/system-architecture-gateway.md`
