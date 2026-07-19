# FireMUD System Architecture: JWT and Token Contracts

This document defines the JWT profiles, claim requirements, issued-token registry, revocation generations, and token-validation behavior used by FireMUD services. It complements [Authentication & Authorization](./system-architecture-authentication.md), which defines how these token contracts are applied to route classification, gameplay admission, and tenant authorization.

Each revocable Browser, player-bootstrap, or private Service JWT has exactly one Account-owned Coordination Redis record: `session:auth:token:<tokenHash>`.

`tokenHash` is a fixed-length SHA-256 digest of the complete compact JWT. The bounded versioned record contains `accountId`, exact token profile/audience, `jti`, `iat`, `exp`, issuance/refresh generation, and active state. It proves that Account issued this exact still-active token but does not duplicate tenant/global roles from its signed claims. Account creates the record before returning the token; registration failure means issuance failure.

The record uses an absolute TTL derived from the JWT expiry so operators do not tune separate JWT and auth-session expiry knobs:

- `session_expiration_ms = FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`

JWT lifetime and the session safety margin are documented in [Environment & Secrets](./infrastructure/environment-and-secrets.md#authentication).

## Token Validity and Revocation

Token validity semantics:

- A JWT must be cryptographically valid (signature, required identity, profile, time, and auth-generation claims) and must have one matching `session:auth:token:<tokenHash>` record in Coordination Redis whose account, profile, `jti`, generation, and time fields agree with the verified claims.
- For tenant or cross-tenant operations, the requested operation must then be authorized from the validated `scopedRoles` or `globalRoles` claims plus the applicable current Account-owned auth generations. The issued-token record does not grant scope independently.
- Coordination Redis therefore acts as a server-side issued-token registry and immediate per-token revocation surface: deleting the one record revokes a still-unexpired JWT; coordination resets that drop `session:auth:*` force re-authentication.
- The single-use connect token and Gateway signed connect context use their separate bounded replay/verification contracts and do not create Account issued-token records.
- During token-authority outages, protected control-plane and admission calls fail closed because authorization cannot be established without the registry and applicable generation checks. This is an explicit availability/security tradeoff; ordinary gameplay commands retain their separate bounded authority-lease contract and do not acquire token lookups.

Bulk revocation (for example “logout all devices”, account bans, membership loss, or tenant-wide billing suspensions) must not rely on wildcard deletes, key scans, or wall-clock ordering. [ADR 0036](./decisions/adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) defines Account-owned positive monotonic generations:

- `session:auth:generation:issuer:<issuerId>` – environment-wide Account issuer authority, advanced for signing-key compromise, post-restore trust reset, or another explicitly global issuer event.
- `session:auth:generation:account:<accountId>` – account-wide authority, advanced for logout-all, security lock, password reset, or another account-wide cutoff.
- `session:auth:generation:tenant:<tenantId>` – regular tenant authority, advanced for tenant-wide gameplay/billing cutoff.
- `session:auth:generation:membership:<accountId>:<tenantId>` – caller-bound tenant authority, advanced when membership or tenant roles change.

Revocation-generation contract requirements:

- Account Service owns durable current generations and is the sole writer of their Coordination Redis projections.
- Account advances the applicable durable generation in the same transaction as the authority change and monotonic outbox event. Projection is idempotent set-if-greater and cannot regress.
- Every revocable token captures current issuer and account generations. Tokens containing tenant-scoped claims also capture tenant and membership generations for exactly those bounded scopes.
- After signature/profile and issued-token registry validation, a protected route requires exact equality with every current generation applicable to that route. Missing, malformed, unavailable, or regressed projection state fails closed.
- `iat` remains required for audit and lifetime validation but is not revocation ordering authority. Normal bounded clock skew applies only to wall-clock claims such as `iat`, `nbf`, and `exp`.
- Advancing the issuer generation is mandatory after environment-wide Account signing-key compromise or player-facing post-restore hardening. This is defense in depth: the affected `kid` must still be removed and validator rejection proved before protected traffic reopens.

Per-token logout is a single-key delete of the token record; bulk revocation advances generations and relies on TTL for eventual registry cleanup.

Coordination Redis outage behavior follows [ADR 0037](./decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md):

- **Unavailable versus revoked** – Unreachable registry/generation state returns retryable `AUTH_UNAVAILABLE` / HTTP `503` and does not tell clients to discard authentication. Reachable missing, deleted, expired, malformed, or mismatched authority returns `AUTH_SESSION_REVOKED` or the specific invalid-token outcome and requires reauthentication. Reset-lost records are missing authority, not a grace path.
- **Token issuance and control plane** – Account exposes no token whose registry and generation state could not be established. Login/bootstrap issuance, refresh/rotation, and every protected control-plane request fail closed during authority unavailability; sensitive admin, billing, support, and payment operations receive no stale-authority exception.
- **Gameplay admission** – New login, join, `PLAY`, reconnect/rebind, and other admission transitions fail closed when their required token, generation, membership, or gameplay-binding authority cannot be established.
- **Registry-only outage with healthy gameplay coordination** – Ordinary gameplay commands perform no registry/generation lookup. An already-admitted binding may continue only through its last successfully renewed ADR 0030 authority-freshness lease, never renews from stale state, and terminates if authority cannot be re-established by the 60-second maximum.
- **Complete Coordination Redis outage** – Game Session does not execute gameplay work whose session state, queues, locks, leases, or tick coordination cannot be established. Existing bounded transport recovery/close behavior may retain the socket temporarily but grants no local-only gameplay authority.

## JWT Format and Role Claims

Internal JWTs are issued by the Account Service and used for backend gRPC authorization to auth/control-plane services and for first-party admin/creator web UIs. Raw gameplay protocol clients (for example Telnet clients and gameplay WebSocket command streams after the socket is open) never carry gameplay authorization JWTs. First-party gameplay web/mobile clients may temporarily hold the short-lived `player-bootstrap` token defined in [Authentication & Authorization](./system-architecture-authentication.md) for bootstrap calls such as `POST /auth/connect-token`, but that token is not sent as gameplay command auth and is not accepted by gameplay services. Admin UIs may supply JWTs, which are validated by the Logging & Admin Service or other admin consumers. The Gateway forwards tokens without validating them. Game Session may hold Account Service-issued JWTs for its own calls to auth/control-plane services, but gameplay-domain requests use concrete mTLS workload identity plus typed `PlayerExecutionContext`, not forwarded per-player JWT claims.

### Claims

| Field | Description |
| --- | --- |
| `iss` | Issuer identifier for the Account Service token authority |
| `sub` | Subject claim for the authenticated account (same semantic identity as `accountId`) |
| `jti` | Unique token identifier for audit/correlation |
| `accountId` | Identity of the authenticated account |
| `aud` | Audience/profile marker used to separate Browser, player-bootstrap, and Service tokens |
| `iat` | Issued-at timestamp (UTC epoch seconds), required for audit and token lifetime validation but not revocation ordering |
| `issuerAuthGeneration` | Current issuer generation captured at issuance |
| `accountAuthGeneration` | Current account generation captured at issuance |
| `tenantAuthGenerations` | Bounded map aligned exactly with tenant entries in `scopedRoles` |
| `membershipAuthGenerations` | Bounded map aligned exactly with tenant entries in `scopedRoles` |
| `nbf` | Not-before timestamp |
| `exp` | Expiration timestamp |
| `globalRoles` | Cross-tenant privileges (for example `platformAdmin`, `billingAdmin`, `support`) |
| `scopedRoles` | Map of `tenantId` -> roles (for example `"tenant-abc": ["tenantAdmin", "designer"]`) |

### Example JWT Payload

- `accountId`: `"user-123"`
- `iat`: `1735689600`
- `issuerAuthGeneration`: `7`
- `accountAuthGeneration`: `12`
- `globalRoles`: `["billingAdmin"]`
- `scopedRoles`:
  - `"tenant-abc"` -> `["tenantAdmin", "designer"]`
  - `"tenant-def"` -> `["moderator"]`
- `tenantAuthGenerations`: `{ "tenant-abc": 4, "tenant-def": 9 }`
- `membershipAuthGenerations`: `{ "tenant-abc": 18, "tenant-def": 3 }`

Tokens are short-lived and internal only. Gameplay context (for example `characterId` and `tenantId`) is stored in Redis and sent through typed command envelopes or `PlayerExecutionContext` rather than embedded in end-user JWT contracts.

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
  - Lifetime: also short-lived and backed by one `session:auth:token:<tokenHash>` registry record; services must not cache them beyond their expiry or ignore registry revocation.
  - An active gameplay binding rotates its private Account/control-plane Service JWT through the Account-owned refresh contract in ADR 0031. Refresh authority includes the still-valid current token generation and cannot be derived from Game Session mTLS identity plus an account ID alone.
  - Account rejects rotation when an issuer, account, tenant, or membership generation no longer matches. A replacement with a newer `iat` must not cross logout-all, password reset, security lock, tenant cutoff, or membership loss.

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
| `issuerAuthGeneration` | Required | Required | Required | Positive monotonic Account-owned issuer generation |
| `accountAuthGeneration` | Required | Required | Required | Positive monotonic Account-owned account generation |
| `tenantAuthGenerations` | As scoped | As scoped | As scoped | Keys must exactly match relevant bounded `scopedRoles` entries |
| `membershipAuthGenerations` | As scoped | As scoped | As scoped | Keys must exactly match relevant bounded `scopedRoles` entries |
| `globalRoles` | Optional | Optional | Optional | Empty list when none |
| `scopedRoles` | Optional | Optional | Optional | Empty map when none |

Tokens that omit required claims, have malformed claim types, or present an unexpected `aud` for the endpoint profile must be rejected before route classification.

JWT verification model (normative):

- Services validate JWTs using Account Service JWKS (asymmetric verification). Shared HMAC verification keys in downstream services are legacy-only compatibility and must not be required in player-facing environments.
- HMAC-only JWT verification is not part of the canonical contract and must not be enabled in shared or player-facing environments.
- Player-facing environments must fail startup if asymmetric JWKS verification is not configured or if HMAC-only verification is enabled.
- HMAC verification mode is allowed only for local/dev and explicitly ephemeral CI environments.
- Account Service is the only application workload that may receive the Account JWT private key. Every issued JWT carries a stable `kid`; validating services receive only public JWKS.
- Validators cache known keys for a configured bounded maximum age and refresh proactively. An unknown `kid` triggers one forced JWKS refresh and one validation retry, then fails closed.
- A temporary JWKS outage may not invalidate a known key whose bounded cache entry remains fresh. Validators must not extend cache age or accept an unknown key to preserve availability.

### Signing-Key Rotation Contract (Normative)

[ADR 0014](./decisions/adr-0014-phased-jwt-signing-key-rotation-and-readiness.md) defines the accepted lifecycle. Planned rotation must prepublish a new public JWK, wait for the bounded validator-cache interval and prove validator visibility, promote the matching Account signer atomically, retain the retiring public key until the last token it signed has expired plus allowed clock skew, then prune and prove acceptance/rejection behavior. A common generation and phase must make separately updated signing and JWKS resources distinguishable; they must not be treated as an atomic multi-resource write.

Normal rotation preserves existing sessions. Signer rollback after promotion must keep public keys for every key used by either application version until all affected tokens expire plus skew.

Compromise and post-restore hardening instead quarantine JWT issuance and protected admission/control-plane traffic, remove the affected public key without overlap, advance the issuer auth generation, force every validator to converge, and require proof that the old `kid` is rejected and the replacement is accepted before traffic reopens. The Account key ring is per environment rather than per tenant, so compromise of that key has environment-wide invalidation scope.

Player-facing readiness requires focused proof of both planned rotation through pruning and compromise hard cutover. Mounted signing/JWKS files, raw JWKS serving, or direct-file watcher callbacks do not independently satisfy this contract.
