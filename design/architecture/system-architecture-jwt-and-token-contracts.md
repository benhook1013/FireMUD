# FireMUD System Architecture: JWT and Token Contracts

This document defines the JWT profiles, claim requirements, allowlist entries, revocation watermarks, and token-validation behavior used by FireMUD services. It complements [Authentication & Authorization](./system-architecture-authentication.md), which defines how these token contracts are applied to route classification, gameplay admission, and tenant authorization.

Issued JWTs are allowlisted in Redis using keys `session:auth:<scope>:<tokenHash>` where `scope` encodes the authorization context and `tokenHash` is a fixed-length digest (for example, a hex-encoded SHA-256 of the JWT). This keeps key lengths bounded and avoids leaking raw token contents into key names. FireMUD standardizes the following scope formats:

- `session:auth:account:<accountId>:<tokenHash>` – account-scoped allowlist entry for a JWT. This represents “this token is currently allowed for this account” and is the baseline revocation surface for control-plane sessions.
- `session:auth:tenant:<tenantId>:<tokenHash>` – tenant-scoped allowlist entry for a JWT that is permitted to act in the regular tenant control plane and gameplay plane for a specific tenant. Services consult these entries when authorizing tenant-specific (non-billing-safe) operations based on `scopedRoles[tenantId]`.
- `session:auth:global:<accountId>:<tokenHash>` – cross-tenant allowlist entry for a JWT that carries `globalRoles` such as `platformAdmin`, `billingAdmin`, or `support`. These entries are used when authorizing cross-tenant operations that are not tied to a single `tenantId`.

JWT issuance follows these rules:

- The Account Service creates exactly one `session:auth:account:<accountId>:<tokenHash>` entry for every issued JWT.
- If a JWT contains any tenant-scoped roles, the Account Service creates one `session:auth:tenant:<tenantId>:<tokenHash>` entry per tenant in `scopedRoles`.
- If a JWT includes `globalRoles`, the Account Service creates a single `session:auth:global:<accountId>:<tokenHash>` entry in addition to the account-scoped entry.

The `session:auth:*` entries use a TTL derived from the JWT lifetime so operators do not need to tune separate “JWT” and “auth session” expiry knobs:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

## Token Validity and Revocation

Token validity semantics:

- A JWT must be cryptographically valid (signature, required claims `iss`, `sub`, `jti`, `accountId`, `aud`, `iat`, `nbf`, `exp`, and expected token profile audience) and must have a matching `session:auth:account:<accountId>:<tokenHash>` entry present in Coordination Redis.
- For regular tenant-scoped operations (gameplay admission, instance management, and non-billing-safe tenant control-plane APIs), the JWT must also have a matching `session:auth:tenant:<tenantId>:<tokenHash>` entry for the requested `tenantId`.
- For cross-tenant operations, the JWT must have a matching `session:auth:global:<accountId>:<tokenHash>` entry and the requested operation must be authorized by `globalRoles` per the Tenant Authorization Contract.
- Coordination Redis therefore acts as a server-side allowlist and immediate revocation surface: deleting `session:auth:*:<tokenHash>` revokes a still-unexpired JWT; coordination resets that drop `session:auth:*` force re-authentication for the affected scopes.
- During Coordination Redis outages, token-gated internal calls fail closed (authorization cannot be established without the allowlist check). This is an explicit availability vs security tradeoff; gameplay clients do not transmit JWTs directly, but backend calls made on their behalf still require the server-side auth-session/token entries to be present.

Bulk revocation (for example “logout all devices”, account bans, or tenant-wide billing suspensions) must not rely on wildcard deletes or key scans. Instead, the platform uses **revocation watermarks** in addition to per-token allowlist entries:

- `session:auth:revoked_after:account:<accountId>` – tokens with `iat` older than this timestamp are treated as revoked for this account, even if their allowlist entries still exist.
- `session:auth:revoked_after:tenant:<tenantId>` – tokens with `iat` older than this timestamp are treated as revoked for tenant-scoped operations targeting this tenant.
- `session:auth:revoked_after:membership:<accountId>:<tenantId>` – tokens with `iat` older than this timestamp are treated as revoked for caller-bound tenant operations for this account and tenant, even if the tenant remains otherwise available.

