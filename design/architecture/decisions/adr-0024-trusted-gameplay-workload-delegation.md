# ADR 0024: Trusted Gameplay Workload Delegation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Shared authentication, authorization, and policy primitives
- Affected capabilities: `SF-1.1`, `SF-1.2`, `GR-1.1`, `PO-3.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-05`, including separate current-design and simplest-credible-alternative evidence passes

## Context

Gameplay commands fan out through first-party services on hot paths. The prior target required Game Session to sign a destination- and method-bound `SessionAttestation` for every delegated gameplay RPC and required every consumer to verify it and write a one-time replay guard. That design limits an allowlisted intermediary that becomes malicious, but adds per-action crypto, key distribution and rotation, replay-store latency and capacity, failure dependencies, and proof work.

Game Session is itself the attestation signer and gameplay-session authority, so attestations cannot contain a compromised Game Session. They also cannot contain a compromised destination. Their incremental protection is principally against a compromised or seriously buggy allowlisted intermediary fabricating or replaying player context. FireMUD currently runs first-party gameplay workloads inside one operated trust domain and does not admit third-party workloads to internal gameplay RPCs.

## Decision

FireMUD accepts trusted first-party gameplay workloads as one internal delegation domain. Routine gameplay RPCs do not use per-action signed player attestations.

### Workload and Method Authorization

- Every gameplay service presents a unique mTLS workload identity derived from its authenticated certificate, such as an approved SPIFFE ID or SAN. A generic `internalService=true` bearer claim is not sufficient workload identity.
- Every internal gameplay RPC has a default-deny allowlist of exact caller workload identities. Network policy narrows reachability but does not replace application-level caller enforcement.
- Services reject client-supplied or forwarded raw identity headers as authority. Only the authenticated workload and the typed request contract participate in internal gameplay delegation.

### Player Execution Context

Player-delegated gameplay RPCs carry a typed protobuf `PlayerExecutionContext`. It contains the required subset of:

- `accountId`, `tenantId`, `gameInstanceId`, `sessionId`, and `characterId`;
- applicable room, region, lease/epoch, admitted bundle, realm, pointer, or playable-state scope; and
- stable request, command, or effect identity required by the owning operation.

`PlayerExecutionContext` is unsigned structured scope data, not a credential or capability. Its purpose is to prevent missing, ambiguous, or mismatched identity and routing fields. Consumers:

- validate required fields and equality with duplicated request fields;
- scope existing reads and writes by the complete tenant/game/resource identity;
- validate domain ownership, visibility, lease, and playable-state relationships required by the operation; and
- record the player context, authenticated calling workload, and request/command/effect identity in applicable audit and diagnostic events.

Context validation should be folded into existing scoped queries and domain checks. It must not introduce a fresh Account, Redis, or database round trip merely to validate every routine action.

### Mutation Replay and Sensitive Operations

- Gameplay mutations retain their command/effect/request idempotency contracts. Reads do not receive a generic one-time replay store.
- FireMUD does not maintain gameplay-attestation signing keys, attestation JWKS, per-action signatures, attestation `jti` replay keys, or a universal delegation-token lifecycle.
- Administrative and operator actions use their control-plane user authentication, live role and scope checks, step-up authentication where separately required, and durable audit. They do not treat gameplay context as authorization.
- Real-money operations use provider signatures, server-owned purchase/payment state, idempotency, ledgering, and reconciliation. A gameplay context never proves that payment occurred.
- Virtual-currency and item mutations use their owning transactional and idempotency contracts. If an asset becomes convertible to real value, the financial-security boundary must explicitly govern it rather than enabling universal gameplay attestations.

### Accepted Risk

A compromised allowlisted intermediary can fabricate another player's context for the exact RPCs that workload is permitted to call. This risk is explicitly accepted for the current first-party gameplay trust domain. The smaller model still protects against clients, network attackers, and unrelated workloads through authentication, mTLS, caller allowlists, full scope validation, and mutation idempotency.

This is the complete target state, not a deferred requirement to build universal attestations later. Revisit delegation only when an actual trust-boundary change occurs, such as independently operated workloads receiving internal gameplay RPC access, or when incident evidence shows method-scoped workload trust is insufficient.

## Consequences

- Routine movement, observation, communication, combat, and tick work avoids per-action signing, verification, replay-cache round trips, replay-key memory, and attestation-key operations.
- Typed context adds only compact protobuf fields and local validation, most of which replace already duplicated scope fields.
- Concrete certificate identity, method allowlists, scoped queries, idempotency, and audit become mandatory rather than optional compensating controls.
- A compromised allowlisted intermediary has a larger player-impersonation blast radius than under correctly implemented Game Session-origin attestations.
- Sensitive control-plane and financial boundaries receive purpose-built protections without charging every gameplay action for them.

## Alternatives Considered

### Per-RPC Signed and Replay-Guarded Attestations

This provides cryptographic Game Session origin proof and limits an intermediary's ability to invent context. It does not protect against the signer or destination and adds asymmetric crypto, token payload, key rotation/discovery, replay-store writes, hot-path availability coupling, and broad release proof.

### Signed Attestations Without Universal Replay Storage

Removing replay writes lowers latency and availability cost while retaining origin proof. It still adds per-RPC signing, verification, payload, key lifecycle, and call-site complexity for a threat FireMUD has chosen to accept.

### Generic Service JWTs Carrying Player Identity

This reuses token infrastructure but conflates workload authentication with player delegation and spreads reusable end-user authority across services.

## Implementation and Proof Obligations

- Replace `SessionAttestation` fields and helpers in gameplay requests with one canonical `PlayerExecutionContext` message; remove obsolete signing, verification, replay, and key-lifecycle code and configuration.
- Bind server-side authorization to concrete mTLS certificate identities and enforce an exact caller allowlist for each gameplay RPC.
- Prove client/untrusted metadata cannot create player context, wrong-service callers fail, and wrong-tenant/game/session/character contexts fail closed.
- Prove reads add no authorization-store round trip and mutations retain their operation-specific idempotency behavior.
- Prove audit/diagnostic records preserve authenticated caller workload and scoped player/request identity without logging credentials.
- Keep admin/operator and payment proof on their owning control-plane and financial boundaries.

## Superseded Guidance

This decision supersedes the `SessionAttestation` requirements in ADR 0014 and the legacy Authentication & Authorization, Security, JWT and Token Contracts, and gameplay service API documentation. ADR 0022 already uses the accepted unsigned typed context and therefore is not a superseded source. This decision does not change Account JWT rotation, Gateway connect-context signing, mTLS certificate lifecycle, or command/effect idempotency.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-jwt-and-token-contracts.md`
- `design/architecture/system-architecture-session-behavior.md`
- gameplay service API and implementation-tracking documentation
