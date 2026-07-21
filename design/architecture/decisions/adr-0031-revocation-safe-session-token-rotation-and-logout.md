# ADR 0031: Revocation-Safe Session-Token Rotation And Logout

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-2.3` Takeover, logout, idle expiry, and revocation
- Affected capabilities: `AA-2.2`, `AA-1.3`, `SF-1.3`, `GR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-09`

## Context

A gameplay connection may remain active longer than the private `game-session-account-delegation` JWT that Game Session uses for Account calls. Without planned rotation, healthy long-running sessions fail at token expiry. A refresh surface that trusts only Game Session's workload identity can create the opposite problem: logout-all, password reset, security lock, or membership revocation may invalidate the current token and then lose a race to a newly issued token with a later `iat`.

The current target already requires periodic refresh, atomic binding replacement, idempotent per-token logout, and generation-based logout-all. The review retains those boundaries and makes refresh lineage, authority generations, expiry scheduling, overlap, and logout scope explicit. Current implementation has no complete periodic rotation or refresh-generation path; the role-refresh service is a placeholder, gameplay session state lacks the complete target token metadata, and Account logout/logout-all authority-generation workflows are absent.

## Decision

### Private Player-Delegation Token Rotation

- The rotated JWT is the receiver-specific private player-delegation profile `game-session-account-delegation` with audience `account-service`. It is private backend material for Account calls; gameplay clients never receive it. Gameplay-domain services continue to authorize the concrete mTLS workload and typed `PlayerExecutionContext`; they do not gain a per-action player-JWT dependency.
- Game Session schedules planned refresh at approximately half of the current token lifetime with random jitter and always before `exp` by the configured safety margin.
- A 60-second minimum retry interval may prevent a refresh storm only while sufficient validity remains. It cannot postpone the final safe attempt beyond expiry.
- Concurrent refresh demand for one gameplay binding is single-flighted. Transient failure while the current token remains valid uses bounded jittered retry.
- `tokenGeneration` is the positive monotonic generation for one JWT issuance/refresh lineage. It is distinct from the Account-owned monotonic authority generations for issuer, account, tenant, and `{accountId, tenantId}` membership scope. The Account-owned refresh request presents the current token identity and lineage generation, the gameplay binding/account/tenant identity, and an idempotent request ID. Account validates the current issued-token registry lineage generation, account state, and each applicable authority generation. The shared authority-generation record shape is a dependency of a follow-on identity decision; this ADR defines the refresh and logout ordering that consumes it. [ADR 0035](./adr-0035-single-record-issued-token-registry.md) subsequently defines the one-record registry shape.
- Refresh locks the applicable account authority generation, or uses an equivalent compare-and-set tied to that durable value, before it creates and commits a replacement token. Replacement issuance succeeds only when the lineage and authority generations still equal the values validated from the presented token; a concurrent account-wide revocation therefore wins rather than allowing refresh to cross the cutoff.
- Game Session mTLS identity authorizes calling the refresh API but is not authority to mint for an arbitrary player or to cross a current authority generation.
- An auth-expired response may trigger immediate refresh while the current lineage and authority generations remain refresh-eligible. An auth-revoked response triggers authoritative reconciliation; logout-all, password reset, security lock, membership loss, or another blocking authority-generation advance cannot be bypassed by giving the replacement a newer `iat`.
- Account creates the replacement token registry record before returning it. Game Session atomically replaces `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, and refreshed membership metadata before new calls use the token.
- The old token may overlap only through the shorter of its original expiry or the maximum deadline of internal RPCs already started before the binding swap. Its single registry record is then removed idempotently.
- Rotation never changes `continuityBindingExpiresAt` or `resumeDeadline`. If refresh cannot establish authority before expiry, backend-authenticated actions fail closed and the player must complete fresh login.

### Per-Token Logout

- `POST /auth/logout` revokes only the presented `control-ui` JWT or `player-bootstrap` token and is idempotent when its issued-token registry record is already absent.
- The first logout attempt uses normal registry-backed authorization. A retry may return no-op success after full local signature, profile, time, and subject validation when the exact presented token record is absent; that exception creates no authorization context and performs no mutation beyond the already-complete revocation. Invalid, expired, wrong-profile, or ambiguous tokens remain denied.
- Other devices and unrelated gameplay bindings for the account remain active.
- A first-party player UI performs its local/device logout sequence separately: stop reconnect, gameplay `LOGOUT`, close the socket, revoke the current bootstrap token, and clear local state. Account does not locate gameplay sockets from the per-token endpoint.
- The audit action is explicitly `token_logout` and records bounded actor, account, token-profile/hash identifier, request, and outcome metadata without the raw token.

