# FireMUD System Architecture: JWT and Token Contracts

This document defines the JWT profiles, claim requirements, issued-token registry, Account authority generations, and token-validation behavior used by FireMUD services. It complements [Authentication & Authorization](./system-architecture-authentication.md), which defines how these token contracts are applied to route classification, gameplay admission, and tenant authorization.

Each revocable `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT has exactly one Account-owned Coordination Redis record: `session:auth:token:<tokenHash>`.

`tokenHash` is a fixed-length SHA-256 digest of the complete compact JWT. The bounded versioned record contains `accountId`, exact token profile/audience, `jti`, `iat`, `exp`, the JWT's `tokenGeneration`, active state, and the applicable Account-owned `issuerGeneration`, `accountAuthorityGeneration`, `tenantAuthorityGeneration`, `{accountId, tenantId}` `membershipAuthorityGeneration`, and grant-gated private-realm `grantVersion` snapshot. Non-applicable scope generations and grant versions are absent rather than wildcard values. It proves that Account issued this exact still-active token but does not duplicate tenant/global roles from its signed claims. Account creates the record before returning the token; registration failure means issuance failure.

The record's absolute cleanup deadline is derived from that token's own JWT `exp` claim plus the cleanup margin; it is not derived from a global session lifetime:

- `registry_ttl_ms = max(0, token_exp_ms - now_ms) + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

## Implementation Status

This document defines target-state token and revocation behavior. The current runtime has no complete issued-token registry or Account-owned authority-generation issuance, advancement, propagation, and validation path, and validators still use shared-HMAC verification rather than Account JWKS. The first implemented authority-generation path must prove that issuance and refresh cannot cross a concurrent generation advance and that every affected route rejects stale generations; no such runtime proof is currently claimed.

## Token Validity and Revocation

For revocable JWT profiles, token validity semantics are:

- A revocable JWT must be cryptographically valid (signature, required claims `iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, `tokenGeneration`, and expected token profile audience) and must have one matching `session:auth:token:<tokenHash>` record in Coordination Redis whose account, profile, `jti`, `tokenGeneration`, and time fields agree with the verified claims.
- The matching registry snapshot must compare every applicable issuer/account/tenant/membership authority generation and private-realm `grantVersion` with current Account-owned state and fail closed on any mismatch. `iat` remains required for chronology, bounded clock-skew handling, and audit, but is not an authorization or revocation authority and cannot replace generation or grant-version comparison.
- For tenant or cross-tenant operations, the requested operation must then be authorized from the validated `scopedRoles` or `globalRoles` claims plus the applicable Account-owned authority-generation/version state. The issued-token record does not grant scope independently.
- Coordination Redis therefore acts as a server-side issued-token registry and immediate per-token revocation surface: deleting the one record revokes a still-unexpired JWT; coordination resets that drop `session:auth:*` force re-authentication.
- The single-use connect token and Gateway signed connect context use their separate bounded replay/verification contracts and do not create Account issued-token records.
- During Coordination Redis outages, routes explicitly gated by a revocable JWT registry check fail closed (authorization cannot be established without that registry check). Ordinary gameplay RPCs are not registry-gated player-JWT calls: they use authenticated mTLS workload identity plus the typed `PlayerExecutionContext` and do not require a player JWT registry record. This is an explicit availability-versus-security boundary, not permission to invent local-only authority for routes that do require the registry.

Bulk revocation (for example “logout all devices”, account bans, or tenant-wide billing suspensions) must not rely on wildcard deletes, key scans, or JWT timestamps. Instead, the platform uses **monotonic authority generations** in addition to per-token registry records:

- The issuer authority generation covers the environment-wide Account issuer and advances for signing-key compromise or post-restore trust reset.
- The account authority generation covers account-wide security cutoffs such as logout-all, password reset, and security lock.
- The tenant authority generation covers tenant-wide billing or security cutoffs for tenant-scoped operations.
- The membership authority generation covers `{accountId, tenantId}` membership or tenant-role changes that affect caller-bound tenant authority.

Authority-generation contract requirements:

- Each authority generation is a positive monotonic value owned and advanced by Account. It is not a timestamp, is not derived from JWT `iat`, and is compared as a generation value.
- Issuance, rotation, and route validation must use the applicable current authority generations. Advancing a generation invalidates earlier authority at that scope even when individual token registry records still exist.
- Account atomically commits the relevant durable event and authority-generation advance, then publishes the resulting authority state. Downstream services request changes through Account-owned contracts and must not write authority state directly.
- Per-token logout is a single-key delete of the token record. Bulk revocation uses authority generations; bounded background cleanup may remove older registry records but is not required for correctness.
- Issuer-generation compromise handling still requires removing the compromised `kid`, forcing validator convergence, and proving rejection before protected traffic reopens. A fresh or future `iat` cannot bypass an issuer authority-generation advance.

The exact shared authority-generation record and propagation shape is a dependency of a follow-on identity decision. This document defines the semantic boundary without treating any timestamp watermark as authority.

Coordination Redis outage behavior must be deterministic:

- **Control-plane APIs (HTTP/gRPC)** – Requests that require issued-token registry checks fail closed while Coordination Redis is unavailable, returning a clear infrastructure error (for example `AUTH_UNAVAILABLE` / `SERVICE_UNAVAILABLE`) rather than silently bypassing authorization.
- **Gameplay admission (`LOGIN` / lobby selection via `PLAY`)** – New admissions fail closed while Coordination Redis is unavailable because allowlist and gameplay session binding state cannot be established reliably.
- **Already-entered gameplay sessions** – Ongoing gameplay behavior follows the Redis outage/degradation policy defined in [Redis Architecture](./system-architecture-redis.md) and [Redis Operations](./system-architecture-redis-operations.md). Game Session must not “assume authorization” in the absence of Redis; if coordination state needed to process commands safely is unavailable, it must degrade or halt according to the Redis policy instead of inventing local-only session authority.

## JWT Format and Role Claims

Account Service issues the exact JWT profiles defined below for control-plane UI calls, first-party player bootstrap, and receiver-specific private player delegation. Raw gameplay protocol clients (for example Telnet clients and gameplay WebSocket command streams after the socket is open) never carry gameplay authorization JWTs. First-party gameplay web/mobile clients may temporarily hold the short-lived `player-bootstrap` token defined in [Authentication & Authorization](./system-architecture-authentication.md) for bootstrap calls such as `POST /auth/connect-token`, but that token is not sent as gameplay command auth and is not accepted by gameplay services. Control UIs may supply `control-ui` JWTs, which are validated by the consuming control-plane service. The Gateway forwards only non-consumed profiles; it does not consume or forward a profile whose route contract makes it an edge admission credential. Game Session may hold the private `game-session-account-delegation` JWT for Account calls, but gameplay-domain requests use concrete mTLS workload identity plus typed `PlayerExecutionContext`, not forwarded per-player JWT claims.

### Gateway Token Forwarding Boundary

- Gateway forwards a token profile only when the route contract declares that profile non-consumed; forwarding preserves the token for the named downstream validator and does not create gameplay authority.
- A `gameplay-connect` token is validated at Gateway for signature, profile, scope, expiry, and replay, then atomically consumed and stripped before the WebSocket upgrade completes. Browser clients carry it only in the approved HttpOnly cookie. A dedicated handshake header is permitted only for an explicitly approved non-browser/server route; Gateway rejects an unapproved browser/header carrier or ambiguous simultaneous carriers. Gateway forwards the resulting signed connect context, not the consumed JWT or its carrier, to Game Session.

### Claims

| Field | Description |
| --- | --- |
| `iss` | Issuer identifier for the Account Service token authority |
| `sub` | Subject claim for the authenticated account (same semantic identity as `accountId`) |
| `jti` | Unique token identifier for audit/correlation |
| `accountId` | Identity of the authenticated account |
| `aud` | Exact audience required by the token profile and receiving route |
| `iat` | Issued-at timestamp (UTC epoch seconds), used for token lifetime and audit chronology only |
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
- `globalRoles`: `["billingAdmin"]`
- `scopedRoles`:
  - `"018f8f0a-2b7c-7a24-9c15-6a9b8c7d6e5f"` -> `["tenantAdmin", "designer"]`
  - `"018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a"` -> `["moderator"]`

First-party `control-ui` and `player-bootstrap` JWTs are short-lived bootstrap/control credentials and never become gameplay command authority. Gameplay context (for example `characterId` and `tenantId`) lives in Game Session bindings and is sent through typed command envelopes or `PlayerExecutionContext` rather than embedded in those end-user JWT contracts. The one-use `gameplay-connect` JWT ends at Gateway, and receiver-specific private delegation JWTs remain backend material for their named receiver.

### Token Profiles and Audiences

To keep trust boundaries clear, FireMUD has exactly these JWT profile categories:

- **`control-ui` JWTs**
  - Issued via the `/auth/login` HTTP endpoint on the Account Service after a successful login from a first-party admin/creator web UI.
  - Intended audience: `control-ui`.
  - Carried only by first-party SPAs behind the Gateway; stored in memory only and sent as `Authorization: Bearer <token>` on meta/control API calls.
  - Lifetime: short (for example 15–30 minutes) and not automatically refreshed; when a `control-ui` JWT expires or is revoked, UIs must treat this as a hard logout condition and require re-authentication.

- **Player-bootstrap JWTs**
  - Issued via the `/auth/player-bootstrap` HTTP endpoint on the Account Service as the first step of the first-party gameplay bootstrap flow, before discovery, connect-token issuance, and gameplay `LOGIN`.
  - Intended audience: bootstrap-only gameplay surfaces (for example an `aud` claim exactly `player-bootstrap`).
  - Carried only by first-party gameplay SPAs or mobile clients, stored in memory only, and used only for bootstrap discovery and `POST /auth/connect-token`.
  - Lifetime: intentionally short (target <= 5 minutes). Expiry or revocation requires the first-party gameplay client to obtain a fresh bootstrap token before continuing gameplay bootstrap.
  - Full-page reload or process restart is treated the same way as token loss: the client re-enters the bootstrap flow from `POST /auth/player-bootstrap` (or an equivalent future explicit bootstrap-restoration endpoint if one is added). The architecture does not currently define a hidden refresh token or silent bootstrap-restoration mechanism.

- **Gameplay-connect JWTs**
  - Issued by Account after bootstrap discovery and current membership, entitlement, and admission checks with profile and audience `gameplay-connect`.
  - Carried exactly once to the Gateway gameplay WebSocket handshake through the approved header or HttpOnly cookie carrier and consumed under the replay contract in ADR 0029.
  - Gateway validates and consumes this token, strips its carrier, and forwards only the signed connect context. Gameplay commands and backend services never accept it as authorization.

- **Receiver-specific private player-delegation JWTs**
  - Issued by the Account Service only for a named receiver and delegated player-binding operation. The current profile is `game-session-account-delegation`.
  - `game-session-account-delegation` is issued for Game Session's Account calls via gRPC `Authenticate` or the generation-bound refresh contract and has the exact audience `account-service`.
  - Carried only over mTLS-protected service-to-service links.
  - Lifetime: short-lived and backed by one `session:auth:token:<tokenHash>` registry record; services must not cache them beyond their expiry or ignore registry revocation.
  - An active gameplay binding rotates its private `game-session-account-delegation` JWT through the Account-owned refresh contract in ADR 0031. Refresh authority includes the still-valid per-lineage `tokenGeneration` and cannot be derived from Game Session mTLS identity plus an account ID alone.
  - Account rejects rotation when the current lineage, applicable authority generation, or private-realm `grantVersion` is blocked by account, tenant, membership, or grant revocation. A replacement with a newer `iat` must not cross a logout-all, password-reset, security-lock, membership-loss, or realm-grant cutoff.

There is no generic backend JWT profile, and the `internal` audience is forbidden. Services must validate both the signature and the exact expected audience/profile for incoming tokens and reject an unexpected `aud` (for example, a `control-ui` JWT presented to a player-bootstrap surface or a `player-bootstrap` JWT presented to an admin API). A privileged-control window is a route/session authorization condition, not a JWT profile or audience.

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
| `tenantId` | Not required | Not required | Required | Not required | Gameplay-connect admission scope |
| `gameInstanceId` | Not required | Not required | Required | Not required | Server-resolved gameplay-connect runtime target |
| `worldSlug` | Not required | Not required | Required | Not required | Stable gameplay-connect world selector |
| `realmSlug` | Not required | Not required | Required | Not required | Stable gameplay-connect realm selector |
| `pointerVersion` | Not required | Not required | Required | Not required | Gameplay-connect routing-freshness fence |
| `connectScopeId` | Not required | Not required | Required | Not required | Opaque discovery scope used for issuance |
| `requestId` | Not required | Not required | Required | Not required | Connect-token issuance idempotency identity |
| `globalRoles` | Optional | Optional | Not used | Optional | Empty list when none; gameplay-connect admission never authorizes from role claims |
| `scopedRoles` | Optional | Optional | Not used | Optional | Empty map when none; gameplay-connect admission never authorizes from role claims |

Tokens that omit required claims, have malformed claim types, or present an unexpected `aud` for the endpoint profile must be rejected before route classification.

`tokenGeneration` is distinct from Account-owned issuer/account/tenant/membership authority generations and private-realm `grantVersion` state. It binds refresh/replacement ordering for one token lineage; it does not grant scope or replace current authority checks.

The only registry-absence exceptions are evidence-backed no-op retry classifications on the two logout endpoints. `AuthLogout` may return idempotent success after full local signature/profile/time/subject validation only when a durable Account `COMMITTED` tombstone proves that exact presented token was previously revoked; registry absence alone is insufficient, and the retry creates no authorization context or additional mutation. `AuthLogoutAll` may return idempotent success without normal registry authorization only when durable Account authority proves a prior logout-all already superseded the presented token. A current or ambiguous token without matching registry state remains denied. These exceptions cannot be reused by any other route.

JWT verification model (normative):

- Services validate JWTs using Account Service JWKS (asymmetric verification). Shared HMAC verification keys in downstream services are legacy-only compatibility and must not be required in player-facing environments.
- HMAC-only JWT verification is not part of the canonical contract and must not be enabled in shared or player-facing environments.
- Player-facing environments must fail startup if asymmetric JWKS verification is not configured or if HMAC-only verification is enabled.
- HMAC verification mode is allowed only for local/dev and explicitly ephemeral CI environments.
- Account Service remains authoritative for signing-generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations explicitly delegated by Account and may not validate tokens, promote a signer, publish JWKS, or prune key material.
- Target-state private-key custody delegates private-key operations to a non-exportable signer in every environment. Until that capability is implemented, the controlled fallback is the materialization-controller-written, Account-consumed Kubernetes Secret baseline described in [ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md): Account is the only application workload that mounts or uses private material, the controller is a narrowly scoped infrastructure custodian for generation and Secret CAS only, validators receive only public JWKS, and rotation automation cannot access signing material. Shared HMAC remains limited to local/dev or explicitly ephemeral CI compatibility and is not a player-facing fallback. Every issued JWT carries a stable `kid`.
- Validators cache known keys for a configured bounded maximum age and refresh proactively. An unknown `kid` triggers one forced JWKS refresh and one validation retry, then fails closed.
- A temporary JWKS outage may not invalidate a known key whose bounded cache entry remains fresh. Validators must not extend cache age or accept an unknown key to preserve availability.

### Signing-Key Rotation Contract (Normative)

[ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) defines the accepted lifecycle. Planned rotation must prepublish a new public JWK, wait for the bounded validator-cache interval and prove validator visibility, promote the matching Account signer atomically, retain the retiring public key until the last token it signed has expired plus allowed clock skew, then prune and prove acceptance/rejection behavior. A common generation and phase must make separately updated signing and JWKS resources distinguishable; they must not be treated as an atomic multi-resource write.

Normal rotation preserves existing sessions. Signer rollback after promotion must keep public keys for every key used by either application version until all affected tokens expire plus skew.

Compromise and post-restore hardening instead quarantine JWT issuance and protected admission/control-plane traffic, remove the affected public key without overlap, advance the issuer authority generation, force every validator to converge, and require proof that the old `kid` is rejected and the replacement is accepted before traffic reopens. The Account key ring is per environment rather than per tenant, so compromise of that key has environment-wide invalidation scope.

Player-facing readiness requires focused proof of both planned rotation through pruning and compromise hard cutover. Mounted signing/JWKS files, raw JWKS serving, or direct-file watcher callbacks do not independently satisfy this contract.
