# FireMUD System Architecture: Session Behavior

This document defines gameplay takeover, reconnect, token refresh, membership-version handling, and control-plane logout behavior. It complements [Authentication & Authorization](./system-architecture-authentication.md), which defines the end-to-end authn/authz model and gameplay admission flow.

## Multi-Client Behavior and Session Takeover

Each gameplay identity can only be controlled by one session at a time, keyed by `{tenantId, gameInstanceId, characterId}`.

If a new login is received for the same active uniqueness key:

- The existing session is terminated.
- The Redis session is rebound to the new socket.
- Tick state, command queues, and timers are preserved.

This enables:

- Clean device handoff.
- Forced logins (for example "kick and take over").
- Session continuity with at-most-once edge delivery semantics. In-flight command loss at disconnect boundaries remains possible.

All session rebinding is enforced by the Game Session Service using Redis locks. See [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).

## Mid-Session Role Updates

If roles change during an active session (for example, a player is promoted to admin):

1. The Game Session Service detects or requests a role refresh.
2. It contacts the Account Service to obtain a new JWT.
3. Updated token context is used for Game Session's subsequent calls to auth/control-plane services; gameplay-domain gRPC calls continue to use refreshed `SessionAttestation` derived from the current authoritative session state rather than forwarding per-player JWT claims downstream.

The Game Session Service exposes `/sessions/{sessionId}/refresh-roles` for manual refreshes. Implementations must ensure the effective claims injected into subsequent backend calls reflect the latest role assignments without requiring players to re-login.

This process is invisible to the client; no re-login is needed.

Membership changes that affect tenant access follow a stricter contract than ordinary role refresh:

1. The Account Service emits a membership-change event containing `accountId`, `tenantId`, `membershipVersion`, the changed role set, and whether gameplay admission remains allowed.
2. Game Session compares the event against active gameplay bindings for `{accountId, tenantId}`.
3. Losing tenant membership or losing gameplay-admission authority (for example removal of `player`) immediately revokes gameplay sessions for that tenant.
4. For caller-bound tenant control-plane access, the Account Service must also advance `session:auth:revoked_after:membership:<accountId>:<tenantId>` when membership or tenant-role changes invalidate previously issued tenant authority for that caller.
5. Non-gameplay role changes may be handled by in-session token refresh for gameplay state, but reconnect/resume must compare current membership authority to the stored `membershipVersion` before restoring gameplay.
6. `PLAY` and reconnect/resume must obtain `membershipVersion` from authoritative membership reads rather than inferring it from JWT claims or local caches.

Membership-change event delivery semantics are required, not best-effort folklore:

- Every event must include a stable `eventId` plus the tuple `{accountId, tenantId, membershipVersion}`.
- `membershipVersion` must be monotonic per `{accountId, tenantId}` and must advance on any role or membership change that can affect gameplay or tenant-safe control-plane authority.
- Consumers must treat duplicate or older versions as no-ops.
- If Game Session detects a version gap or has no prior version for an active binding, it must reconcile immediately via the authoritative internal membership API before deciding whether the session remains valid.
- Account Service owns the version increment rules; other services must not synthesize membership versions locally.

## Session and Identity Management

FireMUD deliberately distinguishes between several types of sessions so that identity, gameplay continuity, and auth token lifetimes can evolve independently:

- **Auth token sessions** – Represented by `session:auth:<scope>:<tokenHash>` entries in Coordination Redis, backing internal JWTs used for meta/control APIs.
- **Bootstrap transport session contexts** – Current Game Session implementations store pre-auth socket context under the `sessionctx:*` key family. These records may exist before `LOGIN`, may have no account or membership authority, and are used only for bootstrap scope, locale, and reconnect lookup plumbing.
- **Gameplay sessions** – Tenant-scoped bindings between a connected socket (or reconnect token) and a character in a specific tenant, backed by gameplay Redis keys.
- **Control-plane UI sessions** – Browser or desktop admin/creator sessions that hold short-lived JWTs client-side and rely on auth token sessions on the server.

The Game Session Service is responsible for:

- Authenticating sockets and binding identity context.
- Managing gameplay Redis session state such as `characterId`, `tenantId`, and tick region.
- Managing JWTs for backend interactions.

### Session Types and Lifetimes

FireMUD uses distinct lifetimes and invariants for each session type:

- **Auth token allowlist entries**
  - Keys: `session:auth:<scope>:<tokenHash>` on Coordination Redis, where `<scope>` is one of:
    - `account:<accountId>` for the baseline session allowlist.
    - `tenant:<tenantId>` for regular tenant-scoped operations and gameplay admission.
    - `global:<accountId>` for cross-tenant and global-role operations.
  - Purpose: server-side allowlist and immediate revocation surface for internal JWTs used by meta/control services.
  - Lifetime: absolute TTL derived from `FIREMUD_AUTH_JWT_EXPIRATION_MS + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`. Entries are not extended by client activity; when they expire, new tokens must be issued. Coordination Redis resets that drop `session:auth:*` entries force re-authentication for the affected scopes.