Revocation watermark contract requirements:

- Watermark values are UTC epoch seconds so they can be compared directly to JWT `iat` without unit conversion drift.
- Watermark keys must have TTL at least `FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` after the last relevant event so all tokens that could still be valid are covered.
- Account Service is the authoritative writer for watermark updates triggered by account-security and billing-state events.
- Account Service is also the authoritative writer for `session:auth:revoked_after:membership:<accountId>:<tenantId>` updates triggered by membership or tenant-role changes that affect caller-bound tenant authority.
- Services validating tokens should allow small bounded clock skew (for example up to 60 seconds) when comparing `iat` to wall-clock checks, but not when comparing `iat` to revocation watermark values.

Per-token logout remains a single-key delete of the token’s allowlist entries; bulk revocation uses watermarks and relies on TTL for eventual allowlist key cleanup.

Coordination Redis outage behavior must be deterministic:

- **Control-plane APIs (HTTP/gRPC)** – Requests that require allowlist checks fail closed while Coordination Redis is unavailable, returning a clear infrastructure error (for example `AUTH_UNAVAILABLE` / `SERVICE_UNAVAILABLE`) rather than silently bypassing authorization.
- **Gameplay admission (`LOGIN` / lobby selection via `PLAY`)** – New admissions fail closed while Coordination Redis is unavailable because allowlist and gameplay session binding state cannot be established reliably.
- **Already-entered gameplay sessions** – Ongoing gameplay behavior follows the Redis outage/degradation policy defined in [Redis Architecture](./system-architecture-redis.md) and [Redis Operations](./system-architecture-redis-operations.md). Game Session must not “assume authorization” in the absence of Redis; if coordination state needed to process commands safely is unavailable, it must degrade or halt according to the Redis policy instead of inventing local-only session authority.

## JWT Format and Role Claims

Internal JWTs are issued by the Account Service and used for backend gRPC authorization to auth/control-plane services and for first-party admin/creator web UIs. Raw gameplay protocol clients (for example Telnet clients and gameplay WebSocket command streams after the socket is open) never carry gameplay authorization JWTs. First-party gameplay web/mobile clients may temporarily hold the short-lived `player-bootstrap` token defined in [Authentication & Authorization](./system-architecture-authentication.md) for bootstrap calls such as `POST /auth/connect-token`, but that token is not sent as gameplay command auth and is not accepted by gameplay services. Admin UIs may supply JWTs, which are validated by the Logging & Admin Service or other admin consumers. The Gateway forwards tokens without validating them. Game Session may hold Account Service-issued JWTs for its own calls to auth/control-plane services, but gameplay-domain requests on behalf of players must use `SessionAttestation`, not forwarded per-player JWT claims.

### Claims

| Field | Description |
| --- | --- |
| `iss` | Issuer identifier for the Account Service token authority |
| `sub` | Subject claim for the authenticated account (same semantic identity as `accountId`) |
| `jti` | Unique token identifier for audit/correlation |
| `accountId` | Identity of the authenticated account |
| `aud` | Audience/profile marker used to separate Browser, player-bootstrap, and Service tokens |
| `iat` | Issued-at timestamp (UTC epoch seconds), required for revocation watermark checks |
| `nbf` | Not-before timestamp |
| `exp` | Expiration timestamp |
| `globalRoles` | Cross-tenant privileges (for example `platformAdmin`, `billingAdmin`, `support`) |
| `scopedRoles` | Map of `tenantId` -> roles (for example `"tenant-abc": ["tenantAdmin", "designer"]`) |

### Example JWT Payload

- `accountId`: `"user-123"`
- `iat`: `1735689600`
- `globalRoles`: `["billingAdmin"]`
- `scopedRoles`:
  - `"tenant-abc"` -> `["tenantAdmin", "designer"]`
  - `"tenant-def"` -> `["moderator"]`

Tokens are short-lived and internal only. Gameplay context (for example `characterId` and `tenantId`) is stored in Redis and sent via command envelopes or session attestations rather than embedded in end-user JWT contracts.

