# ADR 0022: Account Authority and Gameplay Session Ownership

## Status

Accepted

## Implementation Status

The authority split and admission path are substantially present, but complete issued-token registry issuance/validation, immediate revocation, token rotation, Account-owned authority generations, and monotonic membership-version proof remain incomplete. Accepted ownership is target authority; an implementation gap does not transfer authority to the component holding convenient local state.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AA-1.3` Authentication, recovery, security policy, and account data rights
- Affected capabilities: `AA-1.2`, `AA-1.5`, `AA-2.3`, `SF-1.3`, `SF-2.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-03`
- Human review status: Completed
- Human review date: 2026-07-18
- Human review disposition: Accepted
- Review source: `AUTH-03`

## Context

FireMUD needs one unambiguous authority for durable account security state while allowing Game Session to own the short-lived gameplay state it alone can interpret. Gateway must protect the edge without becoming a second account, membership, or gameplay-policy authority.

The architecture already describes this split. Its remaining gaps do not justify moving authority to whichever component currently has the most convenient local data.

## Decision

### Account Service Authority

Account Service owns:

- global account identity, lifecycle, credentials, recovery, and configured authentication modes;
- token issuance profiles, signing keys, JWKS publication, the issued-token registry, and issuer/account/tenant/membership authority-generation state;
- account-to-tenant membership, tenant roles, gameplay-admission eligibility, realm-access grants, and runtime entitlement truth; and
- monotonic membership and entitlement versions used by consumers to reject stale authority.

Account Service owns durable issuer/account/tenant and `{accountId, tenantId}` membership authority generations and is the sole writer of the canonical `session:auth:generation:*` authority projections. Other services request authority changes through owned APIs or events and reconcile against Account Service rather than creating local competing authority. [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) replaces timestamp watermark ordering. A downstream projection is not canonical authority merely because it uses the same generation value.

### Game Session Authority

Game Session owns:

- the protocol `LOGIN` and `PLAY` state transitions;
- the active `{tenantId, gameInstanceId, characterId}` gameplay binding and its bounded Redis indexes;
- reconnect, takeover, and gameplay-session lifecycle state; and
- typed unsigned player execution context for downstream gameplay calls under the trusted workload contract in [ADR 0024](./adr-0024-trusted-gameplay-workload-delegation.md).

Game Session consumes authoritative Account membership, entitlement, and revocation state. It stores only the token identity and freshness metadata needed to validate a binding, such as token hash, issued-at time, and `membershipVersion`; it must not make a persisted raw backend JWT the durable session authority.

For issuer-generation consumption only, Game Session may maintain one derived consumer-local projection under its own namespace: `session:game:auth:issuer-generation:v1:<issuerId>`. Its schema is `game-session-auth-issuer-projection/v1` with `{schemaVersion, issuerId, issuerAuthGeneration, sourceOutboxStreamKey, sourceOutboxSequence, sourceEventId, sourceEventDigest, appliedAt}`. The consumer applies Account events with set-if-greater semantics on `issuerAuthGeneration`; matching equal-generation checkpoints are idempotent, lower generations are no-ops, and conflicting or missing source evidence is quarantined for Account reconciliation. This local projection is a revocation-consumer cache, not an authority source, and Game Session must never write or mutate any canonical `session:auth:generation:*` key. Account's durable issuer generation and its canonical projection remain the only authority; Game Session owns only this derived consumer-local projection.

### Operation Partition

The issued-token registry is a credential-path check, not a universal gameplay middleware dependency. The canonical operation partition is:

| Operation | Credential presented | Required authority and evidence | Issued-token registry behavior |
| --- | --- | --- | --- |
| Protected control-plane and bootstrap operations | A registry-backed JWT (`control-ui`, `player-bootstrap`, or named private delegation) | Local signature/profile validation, one matching registry record, and one current Account evidence comparison for the route | Required; reachable invalid evidence denies, while an unreachable or timed-out dependency is `AUTH_UNAVAILABLE` |
| Gameplay-connect WebSocket handshake | The one-use `gameplay-connect` token | Gateway replay fence, quarantine cutoff, deny marker, exact `jti` atomic consume, and signed connect-context validation | Explicitly not used; this is the dedicated replay-fence exception, not a registry-backed JWT path |
| Non-JWT `LOGIN` | Credentials and, for first-party WebSocket use, the verified connect context | Current Account credential/lifecycle/security checks plus exact connection subject; Game Session creates the authenticated socket/session context and its initial binding fence | Not used; no JWT is presented and no registry lookup is invented |
| Non-JWT `PLAY` and fresh gameplay admission | The authenticated Game Session context | Exact bound-session identity, current membership/entitlement/grant/routing/ownership authority, binding fence, and Account exact-binding admission lease/CAS | Not used; the bound-session contract is authoritative |
| Reconnect, resume, or rebind without a presented JWT | The exact existing gameplay binding and its resume/rebind proof | Exact binding identity and fence, current Account lifecycle/security/membership/revocation authority, and the applicable resume lease; no target or scope expansion | Not used; stale, missing, or conflicting binding evidence denies the operation |
| Routine gameplay commands after admission | The validated bound Game Session context | Binding fences, admission/coordination leases, typed workload context, and domain authorization; bounded reconciliation consumes later authority changes | Not repeated per command; invalidation or conflicting reconciliation evidence terminates the binding |

Fresh gameplay admission, in-band `PLAY`, reconnect, and resume therefore use their bound-session admission contracts and only the current-authority checks those contracts require. Account revocation and membership changes still invalidate registry-backed JWTs immediately and invalidate affected gameplay bindings through bounded indexes/events and authoritative reconciliation; routine gameplay continuity never turns registry absence into authority and never adds a fresh registry lookup to every command.

### Gateway Boundary

Gateway is not a general identity, membership, entitlement, or gameplay-binding authority. It may perform only the authentication work owned by its bounded edge surfaces:

- validate and replay-protect short-lived gameplay connect tokens, then sign the internal connect context;
- require or validate credentials for Gateway-owned management routes according to their explicit route class; and
- strip untrusted identity/scope headers before forwarding.

These checks do not allow Gateway to issue account authority, infer membership, or bind a player to gameplay. Internal gameplay services authenticate the concrete mTLS caller, enforce the method allowlist, and validate player execution context and domain scope.

### Failure and Freshness Rules

- JWT-presenting protected control-plane/bootstrap operations and any new binding path that presents a registry-backed JWT fail closed when authoritative Account membership, entitlement, token-profile, issued-token registry, allowlist, or applicable authority-generation state cannot be established. Non-JWT `LOGIN`, `PLAY`, admission, reconnect, and resume fail closed when their exact bound-session, binding-fence, or current-authority evidence cannot be established.
- The only entitlement-freshness exception is the exact-binding grace-resume path defined by [ADR 0028](./adr-0028-differentiated-entitlement-freshness.md) and [ADR 0030](./adr-0030-risk-based-active-session-revocation.md): reconnect/resume of the same still-resumable binding may use an eligible positive entitlement snapshot for bounded continuity when fresh entitlement evaluation is unavailable. Account must still be reachable to validate fresh current lifecycle, security, membership, applicable grant, billing, and revocation authority and to commit the exact-binding `resumeActivationLease`; the snapshot cannot substitute for that authority, and any missing, stale, mismatched, or ambiguous authority fails closed. This exception never authorizes a new binding, target, instance, scale-out, or quota-increasing commitment.
- `membershipVersion` advances on every membership or role change that can alter gameplay or tenant authority; a database row identifier that does not advance is not a valid version.
- Ongoing sessions must consume authority, revocation, and membership changes through bounded indexes/events plus authoritative reconciliation; JWT expiry alone is insufficient for immediate revocation.
- Design acceptance and implementation status remain separate. Missing authority-generation enforcement, raw-JWT persistence, or non-monotonic versions are recorded implementation gaps, not alternate authority.

## Consequences

- Each security fact has one writer and one reconciliation source, reducing split-brain authorization.
- Game Session can manage low-latency gameplay lifecycle without owning credentials or durable tenant roles.
- Gateway remains replaceable and cannot silently grant access from edge-local state.
- Account Service is a critical dependency and concentrated security boundary. It requires high availability, recovery procedures, key protection, auditability, and fail-closed consumer behavior.
- Revocation propagation and versioned reconciliation add Redis, event, integration-test, and operational overhead.

## Alternatives Considered

### Dedicated IAM and Token Service

A dedicated IAM service could own signing, token registry, allowlists, authority generations, and validation policy while Account retained profile and billing state. This narrows Account's security surface but adds a critical service and consistency boundaries between credentials, membership, billing, and revocation. FireMUD will not introduce it before a demonstrated scaling, isolation, or security-ownership need.

### Gateway-Owned Identity and Authorization

Centralizing identity at Gateway simplifies edge routing but cannot safely authorize internal calls or own gameplay lifecycle without turning the edge into a broad policy and domain-data authority.

### TTL-Only Tokens

Relying only on short token expiry removes the registry, allowlists, and authority-generation propagation but cannot provide the required immediate account, tenant, or membership revocation behavior.

## Implementation and Proof Obligations

- Enforce strict token profile, audience, issued-token registry, allowlist, and applicable authority-generation checks in shared middleware for protected control-plane and JWT-presenting bootstrap/admission operations rather than signature-only parsing. Enforce the exact bound-session, binding-fence, and current-authority contracts for non-JWT `LOGIN`, `PLAY`, admission, reconnect, and resume. Do not impose a fresh registry or authority-generation lookup on every routine command after a binding is admitted; use the validated bound context and bounded reconciliation instead.
- Implement all Account-owned durable authority-generation writers/projections and prove logout-all, account, tenant, membership, and signing-key compromise revocation paths.
- Replace persisted raw gameplay JWTs with token hash/issued-at identity and freshly rebound backend credentials.
- Make `membershipVersion` and entitlement version/sequence monotonic state-change values rather than row identifiers.
- Prove `PLAY`, reconnect/resume, role changes, membership removal, and billing cutoff consume current Account authority and fail closed when it is unavailable, with only the ADR 0028/0030 exact-binding entitlement-snapshot exception for grace resume.
- Prove Gateway cannot create account or gameplay authority and that untrusted identity/scope headers cannot reach internal services as trusted context.

## Required Documentation Alignment

- [Authentication architecture](../system-architecture-authentication.md)
- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [Account runtime and data](../microservices/account-service/runtime-and-data.md)
- [Game Session runtime and data](../microservices/game-session-service/runtime-and-data.md)
- [Gateway architecture](../system-architecture-gateway.md)
