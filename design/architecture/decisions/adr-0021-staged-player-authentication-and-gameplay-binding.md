# ADR 0021: Staged Player Authentication and Gameplay Binding

## Status

Accepted

## Implementation Status

Bootstrap, connect-token, and visible `LOGIN`/`PLAY` pieces exist, but the target sequence is not runtime-complete. `/auth/player-bootstrap` now authenticates account identity before tenant selection and accepts the account's configured password or verified-email OTP mode. Browser and mobile-browser connect-token responses are metadata-only and carry the raw token only in the HttpOnly cookie; public non-browser connect-token issuance and dedicated header carriage are target-only and unavailable, while protected secure storage plus the header is target contract only. Explicit `POST /auth/bootstrap/join` before bootstrap character creation/connect-token work remains a gap. The target `JOIN_REQUIRED` outcome remains canonical, but current connect-token issuance and text `PLAY` may still invoke Account's `EnsurePublicProductionPlayerMembership` and implicitly create missing public-production membership until convergence. The exact `membershipLifecycleState=ACTIVE` admission check, complete `membershipAuthorityGeneration` live reread, and independent `membershipVersion` issuance baseline remain unimplemented/unproven, and explicit `JOIN` remains unimplemented. The target first-party admission gate therefore remains incomplete until explicit `JOIN`, the active-state check, the generation reread, and the unchanged issuance baseline are implemented and proven. The target sequence is `LOGIN` -> explicit `JOIN`/`POST /auth/bootstrap/join` for a first public-production entry -> `CHARS`/bootstrap character creation as needed -> `POST /auth/connect-token` with fresh membership lifecycle, generation, and version rereads -> WebSocket `LOGIN` -> `PLAY`. Returning members use their existing membership. Grant-backed private/playtest players require both a current grant and existing membership, skip `JOIN`, and never create membership through this flow. The route matrix records the unimplemented explicit-membership `JOIN` path; its `known_drift` must also explicitly name the missing active-state, membership-generation, and membership-version baseline checks.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AA-2.1` Gameplay login, character selection, and session binding
- Affected capabilities: `PO-2.1`, `PO-2.2`, `SF-1.3`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-02`

## Context

First-party browsers and explicitly classified non-browser WebSocket clients authenticate through the HTTPS bootstrap/connect-token path before opening public gameplay WebSockets, while Telnet and other non-WebSocket text transports need an in-band credential path. FireMUD must distinguish transport admission, account authentication, and gameplay binding without making the Gateway the general account-authentication authority. [ADR 0029](./adr-0029-single-use-gameplay-connect-token-carriage.md) subsequently made a connect token mandatory for every non-proxy public `/ws/game/**` handshake and defined the dedicated header carrier for non-browser clients.

The staged sequence is partially implemented and tested. It adds an explicit browser `LOGIN` transition and short-lived token infrastructure, but preserves one visible `LOGIN` then `PLAY` protocol and leaves Game Session as the final gameplay-login state owner. The current membership existence check is implemented, while explicit membership joining and the complete membership-authority-generation reread remain target-state work.

## Decision

### First-Party Browser Flow

1. `POST /auth/player-bootstrap` authenticates the platform account without requiring a tenant selection and issues only a short-lived player-bootstrap identity.
2. Bootstrap discovery lets the authenticated player select a visible world and realm context; the player-bootstrap profile authorizes only caller-bound discovery, bootstrap-authenticated character discovery and character creation, `POST /auth/bootstrap/join`, and `POST /auth/connect-token`.
3. A first public-production entry explicitly invokes `JOIN` or `Join & Play`. The public-production join may create durable `player` membership only when that membership is missing; otherwise it reuses the canonical row. If that row is `INACTIVE`, `JOIN` restores it to `ACTIVE` through the retained-membership lifecycle transition defined by [ADR 0042](./adr-0042-global-account-and-tenant-scoped-game-relationships.md), advancing both `membershipAuthorityGeneration` and `membershipVersion` in the Account commit and returning the resulting Account snapshot. `ACTIVE` membership remains idempotent: `JOIN` returns the exact current snapshot without another row, logical join event, or authority/version advance. Any non-`ACTIVE` membership never admits gameplay. If the caller lacks the required public-production membership, connect-token issuance and `PLAY` return `JOIN_REQUIRED` until this operation succeeds. Grant-backed private/playtest realms require both the applicable current grant and existing membership, with lifecycle state exactly `ACTIVE`, skip `JOIN`, and never create membership through this flow.
4. Character discovery and any allowed character creation occur for the selected realm after the explicit join where one is required.
5. `POST /auth/connect-token` revalidates the selected target, rereads current caller-bound membership, requires exact `membershipLifecycleState=ACTIVE`, and captures an immutable issuance baseline `{membershipLifecycleState, membershipAuthorityGeneration, membershipVersion}` only for a successful `COMMITTED` issuance from that Account snapshot. It issues a single-use connect token with a lifetime no longer than 30 seconds and never creates membership. Deterministic failures, including a missing public-production membership, persist terminal error evidence for that request digest as `JOIN_REQUIRED` or the applicable error, with no token and no issuance baseline.
6. Browser and mobile-browser clients receive that token only in a `Secure`, `HttpOnly`, appropriately scoped cookie. First-party native-mobile clients using a cookie jar remain cookie-only. The target contract for explicitly classified non-first-party/public native-mobile and other non-browser clients is protected secure storage plus the dedicated header, but public non-browser issuance and header carriage are target-only and unavailable until the dedicated route is fully registered and proven. Browser JavaScript receives non-secret connection metadata, not the token value.
7. Gateway validates expiry, signature, audience, scope, and one-time use before admitting `/ws/game/**`, then attaches a signed internal connect context. This is transport admission, not general Gateway-owned account authentication.
8. The browser sends bare `LOGIN`. Game Session validates and consumes the signed connect context, establishes the authenticated account state, and does not request credentials again.
9. `PLAY` remains mandatory and receives the selected stable world, realm, and character context. For a token from a successful `COMMITTED` connect-token issuance, Game Session rereads the current authoritative membership snapshot and requires exact `membershipLifecycleState=ACTIVE`; it compares `membershipLifecycleState`, `membershipAuthorityGeneration`, and independent `membershipVersion` to the immutable issuance baseline and rejects any mismatch. Account issues the exact admission lease from that snapshot, and Game Session's gameplay-binding CAS atomically carries and fences the complete unchanged baseline; Account finalizes only that same fenced lease and never recomputes or substitutes the baseline. Any change to any captured membership or authority value after token issuance or before commit rejects the binding, with no partially admissible record. Game Session resolves the current admissible `gameInstanceId` server-side; only the internal gameplay binding carries `{tenantId, resolvedGameInstanceId, characterId}` after these current admission checks.

