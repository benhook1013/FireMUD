# FireMUD System Architecture: JWT and Token Contracts

This document defines the JWT profiles, claim requirements, issued-token registry, Account authority generations, and token-validation behavior used by FireMUD services. It complements [Authentication & Authorization](./system-architecture-authentication.md), which defines how these token contracts are applied to route classification, gameplay admission, and tenant authorization.

Each registry-backed revocable `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT has exactly one Account-owned Coordination Redis record: `session:auth:token:<tokenHash>`. The separate `gameplay-connect` profile is not registry-backed and uses its dedicated single-use replay contract.

`tokenHash` is a fixed-length SHA-256 digest of the complete compact JWT. The canonical bounded registry record contains `accountId`, exact token profile/type/audience, the exact Account issuer, `jti`, `iat`, `exp`, the JWT's `tokenGeneration`, active state, and the applicable Account-owned authority snapshot. Its `tenantAuthorityGeneration` and `membershipAuthorityGeneration` fields are maps, not scalar values: each is keyed by the exact non-empty set of tenant UUIDs in the token's tenant scope, and the two maps have exactly the same key set. For an unscoped token both registry fields are absent, while the corresponding JWT map claims serialize as `{}`; validators compare the effective exact tenant UUID key sets and treat absent registry fields as equivalent to empty claims only for a token whose scope is explicitly unscoped. A scoped token requires both registry maps and both claims, with identical non-empty key sets and values. No map may contain an empty key, wildcard, account ID, or other non-tenant key. Missing, extra, malformed, or differently keyed scoped entries fail closed. The record proves that Account issued this exact still-active token but does not duplicate tenant/global roles from its signed claims. Account creates the record before returning the token; registration failure means issuance failure.

The issued-token registry uses the following canonical field-to-claim mapping: `issuerAuthGeneration` maps directly to the JWT claim `issuerAuthGeneration`; `accountAuthorityGeneration` maps to `accountAuthGeneration`; each registry `tenantAuthorityGeneration[tenantUuid]` maps to the value at the same exact tenant UUID key in `tenantAuthGenerations`; and each registry `membershipAuthorityGeneration[tenantUuid]` maps to the value at that same exact tenant UUID key in `membershipAuthGenerations`. Account captures the durable authority snapshot used for the token under the issuance fence. The registry projection carries source-version and freshness evidence but is not a second authority.

The record's absolute cleanup deadline is derived from that token's own JWT `exp` claim plus the cleanup margin; it is not derived from a global session lifetime:

- `registry_ttl_ms = max(0, token_exp_ms - now_ms) + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

## Implementation Status

This document defines target-state token and revocation behavior. The current runtime has no complete issued-token registry or Account-owned authority-generation issuance, advancement, propagation, and validation path, and validators still use shared-HMAC verification rather than Account JWKS. The first implemented authority-generation path must prove that issuance and refresh cannot cross a concurrent generation advance and that every affected route rejects stale generations; no such runtime proof is currently claimed.

## Token Validity and Revocation

For registry-backed JWT profiles (`control-ui`, `player-bootstrap`, and receiver-specific private player-delegation profiles), token validity semantics are:

- A registry-backed JWT must be cryptographically valid (signature, required claims `iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, `tokenGeneration`, and expected token profile/type and audience) and must have one matching `session:auth:token:<tokenHash>` record in Coordination Redis whose account, issuer, profile/type, `jti`, `tokenGeneration`, and time fields agree with the verified claims.
- The matching registry snapshot must compare every applicable issuer/account/tenant/membership authority generation and private-realm `grantVersion` with current Account-owned state and fail closed on any mismatch. `iat` remains required for chronology, bounded clock-skew handling, and audit, but is not an authorization or revocation authority and cannot replace generation or grant-version comparison.
- For tenant or cross-tenant operations, the requested operation must then be authorized from the validated `scopedRoles` or `globalRoles` claims plus the applicable Account-owned authority-generation/version state. The issued-token record does not grant scope independently.
- Coordination Redis therefore acts as a server-side issued-token registry and immediate per-token revocation surface: deleting the one record revokes a still-unexpired JWT; coordination resets that drop `session:auth:*` force re-authentication.
- The single-use `gameplay-connect` token and Gateway-signed connect context use their separate bounded replay/verification contracts and do not create Account issued-token records; `nbf` and `tokenGeneration` registry requirements do not apply to that profile.
- During Coordination Redis outages, routes explicitly gated by a revocable JWT registry check fail closed (authorization cannot be established without that registry check). Ordinary gameplay RPCs are not JWT-bearing routes: they explicitly declare concrete authenticated mTLS workload identity plus the typed `PlayerExecutionContext`, so they do not require a player JWT registry record and are not an exception from registry validation. This is an explicit availability-versus-security boundary, not permission to invent local-only authority for routes that do require the registry.

Bulk revocation (for example “logout all devices”, account bans, membership loss, or tenant-wide billing suspensions) must not rely on wildcard deletes, key scans, or wall-clock ordering. [ADR 0036](./decisions/adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) defines Account-owned positive monotonic authority generations:

- `session:auth:generation:issuer:<issuerId>` – environment-wide Account issuer authority, advanced for signing-key compromise, post-restore trust reset, or another explicitly global issuer event.
- `session:auth:generation:account:<accountId>` – account-wide authority, advanced for logout-all, security lock, password reset, or another account-wide cutoff.
- `session:auth:generation:tenant:<tenantId>` – regular tenant authority, advanced for tenant-wide gameplay/billing cutoff.
- `session:auth:generation:membership:<accountId>:<tenantId>` – caller-bound tenant authority, advanced when membership or tenant roles change.

### Explicit Route-Class Generation Allowlist

Tenant-generation revocation applies by default to every tenant-bearing route. The closed omission allowlist contains exactly these route classifications; separate no-target-tenant classifications are not allowlist members:

- `billing_safe_tenant` requires current issuer and account generations, the caller-bound `{accountId, tenantId}` membership generation, exact target-tenant binding, and a live `tenantAdmin` membership/role check.
- `cross_tenant_support_safe` requires current issuer and account generations, global token scope, and a live global `support` role or explicitly allowed `platformAdmin` role. Support does not require `privileged_control`; the platform-admin alternative does.
- `cross_tenant_billing_safe` requires current issuer and account generations, global token scope, and a live global `billingAdmin` or explicitly allowed `platformAdmin` role with `privileged_control` assurance.

Every other ordinary tenant-bearing route class requires the tenant generation declared by its route entry. In particular, `platformAdmin` requests in `tenant_regular` or `cross_tenant_data_bearing` require active TOTP-backed `privileged_control` assurance and bind and validate the exact current target-tenant generation as a target-scope freshness fence; they do not obtain tenant membership or add a tenant to JWT claims. No-target-tenant classifications such as `public`, `pre_tenant_discovery`, `account_scoped`, `pending_deletion_scoped`, and targetless `internal_workload` routes are outside target-tenant generation because their route scope has no target tenant; `pending_deletion_scoped` is specifically a separate no-target classification, not a member of the closed omission allowlist. `public_production_onboarding` and `caller_membership_scoped` may carry selected tenant context for discovery/onboarding or membership mutation while explicitly using no target-tenant generation; they rely only on the membership, grant, admission, or caller-bound checks declared by their route entries. `player_bootstrap_tenant` is also tenant-free at the token level: its `player-bootstrap` credential carries empty tenant/membership-generation maps and uses caller-bound membership authority only; the classification name must not be interpreted as a target-tenant generation claim. These no-target or explicitly target-generation-free cases do not expand the closed route-class allowlist, and a tenant-bearing classification must never inherit an exemption by name or service convention. Negative proof must show a tenant-generation advance denies non-allowlisted ordinary tenant-bearing routes and that each allowlisted route still denies missing issuer/account/membership authority, wrong target tenant, wrong global role, stale scope, or missing role assurance.

Authority-generation contract requirements:

- Account Service owns durable current generations and is the sole writer of their Coordination Redis projections.
- Account advances the applicable durable generation in the same transaction as the authority change and commits the monotonic outbox event. The `session:auth:generation:*` projection is an asynchronous outbox consumer output, not an atomically committed part of that durable generation/event transaction. Projection is idempotent set-if-greater and cannot regress; consumers fail closed while the projection or its freshness/source evidence is missing, stale, malformed, regressed, or ambiguous.
- Every revocable token captures current issuer and account generations. Tokens containing tenant-scoped claims also capture tenant and membership generations for exactly those bounded scopes.
- After signature/profile and issued-token registry validation, a protected route requires exact equality with every current generation applicable to that route. Missing, malformed, unavailable, or regressed projection state fails closed.
- `iat` remains required for audit and lifetime validation but is not revocation ordering authority. Normal bounded clock skew applies only to wall-clock claims such as `iat`, `nbf`, and `exp`.
- Advancing the issuer generation is mandatory after environment-wide Account signing-key compromise or player-facing post-restore hardening. This is defense in depth: the affected `kid` must still be removed and validator rejection proved before protected traffic reopens.

Issuance and refresh use an explicit generation linearization boundary. Account reads the applicable authority-generation snapshot and creates the matching issued-token registry record under the same Account-owned transaction or compare-and-set fence that protects generation advancement; the record stores the exact snapshot used for the token. Account does not return a token until that record has been accepted with the matching generation. If a generation advance wins the race, the candidate issuance or refresh is rejected or retried from a fresh snapshot, and no stale registry record is exposed as usable. The same fence applies when registering a refreshed token and when advancing account, tenant, membership, or issuer authority; downstream projections may lag, but they cannot authorize a snapshot that the Account authority fence rejected.

Issuance and refresh have a durable idempotency commit point. Account binds each operation to a high-entropy `requestId` and immutable request digest, persists the exact compact JWT in protected Account durable operation state, records the operation and authority snapshot under Account ownership, and accepts the exact registry record before returning the token. The protected operation state is access-controlled and encrypted or otherwise secret-protected at rest; the compact JWT is never logged or used as a substitute for registry validation. A registry timeout or ambiguous response returns no token; retrying the same request returns the exact stored compact JWT only after Account reconciles the durable operation with the matching registry record, while a changed digest conflicts without mutation. Refresh applies the same rule to the replacement lineage: the replacement registry record is accepted before the predecessor is retired, and any orphaned replacement is explicitly aborted or retired by Account's reconciler rather than becoming usable by registry absence. A crash between registry acceptance and response is replayable by request identity, not a reason to mint a second token or infer success from a partial record.

Every generation-bearing validation must also obtain explicit authority-projection freshness evidence. The evidence fence identifies the exact authority scope, the committed Account authority generation, the source transaction or outbox/event version that established it, and the observed projection generation and status. A validator may authorize only when that evidence proves the projection represents the current committed authority for the requested scope; Redis key presence, a successful read, a timestamp, or a cached value is not freshness evidence. Missing, malformed, ambiguous, stale, regressed, or unavailable fence evidence fails closed. An unreachable registry, authority-generation source, or projection fence is a retryable `AUTH_UNAVAILABLE` / HTTP 503 condition; reachable missing, expired, revoked, mismatched, or otherwise invalid evidence is an authentication failure requiring reauthentication.

The exact shared authority-generation record and propagation shape is a dependency of a follow-on identity decision. This document defines the semantic boundary without treating any timestamp watermark as authority.

Coordination Redis outage behavior follows [ADR 0037](./decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md):

- **Control-plane APIs (HTTP/gRPC)** – Requests that require issued-token registry checks fail closed while Coordination Redis is unavailable, returning the canonical retryable `AUTH_UNAVAILABLE` error rather than silently bypassing authorization.
- **Gameplay admission (`LOGIN` / lobby selection via `PLAY`)** – New admissions fail closed while Coordination Redis is unavailable because allowlist and gameplay session binding state cannot be established reliably.
- **Already-entered gameplay sessions** – Ongoing gameplay behavior follows the Redis outage/degradation policy defined in [Redis Architecture](./system-architecture-redis.md) and [Redis Operations & Migrations](./system-architecture-redis-operations.md#canonical-coordination-reset-sequence). Game Session must not “assume authorization” in the absence of Redis; if coordination state needed to process commands safely is unavailable, it must degrade or halt according to the Redis policy instead of inventing local-only session authority.

- **Unavailable versus revoked** - Unreachable registry, authority-generation state, or authority-projection freshness fence returns retryable AUTH_UNAVAILABLE / HTTP 503 and does not tell clients to discard authentication. The UI may retain in-memory auth state for retry, but the failed operation remains denied and no cached JWT role, membership, generation, or allowlist result may authorize it. Reachable missing, deleted, expired, malformed, revoked, or mismatched authority returns AUTH_SESSION_REVOKED or the specific invalid-token outcome and requires reauthentication.

## JWT Format and Role Claims

Account Service issues the exact JWT profiles defined below for control-plane UI calls, first-party player bootstrap, and receiver-specific private player delegation. Raw gameplay protocol clients (for example Telnet clients and gameplay WebSocket command streams after the socket is open) never carry gameplay authorization JWTs. First-party gameplay web/mobile clients may temporarily hold the short-lived `player-bootstrap` token defined in [Authentication & Authorization](./system-architecture-authentication.md) for caller-bound bootstrap calls including target-state-only `POST /auth/bootstrap/join`, bootstrap-authenticated character creation, and `POST /auth/connect-token`, but that token is not sent as gameplay command auth and is not accepted by gameplay services. Control UIs may supply `control-ui` JWTs, which are validated by the consuming control-plane service. The Gateway forwards only non-consumed profiles; it does not consume or forward a profile whose route contract makes it an edge admission credential. Game Session may hold the private `game-session-account-delegation` JWT for Account calls, but gameplay-domain requests use concrete mTLS workload identity plus typed `PlayerExecutionContext`, not forwarded per-player JWT claims.

### Mandatory Receiver Predicates

Each receiving method has an explicit, conjunctive receiver predicate. A receiver must reject the request before authorization unless all applicable predicates pass:

- **Token profile and type** – The exact registered profile and its registered type must match the route. The audience is not a substitute for the profile/type.
- **Issuer** – A JWT-bearing route accepts only the exact Account issuer `firemud-account-service`; issuer validation is mandatory even when the signing key is trusted.
- **Audience** – The exact audience registered for that profile and route must match; broad audiences and cross-profile reuse are forbidden.
- **Concrete workload caller** – An internal method accepts only its declared concrete mTLS certificate identity, not a service-family label, forwarded header, or JWT subject.
- **Per-method policy** – The method's exact route entry and caller policy must allow the operation. A caller allowed for one method is not allowed for another method by inheritance.

Workload-only methods explicitly declare token profile, type, issuer, and audience as `none`; they still require the concrete mTLS caller and exact method policy. Private delegation methods require the exact profile/type, issuer, audience, concrete mTLS caller, and method policy together. Gameplay-domain methods additionally validate typed `PlayerExecutionContext` scope and do not add a JWT hot path.

### Gateway Token Forwarding Boundary

- Gateway forwards a token profile only when the route contract declares that profile non-consumed; forwarding preserves the token for the named downstream validator and does not create gameplay authority.
- A `gameplay-connect` token is validated at Gateway for signature, profile, scope, expiry, and replay, then atomically consumed and stripped before the WebSocket upgrade completes. Browser and mobile-browser clients, plus first-party cookie-jar clients, carry it only in the approved HttpOnly cookie. A dedicated handshake header is permitted only for an explicitly classified non-first-party/public native-mobile, server-side, or other non-browser route; Gateway rejects an unapproved first-party header carrier or ambiguous simultaneous carriers. Gateway forwards the resulting signed connect context, not the consumed JWT or its carrier, to Game Session.

### Claims

| Field | Description |
| --- | --- |
| `iss` | Issuer identifier for the Account Service token authority |
| `sub` | Subject claim for the authenticated account (same semantic identity as `accountId`) |
| `jti` | Unique token identifier for audit/correlation |
| `accountId` | Identity of the authenticated account |
| `aud` | Exact audience required by the token profile and receiving route |
| `iat` | Issued-at timestamp (UTC epoch seconds), required for token lifetime validation and audit chronology but not revocation ordering |
| `tokenGeneration` | Positive monotonic generation for one issued-token refresh lineage |
| `issuerAuthGeneration` | Current issuer authority generation captured at issuance |
| `accountAuthGeneration` | Current account authority generation captured at issuance |
| `tenantAuthGenerations` | Bounded map keyed by the exact tenant UUID entries in `scopedRoles`; for a private delegation, keyed only by the exact delegated binding `tenantId` |
| `membershipAuthGenerations` | Bounded map keyed by the exact tenant UUID entries in `scopedRoles`; for a private delegation, the same key denotes the Account-owned `{accountId, tenantId}` membership generation for the delegated binding |
| `nbf` | Not-before timestamp |
| `exp` | Expiration timestamp |
| `globalRoles` | Cross-tenant privileges (for example `platformAdmin`, `billingAdmin`, `support`) |
| `scopedRoles` | Map of `tenantId` -> roles (for example `"018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f": ["tenantAdmin", "designer"]`) |

### Example JWT Payload

- `iss`: `"firemud-account-service"`
- `sub`: `"018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a"`
- `jti`: `"018f8f0a-4d9e-7c46-be37-8c1d0e9f7a5b"`
- `aud`: `"control-ui"`
- `accountId`: `"018f8f0a-1a6b-7b13-8d04-5f6e7d8c9b0a"`
- `iat`: `1735689600`
- `nbf`: `1735689600`
- `exp`: `1735693200`
- `tokenGeneration`: `12`
- `issuerAuthGeneration`: `7`
- `accountAuthGeneration`: `12`
- `tenantAuthGenerations`: `{ "018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f": 4, "018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a": 9 }`
- `membershipAuthGenerations`: `{ "018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f": 18, "018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a": 3 }`
- `globalRoles`: `["billingAdmin"]`
- `scopedRoles`:
  - `"018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f"` -> `["tenantAdmin", "designer"]`
  - `"018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a"` -> `["moderator"]`

First-party `control-ui` and `player-bootstrap` JWTs are short-lived, non-gameplay control/bootstrap credentials and never become gameplay command authority. `player-bootstrap` can authorize caller-bound discovery, join, and connect-token issuance, but it remains tenant-free and does not itself carry a selected runtime target. Gameplay context (for example `characterId` and `tenantId`) lives in Game Session bindings and is sent through typed command envelopes or `PlayerExecutionContext` rather than embedded in those end-user JWT contracts. The bounded `gameplay-connect` JWT is the separate edge-admission profile: its `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `connectScopeId`, `requestId`, and `replayAdmissionFence` fields are one short-lived target and replay-readiness snapshot, consumed at Gateway, and never gameplay command authority. Receiver-specific private delegation JWTs remain backend material for their named receiver.

### Token Profiles and Audiences

To keep trust boundaries clear, FireMUD has exactly the JWT profile categories defined by [ADR 0038](./decisions/adr-0038-explicit-jwt-profiles-and-mtls-workload-identity.md):

- **`control-ui` JWTs**
  - Issued via the `/auth/login` HTTP endpoint on the Account Service after a successful login from a first-party admin/creator web UI.
  - Intended audience: `control-ui`.
  - Carried only by first-party SPAs behind the Gateway; stored in memory only and sent as `Authorization: Bearer <token>` on meta/control API calls.
  - Lifetime: short (for example 15–30 minutes) and not automatically refreshed; when a `control-ui` JWT expires or is revoked, UIs must treat this as a hard logout condition and require re-authentication.

- **Player-bootstrap JWTs**
  - Issued via the `/auth/player-bootstrap` HTTP endpoint on the Account Service as the first step of the first-party gameplay bootstrap flow, before discovery, target-state-only `POST /auth/bootstrap/join`, bootstrap-authenticated character creation, connect-token issuance, and gameplay `LOGIN`.
  - Intended audience: bootstrap-only gameplay surfaces (for example an `aud` claim exactly `player-bootstrap`).
  - Carried only by first-party gameplay SPAs or mobile clients, stored in memory only, and used only for bootstrap discovery, target-state-only `POST /auth/bootstrap/join`, bootstrap-authenticated character creation, and `POST /auth/connect-token`.
  - Lifetime: intentionally short (target <= 5 minutes). Expiry or revocation requires the first-party gameplay client to obtain a fresh bootstrap token before continuing gameplay bootstrap.
  - Full-page reload or process restart is treated the same way as token loss: the client re-enters the bootstrap flow from `POST /auth/player-bootstrap` (or an equivalent future explicit bootstrap-restoration endpoint if one is added). The architecture does not currently define a hidden refresh token or silent bootstrap-restoration mechanism.

- **Gameplay-connect JWTs**
  - Issued by Account after bootstrap discovery and current membership, entitlement, and admission checks with profile and audience `gameplay-connect`.
  - Carried exactly once to the Gateway gameplay WebSocket handshake through the approved HttpOnly cookie carrier or the explicitly classified non-first-party/public non-browser header carrier and consumed under the replay contract in [ADR 0029](./decisions/adr-0029-single-use-gameplay-connect-token-carriage.md).
  - Gateway validates and consumes this token, strips its carrier, and forwards only the signed connect context. Gameplay commands and backend services never accept it as authorization.
  - This profile is an explicit bounded revocation-freshness exception: it has no `session:auth:token:<tokenHash>` issued-token-registry record and omits `tokenGeneration`, `issuerAuthGeneration`, and `accountAuthGeneration`. Its substitute is one atomic exact-`jti` consume marker, a signed lifetime no longer than 30 seconds, configured clock skew no greater than 5 seconds, the signed/current `replayAdmissionFence`, quarantine cutoff validation, and the Gateway-owned deny marker checked atomically before consumption. A hard Account or security revocation may leave only that bounded pre-consumption window; neither the `player-bootstrap` lifetime nor `FIREMUD_AUTH_SESSION_EXPIRATION_MS=300000` extends Gateway replay or revocation freshness. This exception applies only to the route-matrix-declared Gateway gameplay-connect handshake and cannot be inherited by another JWT profile or protected route.

- **Receiver-specific private player-delegation JWTs**
  - Issued by the Account Service only for a named receiver and delegated player-binding operation. The current profile is `game-session-account-delegation`.
  - `game-session-account-delegation` is issued for Game Session's Account calls via gRPC `Authenticate` or the generation-bound refresh contract and has the exact audience `account-service`.
  - A tenant-bound private delegation has exactly one bound `tenantId` and exactly one corresponding `{accountId, tenantId}` membership-generation key. It must not carry a multi-tenant set, an unscoped tenant value, a wildcard, or a missing membership-generation entry; tenant-bound receivers reject any such shape before authorization.
  - Carried only over mTLS-protected service-to-service links. The receiver requires both the approved concrete mTLS caller identity and the exact delegated-token profile; the token does not authenticate the workload.
  - Lifetime: short-lived and backed by one `session:auth:token:<tokenHash>` registry record; services must not cache them beyond their expiry or ignore registry revocation.
  - An active gameplay binding rotates its private `game-session-account-delegation` JWT through the Account-owned refresh contract in [ADR 0031](./decisions/adr-0031-revocation-safe-session-token-rotation-and-logout.md). Refresh authority includes the still-valid per-lineage `tokenGeneration` and cannot be derived from Game Session mTLS identity plus an account ID alone.
  - Account rejects rotation when the current lineage, applicable authority generation, or private-realm `grantVersion` is blocked by account, tenant, membership, or grant revocation. A replacement with a newer `iat` must not cross a logout-all, password-reset, security-lock, membership-loss, or realm-grant cutoff.

There is no generic backend JWT profile, and the `internal` audience is forbidden. Workload-only calls use certificate-derived mTLS identity and exact per-method caller policy without a JWT. Gameplay-domain requests additionally use typed `PlayerExecutionContext` and never add per-command token validation. Services must validate both the signature and the exact expected audience/profile for incoming tokens and reject every cross-profile or wrong-receiver token before authorization. A privileged-control window is a route/session authorization condition, not a JWT profile or audience.

### Pending-Deletion Access Credential

`pending-deletion-access` is an opaque Account-issued credential, not a JWT profile and not a variant of `control-ui` or `player-bootstrap`. Account stores its hash in a separate server-side pending-deletion registry bound to `account_id`, `deletion_workflow_id`, `deletion_workflow_generation`, `action_family`, issue time, and expiry. The credential is accepted only by the matrix's `pending_deletion_scoped` status, cancel, export, and necessary billing-settlement routes. It does not carry or restore normal account-generation authority, and it cannot authenticate login, bootstrap, connect-token, tenant, purchase, or gameplay routes.

The credential is revoked on cancellation, terminal deletion, or expiry. Credential loss or expiry can be recovered only through a dedicated pending-deletion recovery challenge, which issues another pending-deletion credential and never a normal login or gameplay credential.

Privileged `platformAdmin` and cross-tenant `billingAdmin` elevation does not create another JWT profile or add elevated claims to a reusable bearer token. Account Service records a bounded server-side role-assurance window bound to the current `control-ui` token `jti`, account generation, and requested global role; the authorization route matrix defines its entry route, invalidation conditions, and role application.

### Platform-Admin Target-Tenant Assurance

When a `platformAdmin` caller uses a `tenant_regular` route or a `cross_tenant_data_bearing` route, authorization requires both the exact current target-tenant generation and an active `privileged_control` assurance backed by the configured TOTP challenge. The assurance is server-side, bound to the current `control-ui` token `jti`, account generation, requested global role, and target operation; the JWT role claim alone is insufficient. A missing, expired, mismatched, stale, or unverifiable assurance or target-tenant generation fails closed, and no selected UI tenant, global role, issuer generation, or account generation substitutes for either predicate. This requirement aligns these target-sensitive classes with ADR 0036 and the route matrix while preserving the closed omission allowlist for explicitly safe cross-tenant classes.

### JWT Claim Contract (Normative)

Services must enforce this claim contract before role/tenant authorization:

| Claim | `control-ui` JWT | `player-bootstrap` JWT | `gameplay-connect` JWT | `game-session-account-delegation` JWT | Notes |
| --- | --- | --- | --- | --- | --- |
| `iss` | Required | Required | Required | Required | Must match Account Service issuer value |
| `sub` | Required | Required | Not required | Required | Must identify the account subject where required |
| `jti` | Required | Required | Required | Required | Unique per issued token; the gameplay-connect value is the single-use replay nonce |
| `accountId` | Required | Required | Required | Required | Must be consistent with `sub` mapping where `sub` is required |
| `aud` | Required (`control-ui`) | Required (`player-bootstrap`) | Required (`gameplay-connect`) | Required (`account-service`) | Exact allowed values are centrally configured |
| `iat` | Required | Required | Required | Required | UTC epoch seconds |
| `nbf` | Required | Required | Not required | Required | Token not usable before this time when present |
| `exp` | Required | Required | Required | Required | Token unusable after this time |
| `tokenGeneration` | Required | Required | Not used | Required | Positive integer for issued-token-registry lineage; gameplay-connect instead uses its dedicated single-use replay contract |
| `issuerAuthGeneration` | Required | Required | Absent | Required | Positive monotonic Account-owned issuer generation captured at issuance |
| `accountAuthGeneration` | Required | Required | Absent | Required | Positive monotonic Account-owned account generation captured at issuance |
| `tenantAuthGenerations` | Required when `scopedRoles` is non-empty; otherwise Empty map | Empty map | Absent | Exactly one key for every tenant-bound delegation; Empty map only for an explicitly non-tenant profile | Bounded positive-generation map keyed by the exact tenant UUID entries in `scopedRoles`; a tenant-bound private delegation has no end-user `scopedRoles`, so its sole entry is keyed by the exact `tenantId` in its Account-owned delegated binding and never by account, grant, token, or wildcard scope |
| `membershipAuthGenerations` | Required when `scopedRoles` is non-empty; otherwise Empty map | Empty map | Absent | Exactly one key for every tenant-bound delegation; Empty map only for an explicitly non-tenant profile | Same sole key as `tenantAuthGenerations`; for a tenant-bound private delegation the value is the Account-owned `{accountId, tenantId}` membership generation from the bound player identity, not a tenant-wide or account-wide substitute |
| `tenantId` | Not required | Not required | Required | Not required | Gameplay-connect admission scope |
| `gameInstanceId` | Not required | Not required | Required | Not required | Server-resolved gameplay-connect runtime target |
| `worldSlug` | Not required | Not required | Required | Not required | Stable gameplay-connect world selector |
| `realmSlug` | Not required | Not required | Required | Not required | Stable gameplay-connect realm selector |
| `pointerVersion` | Not required | Not required | Required | Not required | Gameplay-connect routing-freshness fence |
| `connectScopeId` | Not required | Not required | Required | Not required | Opaque discovery scope used for issuance |
| `requestId` | Not required | Not required | Required | Not required | Connect-token issuance idempotency identity |
| `replayAdmissionFence` | Not required | Not required | Required | Not required | Exact shared replay-readiness fence observed at issuance; Gateway validates equality before authorization and repeats the check atomically during token consumption |
| `globalRoles` | Optional | Optional | Absent | Optional | Empty list when none; gameplay-connect admission never authorizes from role claims |
| `scopedRoles` | Required; `{}` when none | Empty map | Absent | Optional; generation maps align to delegated binding scope rather than this claim | Omission is rejected for `control-ui`; its generation-map keys must equal the `scopedRoles` tenant UUID keys exactly, and no generation map may introduce an unclaimed tenant |

`Required` means the claim must be present with a valid type. Positive-value constraints apply only where a generation or other numeric claim is specified to be positive; string claims must be non-empty and satisfy their exact issuer, subject, profile, or audience contract, and time claims must be valid UTC epoch-second values. `Empty map` means the claim must be present as `{}`; `Absent` means the claim must not be serialized. For `control-ui`, `scopedRoles` is required even when empty, and both tenant-generation maps are required as `{}` when it is empty and otherwise have exactly the same non-empty canonical tenant UUID keys. Omission of `scopedRoles` or either required generation map is rejected. `player-bootstrap` is tenant-free, so its tenant/membership-generation maps and `scopedRoles` are empty. A tenant-bound private player-delegation profile has no end-user role scope or `scopedRoles` key requirement, but it must carry both tenant-generation maps with exactly one shared, non-empty key derived from the exact delegated gameplay binding `{accountId, tenantId}`; the receiver rejects any extra, missing, empty, wildcard, or differently keyed entry. Only an explicitly non-tenant private-delegation profile may carry `{}` for both maps.

Tokens that omit required claims, have malformed claim types, violate these empty/absent rules, or present an unexpected `aud` for the endpoint profile must be rejected before route classification.

`tokenGeneration` is distinct from Account-owned issuer/account/tenant/membership authority generations and private-realm `grantVersion` state. It binds refresh/replacement ordering for one token lineage; it does not grant scope, replace current authority checks, or serve as restart authority by itself. Game Session uses the durable protected single-use rebind handle in the gameplay binding when a restart or takeover owner no longer has the raw private delegation JWT; Account consumes that handle and returns the next token-generation value plus replacement handle under the binding/fence protocol.

For authorization, the issued-token registry has only two structural exceptions: an explicitly public route in the route matrix whose contract requires no JWT, and the exact matrix-declared `gameplay-connect` Gateway handshake with its bounded single-use replay contract. No JWT-bearing route may turn registry absence into authorization. The logout endpoints have a separate lifecycle-only reconciliation path: after local signature/profile/time/subject validation, `AuthLogout` may return the stored result only from a durable Account pending/committed intent or tombstone for that exact token, and `AuthLogoutAll` may return the stored result only from durable Account evidence that a prior logout-all superseded the presented authority. These retries create no authorization context and cannot be reused by any other route; a current or ambiguous token without matching durable evidence remains denied.

JWT verification model (normative):

- Services validate JWTs using Account Service JWKS (asymmetric verification). Shared HMAC verification keys in downstream services are legacy-only compatibility and must not be required in player-facing environments.
- HMAC-only JWT verification is not part of the canonical contract and must not be enabled in shared or player-facing environments.
- Player-facing environments must fail startup if asymmetric JWKS verification is not configured or if HMAC-only verification is enabled.
- HMAC verification mode is allowed only for local/dev and explicitly ephemeral CI environments.
- Account Service remains authoritative for signing-generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations explicitly delegated by Account and may not validate tokens, promote a signer, publish JWKS, or prune key material.
- The canonical Kubernetes Secret baseline described in [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) gives the materialization controller sole name-scoped authority to generate and `resourceVersion`-CAS-write the private signing Secret. Account is the only application workload that mounts or uses private material and remains the sole issuer, authority-transition, session-invalidation, and JWKS-publication owner; validators receive only public JWKS, and rotation automation cannot access signing material. ADR 0014's non-exportable signer custody is a future private-key backend under the same Account authority model, not a second Kubernetes Secret writer or issuer. Shared HMAC remains limited to local/dev or explicitly ephemeral CI compatibility and is not a player-facing fallback. Every issued JWT carries a stable `kid`.
- Validators cache known keys for a configured bounded maximum age and refresh proactively. An unknown `kid` triggers one forced JWKS refresh and one validation retry, then fails closed.
- A temporary JWKS outage may not invalidate a known key whose bounded cache entry remains fresh. Validators must not extend cache age or accept an unknown key to preserve availability.

### Signing-Key Rotation Contract (Normative)

[ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) defines the accepted lifecycle. Planned rotation must prepublish a new public JWK, wait for the bounded validator-cache interval and prove validator visibility, promote the matching Account signer atomically, retain the retiring public key until the last token it signed has expired plus allowed clock skew, then prune and prove acceptance/rejection behavior. A common generation and phase must make separately updated signing and JWKS resources distinguishable; they must not be treated as an atomic multi-resource write.

Normal rotation preserves existing sessions. Signer rollback after promotion must keep public keys for every key used by either application version until all affected tokens expire plus skew.

Compromise and post-restore hardening instead quarantine JWT issuance and protected admission/control-plane traffic. Account binds signing-generation promotion, Account-owned JWKS publication, and one issuer-authority-generation advance to one durable idempotent `compromiseRotationOperationId`; a same-operation retry returns the committed generation and all rotation/convergence evidence without another advance, while a changed digest conflicts. Account removes the affected public key without overlap, forces every validator to converge, and requires proof that the old `kid` is rejected and the replacement is accepted before traffic reopens. Physical issued-token-registry and gameplay-session cleanup is a bounded best-effort follow-up after that authority gate; it must not delay the security gate or substitute for issuer invalidation, key removal, validator convergence, and rejection proof. The Account key ring is per environment rather than per tenant, so compromise of that key has environment-wide invalidation scope.

Player-facing readiness requires focused proof of both planned rotation through pruning and compromise hard cutover. Mounted signing/JWKS files, raw JWKS serving, or direct-file watcher callbacks do not independently satisfy this contract.