### Account-Wide Logout

- `POST /auth/logout-all` atomically advances the durable account authority generation with its account-wide logout event, distinct `account_logout_all` audit record, and outbox record. The new account authority generation invalidates earlier account-scoped authority without comparing it to JWT `iat`.
- Logout-all and every equivalent account-wide security cutoff serialize against private-token refresh through the same account generation row or compare-and-set. A refresh validated against the previous generation cannot commit after the cutoff advances it.
- The current account authority generation, not deletion of every token record, is immediate correctness authority. Bounded background cleanup may remove older token records.
- Logout-all is idempotently successful when no live tokens remain.
- The first logout-all attempt uses normal registry-backed authorization. A retry whose registry record or account-generation check no longer passes may return no-op success only when durable Account authority proves that a prior logout-all generation/event already superseded the presented token. It must not advance authority or mutate state from this retry path; current or ambiguous tokens remain denied.
- The event terminates all control-plane tokens, player-bootstrap tokens, and active gameplay bindings for the account across tenants through ADR 0030. A later deliberate login with fresh credentials starts a new generation normally.
- Password reset, security lock, and other account-wide revocations use distinct audit/event action types even when they advance the same account authority generation.

## Consequences

- Healthy long-running gameplay does not fail merely because a private control-plane token reaches its ordinary expiry.
- A trusted Game Session cannot use automatic refresh to resurrect a generation invalidated by logout-all or another security event.
- Planned refresh adds one bounded Account interaction per active session per fraction of token lifetime, with jitter and single-flight to avoid synchronized load. It is not on the gameplay command hot path.
- Atomic swap plus bounded overlap prevents new calls from using the old token while allowing already-started RPCs to finish.
- Logout current device no longer unexpectedly signs out every device; logout-all deliberately terminates gameplay as well as control-plane sessions.
- The model requires a lineage- and authority-generation-bound refresh API, expiry-aware scheduler, token-entry lifecycle, durable account-wide events, authority-generation projection, and cross-device/gameplay proof.

## Alternatives Considered

### Refresh Only At Expiry Or First Failure

This removes scheduled work but creates synchronized expiry failures, adds user-visible latency, and makes recovery dependent on an already-expired credential.

### Long-Lived Tokens Without Rotation

Long lifetimes reduce refresh traffic but extend stale-role and stolen-token exposure and make revocation machinery carry more risk.

### Workload-Identity-Only Refresh

Allowing Game Session to mint a fresh player token from mTLS plus an account ID is simple, but can cross logout-all and security authority generations. The current token lineage generation must remain part of refresh authority.

### Delete Every Token For Logout-All

Enumerating and deleting all token keys creates race, indexing, and partial-failure problems. A monotonic account authority generation gives one correctness decision while cleanup remains bounded background work.

### Make Per-Token Logout Global

This has one simpler logout meaning but unexpectedly terminates other devices and gameplay sessions when a user only intended to sign out the current browser.

## Implementation and Proof Obligations

- Define and implement `RefreshGameplayServiceToken` with current-lineage subject binding, idempotency, live membership/account validation, and applicable authority-generation comparison.
- Persist `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, refreshed membership metadata, and rotation CAS generation in the authenticated gameplay binding.
- Implement half-life scheduling, jitter, single-flight, expiry safety margin, bounded retry, atomic swap, old-entry cleanup, and failure-to-fresh-login behavior.
- Prove refresh cannot cross issuer, account, tenant, or membership authority-generation advances, including races with logout-all, password reset, security lock, and membership removal.
- Implement idempotent `/auth/logout` for `control-ui` and `player-bootstrap` profiles with bounded scoped-key lookup and distinct audit events.
- Implement logout-all durable event/audit, Account-owned account-authority-generation advancement, active-gameplay termination, idempotency, and background token cleanup.
- Prove per-token logout leaves other devices and unrelated gameplay active, while logout-all terminates every active account binding and permits later deliberate reauthentication.
- Prove gameplay-domain routine actions acquire no private player-delegation JWT refresh or authority-generation lookup.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/architecture/decisions/adr-0030-risk-based-active-session-revocation.md`
- `design/project-management/implementation-tracking/player-access-and-session.md`

## Reversibility and Revisit Triggers

Refresh timing, retry, and overlap are centralized policy that can be tuned without changing logout scope or authority ownership. Revisit if measured refresh load is material, token lifetimes become shorter than safe scheduled rotation permits, cross-device UX requires a durable device/session management surface, or gameplay services no longer need the `game-session-account-delegation` profile for Account calls.