Browser mutation security is fail-closed. A browser-authenticated state-changing request that relies on cookies must use the `Secure`, `HttpOnly`, `SameSite=Strict` session/connect cookie, carry a valid anti-CSRF token in the designated request header, and include an `Origin` that exactly matches the configured first-party origin allowlist. Missing, malformed, mismatched, or unallowlisted `Origin` or CSRF proof rejects the request; `SameSite`, `Referer`, user-agent classification, or a caller-supplied header alone is not sufficient. The same origin/CSRF policy applies to the exact Gateway connect-token revocation route defined by ADR 0029. Browser WebSocket admission also rejects a missing or unallowlisted `Origin`; non-browser and Telnet transports use their own authenticated transport boundaries and do not weaken the browser rule.

Public-production membership and grant-backed realm access are different authorities. The public-production `JOIN` operation may create the durable `player` membership for that public realm only when it is missing; an existing `ACTIVE` membership is returned idempotently, while an `INACTIVE` membership is restored to `ACTIVE` through the ADR 0042 lifecycle transition with the required `membershipAuthorityGeneration` and `membershipVersion` advances, and the resulting Account snapshot is returned. Any non-`ACTIVE` membership never admits gameplay. Every private/playtest admission requires both a valid current grant and an existing membership, checked independently; the grant never makes membership optional. Neither `JOIN` nor grant validation creates private/playtest membership as a side effect.

Reconnect uses a fresh bootstrap/connect-token sequence where required by the owning token lifetime, followed by fresh `LOGIN` and `PLAY`. Its short-lived snapshot is `{connectScopeId, tenantId, realmSlug, gameInstanceId, pointerVersion, catalogRevision, evaluatedAt, connectScopeExpiresAt}`; `CONNECT_SCOPE_MISMATCH` discards that entire snapshot and every derived connect-token field before rediscovery. A resumable gameplay session does not eliminate these authentication and binding steps after transport loss.

### Telnet and Non-WebSocket Text Protocol Flow

Telnet and other non-WebSocket text clients authenticate in-band with `LOGIN <email> <secret>`. A first public-production entry then uses explicit `JOIN <world>` before `CHARS` and `PLAY`; the user-entered world value is only a selector that Game Session resolves server-side to the current catalog identity and verified `connectScopeId` before Account creates membership, and a missing public-production membership returns `JOIN_REQUIRED`. Raw world, realm, tenant, or scope text is never membership authority. Grant-backed private/playtest entry requires both the grant and existing membership with lifecycle state exactly `ACTIVE`, skips `JOIN`, and does not create membership. Account Service interprets the secret according to the account's enabled `PASSWORD` or verified-email `EMAIL_OTP` mode. Bare prompt-based login remains the target text-client experience, but its absence is an implementation gap rather than a different authentication contract. Only this credential-bearing text-client path may use the direct existing-member `LOGIN` plus `PLAY` compatibility exception; first-party/browser clients remain behind authenticated lobby gates.

Public non-browser WebSocket clients do not use this credential-bearing exception. Their scoped connect-token issuance and dedicated non-browser handshake header are target-only and unavailable until the route is fully registered and proven; the target flow uses protected secure storage and then bare `LOGIN` after Gateway has established the signed connect context. Direct Game Session WebSocket access remains an internal/test seam rather than a separate public authentication contract.