### Token Profiles and Audiences

To keep trust boundaries clear, FireMUD distinguishes between three JWT profiles:

- **Browser JWTs**
  - Issued via the `/auth/login` HTTP endpoint on the Account Service after a successful login from a first-party admin/creator web UI.
  - Intended audience: frontend/meta APIs (for example an `aud` claim such as `frontend` or `meta-ui`).
  - Carried only by first-party SPAs behind the Gateway; stored in memory only and sent as `Authorization: Bearer <token>` on meta/control API calls.
  - Lifetime: short (for example 15–30 minutes) and not automatically refreshed; when a Browser JWT expires or is revoked, UIs must treat this as a hard logout condition and require re-authentication.

- **Player-bootstrap JWTs**
  - Issued via the `/auth/player-bootstrap` HTTP endpoint on the Account Service as the first step of the first-party gameplay bootstrap flow, before discovery, connect-token issuance, and gameplay `LOGIN`.
  - Intended audience: bootstrap-only gameplay surfaces (for example an `aud` claim exactly `player-bootstrap`).
  - Carried only by first-party gameplay SPAs or mobile clients, stored in memory only, and used only for bootstrap discovery and `POST /auth/connect-token`.
  - Lifetime: intentionally short (target <= 5 minutes). Expiry or revocation requires the first-party gameplay client to obtain a fresh bootstrap token before continuing gameplay bootstrap.
  - Full-page reload or process restart is treated the same way as token loss: the client re-enters the bootstrap flow from `POST /auth/player-bootstrap` (or an equivalent future explicit bootstrap-restoration endpoint if one is added). The architecture does not currently define a hidden refresh token or silent bootstrap-restoration mechanism.

- **Service JWTs**
  - Issued by the Account Service for backend callers (for example, Game Session, Logging & Admin, Game Design) via the gRPC `Authenticate` or equivalent internal flows.
  - Intended audience: internal services (for example an `aud` claim such as `internal`).
  - Carried only over mTLS-protected service-to-service links.
  - Lifetime: also short-lived and backed by `session:auth:*` allowlist entries; services must not cache them beyond their expiry or ignore allowlist revocation.

Services must validate both the signature and the expected audience/profile for incoming tokens and reject tokens with an unexpected `aud` (for example, a Browser JWT presented to a purely internal service endpoint that only accepts Service JWTs, or a player-bootstrap JWT presented to an admin API).

### JWT Claim Contract (Normative)

Services must enforce this claim contract before role/tenant authorization:

| Claim | Browser JWT | Player-bootstrap JWT | Service JWT | Notes |
| --- | --- | --- | --- | --- |
| `iss` | Required | Required | Required | Must match Account Service issuer value |
| `sub` | Required | Required | Required | Must identify the account subject |
| `jti` | Required | Required | Required | Unique per issued token |
| `accountId` | Required | Required | Required | Must be consistent with `sub` mapping |
| `aud` | Required (`frontend` / `meta-ui`) | Required (`player-bootstrap`) | Required (`internal`) | Exact allowed values are centrally configured |
| `iat` | Required | Required | Required | UTC epoch seconds |
| `nbf` | Required | Required | Required | Token not usable before this time |
| `exp` | Required | Required | Required | Token unusable after this time |
| `globalRoles` | Optional | Optional | Optional | Empty list when none |
| `scopedRoles` | Optional | Optional | Optional | Empty map when none |

Tokens that omit required claims, have malformed claim types, or present an unexpected `aud` for the endpoint profile must be rejected before route classification.

JWT verification model (normative):

- Services validate JWTs using Account Service JWKS (asymmetric verification). Shared HMAC verification keys in downstream services are legacy-only compatibility and must not be required in player-facing environments.
- HMAC-only JWT verification is not part of the canonical contract and must not be enabled in shared or player-facing environments.
- Player-facing environments must fail startup if asymmetric JWKS verification is not configured or if HMAC-only verification is enabled.
- HMAC verification mode is allowed only for local/dev and explicitly ephemeral CI environments.