- **Gameplay session bindings**
  - Keys: tenant-scoped session keys described in [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding), storing `accountId`, `tenantId`, `characterId`, and tick-region context.
  - Purpose: bind a connected socket or reconnect token to a character in a specific tenant, enforce one session per character, and support reconnect flows.
  - Lifetime: on successful gameplay admission at `admissionAt`, Game Session stores the immutable logical anchor `gameplaySessionExpiresAt = admissionAt + session_expiration_ms`. The Redis TTL is physical cleanup/storage metadata and may refresh while the binding is active, but it must never move that anchor. After disconnect or suspension at `disconnectAt`, resume is admitted only before `resumeDeadline = min(gameplaySessionExpiresAt, disconnectAt + effective firemud.reconnection.policy.resume-window-ms)`. Redis key presence and transcript retention are not sufficient authority to resume.

- **Bootstrap/pre-auth session contexts**
  - Keys: current implementation-local `sessionctx:*` entries described in the Game Session runtime docs.
  - Purpose: remember transport-level bootstrap context before gameplay authentication completes.
  - Lifetime: bounded by the same session-expiration family for cleanup, but these entries are not logically resumable gameplay sessions until `LOGIN` and `PLAY` have established authenticated gameplay scope.

Game Session must also maintain bounded authoritative secondary indexes for gameplay bindings so takeover, reconnect, and revocation do not require scans:

- `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>` -> `sessionId`
- `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` -> active `sessionId` set
- `session:game:index:tenant:{tenantGameplayTag}` -> active `sessionId` set

Index contract requirements:

- Game Session is the sole writer for these indexes.
- The session record plus these tenant-scoped indexes must be mutated through one shard-local session-only CAS/update flow where all keys share `{tenantGameplayTag}`. This is the atomic boundary for takeover and resume decisions inside Redis Cluster.
- Region-local gameplay binding is intentionally outside that atomic boundary and follows the separate session-to-region bridge contract in the Redis architecture docs.
- Index entries must be removed or expired when the bound gameplay session ends or becomes non-resumable.
- Billing- and membership-driven revocation flows must use these bounded indexes rather than wildcard key scans.

Each gameplay session binding must store the server-side auth token identity it is operating under:

- `authTokenHash` – the token hash for the internal JWT that Game Session uses when making backend calls for this session. Clients never see or transmit this token.
- `authTokenIssuedAt` (`iat`) – the issuance time of that JWT.
- `membershipVersion` – the latest authoritative tenant-membership version used when the session was admitted or last refreshed.
- When roles are refreshed mid-session, Game Session must update the stored `authTokenHash` and `authTokenIssuedAt` in the gameplay session binding and persist the refreshed `membershipVersion` when tenant membership authority is consulted.

On reconnect/resume (after the client re-`LOGIN`s and re-`PLAY`s), Game Session must load the gameplay session binding and confirm:

- The newly authenticated caller `accountId` matches the stored gameplay binding subject.
- Current tenant membership and role authority still permits gameplay admission for the target tenant and is not older than the stored `membershipVersion`.
- The gameplay session key remains logically resumable (key present, `gameplaySessionExpiresAt` has not passed, the current `resumeDeadline` has not passed, and the uniqueness key is unchanged).
- Current revocation state does not block the account or tenant for gameplay admission.

Resume validation must not depend on the previous internal service token remaining valid. After a fresh successful `LOGIN`, Game Session must mint or obtain a fresh backend token, atomically replace stored `authTokenHash` and `authTokenIssuedAt`, update `membershipVersion`, and then resume the gameplay binding. Resume is rejected only for subject mismatch, expired or non-resumable gameplay state, expired resume window, revoked account or tenant state, or lost gameplay membership. The fresh token's validity remains bounded by its own `exp`; obtaining it does not extend `gameplaySessionExpiresAt` or `resumeDeadline`.

### Active Session Token Refresh (Required)

Long-lived gameplay sessions require periodic service-token rotation, independent of role changes. Game Session must:

1. Refresh session service JWTs on a bounded cadence (recommended at 50% of JWT lifetime with random jitter and a hard floor of 60 seconds between refresh attempts).
2. Refresh immediately when an internal backend call fails with auth-expired semantics. Treat auth-revoked responses as non-refreshable: fail closed and revalidate authoritative account, tenant, membership, and gameplay-binding state before allowing further actions.
3. On successful refresh, atomically update gameplay session binding fields `authTokenHash` and `authTokenIssuedAt` before using the new token for subsequent backend calls. Do not rewrite the immutable `gameplaySessionExpiresAt` anchor or the disconnected `resumeDeadline`.
4. If expiration-driven refresh fails, fail closed for gameplay actions that require backend auth and return a canonical session-expired error, forcing re-login. For auth-revoked responses, terminate or revoke the gameplay binding when authoritative state no longer permits it; token rotation must not bypass revocation.

Security- and billing-related events (for example, account bans, password resets, tenant suspension, or subscription state changes) do not all behave identically; they follow subscription-aware rules:

- For **account-level security events** such as account bans or password resets, services must:
  - Set `session:auth:revoked_after:account:<accountId>` to "now" so previously issued tokens become invalid without requiring key scans.
  - Revoke any gameplay session keys bound to the affected account across tenants so active sockets are kicked and must re-login under the new security conditions.
- For **tenant-level billing events**, see the subscription-state mapping below and [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement) for when revocation is mandatory versus when quotas and warnings apply.
- For **tenant membership changes**, the Account Service is the authoritative source of membership versioning and change events. Gameplay sessions must be revoked immediately when the caller loses tenant membership or gameplay-admission authority for that tenant.

### Billing State and Revocation Rules

Subscription and billing state drives how aggressively sessions are revoked:

- `trialing`, `active`
  - No automatic revocation based solely on billing state.
  - Quotas and entitlements from the plan apply; gameplay and control-plane access behave normally.
- `past_due`, `grace`
  - No automatic revocation of existing sessions.
  - Operator and creator UIs surface strong warnings; services enforce any soft restrictions defined by the plan (for example, blocking new instances while allowing existing ones to run).
  - Auth token sessions and gameplay sessions remain valid unless explicitly revoked for security reasons.
- `suspended`, `canceled`
  - Tenant-level hosting is disabled for gameplay:
    - Game Session and world-management flows must reject new game instance creations, restarts, or tenant selection for gameplay for the affected `tenantId` based on `GetTenantEntitlementsForRuntime`.
    - New player logins and tenant-selection attempts for that tenant are rejected with a dedicated billing error code.
  - Existing gameplay sessions for the tenant must be revoked so connected sockets are kicked and cannot reconnect into gameplay for that tenant.
  - Tenant-scoped authorization must be bulk-revoked by setting `session:auth:revoked_after:tenant:<tenantId>` to "now". The Account Service is the authoritative writer for this watermark and downstream services must not write the watermark key directly. Services must not rely on wildcard deletes (`session:auth:tenant:<tenantId>:*`) in hot paths. Billing-safe and support-safe control-plane routes, including tenant-scoped export, remain available as described in [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement).

### Control-Plane Logout

Control-plane logout for admin and creator UIs is implemented as explicit Account Service APIs:

- `POST /auth/logout` (or equivalent gRPC method) – per-token logout for the currently presented token.
- `POST /auth/logout-all` (or equivalent gRPC method) – bulk logout for all active tokens belonging to the authenticated account.

For per-token logout, clients call `POST /auth/logout` with the current JWT in the `Authorization` header. The Account Service:

- Computes the `tokenHash` from the presented JWT.
- Deletes the corresponding `session:auth:*:<tokenHash>` allowlist entries for that token (account-scoped, plus any global and tenant-scoped entries that were created for it).
- Emits an audit event so logout activity is observable.

This flow performs a per-token logout: it invalidates the current browser or device session without affecting other active sessions for the same account.

For `POST /auth/logout-all`, the Account Service must:

- Set `session:auth:revoked_after:account:<accountId>` to "now".
- Emit an audit event indicating global logout and actor context.
- Return success even when no active tokens remain (idempotent behavior).
- Treat the account watermark as immediate authority for revocation; existing `session:auth:tenant:*` and `session:auth:global:*` keys may be removed by bounded background cleanup and must not be required for correctness.

See [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

### Control-Plane Session UX Expectations

Control-plane UIs must treat certain auth failures as hard logout conditions and others as tenant-level billing issues:

- Meta/control APIs that rely on JWTs return canonical error codes such as:
  - `AUTH_TOKEN_EXPIRED` – The presented JWT is no longer valid because its cryptographic lifetime has ended. Frontends must clear any in-memory token, redirect to login, and display a "Session expired" message.
  - `AUTH_SESSION_REVOKED` – The JWT’s auth token sessions have been revoked due to a security event (for example, password reset, account ban, or "logout all devices"). Frontends must clear in-memory token state, redirect to login, and indicate that the session was ended for security reasons.
  - `TENANT_BILLING_BLOCKED` – The operation is blocked because the tenant’s billing state (for example, `suspended` or `canceled`) does not allow the requested action. Frontends must keep the user logged in but surface a billing-specific banner or UI state for that tenant and disable gameplay and instance-management actions while still allowing the billing-safe control-plane surface (for example, updating payment details or exporting tenant-scoped data).
  - `MEMBERSHIP_AUTH_UNAVAILABLE` – Billing-safe mutation authorization could not be established from live membership authority. Frontends keep auth state, show retriable availability feedback, and block billing-safe mutations until authority recovers.
  - `ADMISSION_POINTER_UNAVAILABLE` – Gameplay admission pointer state is unavailable or ambiguous. Frontends keep auth state and retry admission with bounded backoff instead of logging out.
- Closing a browser tab or window does not automatically revoke auth token sessions; users must call explicit logout (or an operator must use "logout all devices") to revoke server-side allowlist entries before TTL expiry. On shared devices, UIs must encourage explicit logout from admin and creator sections.