Plaintext Telnet credentials are a legacy exposure. As subsequently constrained by [ADR 0033](./adr-0033-public-player-facing-telnet-requires-tls.md), public player-facing deployments require Telnet-over-TLS or the first-party browser path. Local, test, and explicitly private-network plaintext listeners retain the documented pre-login warning where real credentials could be entered.

### Factor Policy

Ordinary gameplay login supports password and verified-email OTP modes. Authenticator-app TOTP or WebAuthn is not a mandatory gameplay-admission factor. Stronger factors and reauthentication for elevated creator, operator, billing, or administrative actions are separate control-plane security decisions and may be added without changing the gameplay sequence.

Every configured gameplay login mode must work through the applicable first-party bootstrap and credential-bearing flows. A first-party bootstrap implementation that rejects an `EMAIL_OTP`-only account does not satisfy this decision.

## Consequences

- Account authentication, transport admission, and gameplay identity binding remain explicit and independently testable.
- Browser, non-browser WebSocket, and text transports share the visible `LOGIN` then `PLAY` state model even though their credential and connect-token carriage differs.
- Game Session remains the final gameplay-login state owner; Gateway stays a bounded token-validation and connection-admission boundary.
- Browsers incur one additional protocol transition after the WebSocket opens.
- Operators must test token issuance, cookie carriage, expiry, replay denial, signed-context validation, `LOGIN`, `PLAY`, and reconnect behavior across releases.
- Mandatory TOTP does not burden ordinary players, while elevated control-plane factor policy remains available as a separate security boundary.

## Alternatives Considered

### Complete Browser Login at the WebSocket Handshake

Automatically authenticating the browser from the verified handshake context removes bare `LOGIN` and one client transition. It also moves more authentication semantics into the edge path, makes browser and text protocol state machines diverge, and weakens the explicit Game Session login boundary.

### Send Browser Credentials Through Gameplay `LOGIN`

This gives every transport identical credential carriage but exposes credentials to more components and repeats secrets after HTTPS bootstrap. The connect context exists specifically to avoid that replay.

### Require TOTP or WebAuthn Before Every Gameplay Connection

This strengthens account takeover resistance but adds enrollment, recovery, support, accessibility, and reconnect friction disproportionate to ordinary gameplay. Elevated control-plane actions can adopt stronger factors separately.

## Implementation and Proof Obligations

- Remove any tenant requirement from account-level player-bootstrap authentication; tenant/realm selection occurs through authenticated discovery and is revalidated at connect-token issuance and `PLAY`.
- Ensure `PASSWORD` and `EMAIL_OTP` account modes both work through first-party bootstrap where configured.
- Ensure every first-party browser/mobile-browser connect-token response carries the token only through the secure HttpOnly cookie, and first-party native-mobile clients using a cookie jar remain cookie-only. Target-only public non-browser issuance must first have a fully registered route and complete proof; only then may explicitly classified non-first-party/public native-mobile and other non-browser WebSocket clients receive the token through protected secure storage and present it through ADR 0029's dedicated handshake header. Raw Telnet and other non-WebSocket text clients use their separate in-band login flow.
- Prove token expiry, replay, scope mismatch, missing signed context, account mismatch, and pre-`PLAY` gameplay commands fail closed with stable errors.
- Prove `JOIN_REQUIRED` for a public-production caller without membership, prove explicit `JOIN` is the only action that may create a missing public-production membership and resolves its user selector to a verified `connectScopeId` before the Account write, restores an `INACTIVE` relationship to `ACTIVE` only through the ADR 0042 lifecycle with both `membershipAuthorityGeneration` and `membershipVersion` advanced in the Account commit, returns the resulting exact Account snapshot, keeps `ACTIVE` `JOIN` idempotent without an authority/version advance, and never admits a non-`ACTIVE` membership. Prove grant-backed private/playtest admission requires both its grant and existing membership with exact lifecycle state `ACTIVE` without creating membership.
- Prove `IssueConnectToken` persists the immutable `{membershipLifecycleState=ACTIVE, membershipAuthorityGeneration, membershipVersion}` issuance baseline only with a successful `COMMITTED` token issuance, while deterministic failures including `JOIN_REQUIRED` persist terminal error evidence with no token or baseline; prove `PLAY` compares one current membership snapshot to that committed baseline, atomically carries the unchanged values through the Account lease, gameplay-binding CAS, and Account finalization, rejects any change before commit, and never treats token-carried membership evidence as the final admission decision.
- Prove browser and non-browser connect-token-backed WebSocket `LOGIN` never request credentials again and that Telnet credential login reaches the same authenticated pre-`PLAY` state.
- Complete and prove the Telnet/non-WebSocket prompt-based login UX or continue reporting it as partial implementation.
- Exercise first connection, disconnect/reconnect, revoked/expired authentication, and resumable-session behavior in focused integration and player-experience proof.

## Required Documentation Alignment

- [Authentication architecture](../system-architecture-authentication.md)
- [Gateway architecture](../system-architecture-gateway.md)
- [Reconnection architecture](../system-architecture-reconnection.md)
- [Account API contracts](../microservices/account-service/api-contracts.md)
- [Game Session protocols](../microservices/game-session-service/protocols.md)
