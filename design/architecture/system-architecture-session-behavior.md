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
3. Updated token context is used for Game Session's subsequent calls to auth/control-plane services; gameplay-domain gRPC calls use typed `PlayerExecutionContext` derived from current authoritative session state rather than forwarding per-player JWT claims downstream.

The Game Session Service exposes `/sessions/{sessionId}/refresh-roles` for manual refreshes. Implementations must ensure the effective claims injected into subsequent backend calls reflect the latest role assignments without requiring players to re-login.

This process is invisible to the client; no re-login is needed.

Membership changes that affect tenant access follow a stricter contract than ordinary role refresh:

1. The Account Service emits a membership-change event containing `accountId`, `tenantId`, `membershipVersion`, `membershipAuthorityGeneration`, the changed role set, and whether gameplay admission remains allowed.
2. Game Session compares the event against active gameplay bindings for `{accountId, tenantId}`.
3. Losing tenant membership or losing gameplay-admission authority (for example removal of `player` or a required private-realm grant) immediately revokes the affected gameplay sessions. Planned realm or playtest closure uses the owning close-and-drain workflow before grants are revoked; grant revocation itself is not a graceful-drain mechanism.
4. For caller-bound tenant control-plane access, the Account Service must also advance the `{accountId, tenantId}` membership authority generation when membership or tenant-role changes invalidate previously issued tenant authority for that caller.
5. Non-gameplay role changes may be handled by in-session token refresh for gameplay state, but reconnect/resume must compare current membership authority to the stored `membershipVersion` before restoring gameplay.
6. `PLAY` and reconnect/resume must obtain `membershipVersion` from authoritative membership reads rather than inferring it from JWT claims or local caches.

Membership-change event delivery semantics are required, not best-effort folklore:

- Every event must include a stable `eventId` plus the tuple `{accountId, tenantId, membershipVersion, membershipAuthorityGeneration}`.
- `membershipVersion` must be monotonic per `{accountId, tenantId}` and must advance on any role or membership change that can affect gameplay or tenant-safe control-plane authority. `membershipAuthorityGeneration` is a separate authority fence that advances whenever previously issued caller-bound tenant authority must be invalidated; consumers must not substitute one for the other.
- Consumers must treat duplicate or older versions as no-ops.
- If Game Session detects a version gap or has no prior version for an active binding, it must reconcile immediately via the authoritative internal membership API before deciding whether the session remains valid.
- Account Service owns the version increment rules; other services must not synthesize membership versions locally.

Account commits each security, membership, grant, or billing authority change, the corresponding durable Account-owned authority-generation or grant-version advance, and its monotonic outbox event in one database transaction. Redis and other downstream projections then idempotently reflect the committed authority state. The cutoff workflow does not report enforcement complete until the required projection and consumer convergence succeeds. Game Session consumes the durable events through an idempotent consumer and maintains the canonical bounded active-binding indexes listed below; correctness must not depend on wildcard Redis scans.

An accepted, delivered authority event is the immediate revocation path: Game Session targets the affected bindings, closes their active sockets, and removes their admission state without waiting for the reconciliation interval. The `<=60-second` bound applies only when an event is missed, delayed, or cannot be consumed; batched authority-generation/version reconciliation must then discover the stale authority and terminate the affected bindings within that bound. If the reconciliation lease cannot be renewed, new admission fails closed and active bindings whose authority cannot be re-established are terminated at the bound. This is periodic per-authority reconciliation, not an Account or Redis lookup on each gameplay command.

## Session and Identity Management

FireMUD deliberately distinguishes between several types of sessions so that identity, gameplay continuity, and auth token lifetimes can evolve independently:

- **Auth token sessions** – Represented by one `session:auth:token:<tokenHash>` issued-token registry record per revocable JWT in Coordination Redis, backing meta/control and admission APIs.
- **Bootstrap transport session contexts** – Current Game Session implementations store pre-auth socket context under the `sessionctx:*` key family. These records may exist before `LOGIN`, may have no account or membership authority, and are used only for bootstrap scope, locale, and reconnect lookup plumbing.
- **Gameplay sessions** – Tenant-scoped bindings between a connected socket (or reconnect token) and a character in a specific tenant, backed by gameplay Redis keys.
- **Control-plane UI sessions** – Browser or desktop admin/creator sessions that hold short-lived JWTs client-side and rely on auth token sessions on the server.

The Game Session Service is responsible for:

- Authenticating sockets and binding identity context.
- Managing gameplay Redis session state such as `characterId`, `tenantId`, and tick region.
- Obtaining Account-issued JWTs for backend interactions. Game Session never mints JWTs; Account is the sole JWT issuer.

### Session Types and Lifetimes

FireMUD uses distinct lifetimes and invariants for each session type:

- **Issued-token registry records**
  - Key: `session:auth:token:<tokenHash>` on Coordination Redis, where `<tokenHash>` is a fixed-length SHA-256 digest of the complete compact JWT.
  - Purpose: one versioned Account-owned issuance and immediate per-token revocation record for each revocable `control-ui`, player-bootstrap, or receiver-specific private player-delegation JWT. Signed claims plus Account-owned revocation/version state govern tenant/global authority without additional per-scope token keys.
  - Lifetime: `registry_ttl_ms = max(0, token_exp_ms - now_ms) + FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`, so each record is retained through that token's actual JWT `exp` plus the cleanup margin. Records are not extended by client activity; when they expire, new tokens must be issued. Coordination Redis resets that drop `session:auth:*` force re-authentication.

- **Gameplay session bindings**
  - Keys: tenant-scoped session keys described in [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding), storing `accountId`, `tenantId`, `characterId`, and tick-region context.
  - Purpose: bind a connected socket or reconnect token to a character in a specific tenant, enforce one session per character, and support reconnect flows.
  - Lifetime: on successful gameplay admission at `admissionAt`, Game Session stores the immutable continuity anchor `continuityBindingExpiresAt = admissionAt + session_expiration_ms`. Passing it does not itself end a continuously connected, currently authorized session, but the old binding cannot resume after a later transport loss. The authoritative active binding and its current revocation/fencing state are distinct from the expiring reconnect/transcript cache; cache refresh cannot extend active or resume authority, and cache loss cannot authorize a new binding. The Redis TTL is physical cleanup metadata and may refresh while active without moving the anchor. Each connected-to-disconnected transition starts an immutable episode at `disconnectAt`, with `resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective firemud.reconnection.policy.resume-window-ms)`. Failed reconnects cannot extend that pair. Successful resume consumes the episode; a later transport loss starts a new episode bounded by the same continuity anchor. An `auth-revoked` result received while connected must first terminate the active binding and create this disconnection episode before any fresh re-admission. Redis key presence and transcript retention are not sufficient authority to resume.

- **Bootstrap/pre-auth session contexts**
  - Keys: current implementation-local `sessionctx:*` entries described in the Game Session runtime docs.
  - Purpose: remember transport-level bootstrap context before gameplay authentication completes.
  - Lifetime: bounded by the same session-expiration family for cleanup, but these entries are not logically resumable gameplay sessions until `LOGIN` and `PLAY` have established authenticated gameplay scope.

Game Session must also maintain bounded authoritative secondary indexes for gameplay bindings so takeover, reconnect, and revocation do not require scans. There are five index families in total, but only four are tenant-scoped and share the shard-local session CAS:

- `session:game:index:character:{tenantGameplayTag}:<gameInstanceId>:<characterId>` -> `sessionId`
- `session:game:index:account:<accountId>` -> active tenant-qualified `sessionId` set across all tenants for the account
- `session:game:index:account-tenant:{tenantGameplayTag}:<accountId>` -> active `sessionId` set
- `session:game:index:tenant:{tenantGameplayTag}` -> active `sessionId` set
- `session:game:index:realm-grant:{tenantGameplayTag}:<worldSlug>:<realmSlug>:<accountId>` -> active `sessionId` set for grant-gated realms

Index contract requirements:

- Game Session is the sole writer for all five index families; Account authority and realm-grant state remain the authorization source, not the indexes.
- The shard-local CAS/update flow contains exactly the gameplay session record plus the four tenant-scoped indexes: character, account-tenant, tenant, and realm-grant. All of those keys share `{tenantGameplayTag}`. This is the atomic boundary for takeover, resume, and tenant/grant-binding decisions inside Redis Cluster.
- The cross-tenant `session:game:index:account:<accountId>` index is explicitly excluded from that shard-local CAS because it spans tenant hash slots. Before the tenant-scoped CAS publishes a new binding, Game Session durably commits a deterministic account-index repair obligation keyed to that binding transition. The shard-local CAS may publish only a provisional binding with `accountIndexState=REPAIR_REQUIRED`; it is not admissible for gameplay, resume, or takeover. Game Session then performs the separate idempotent account-index write, durably acknowledges the repair obligation, and uses a follow-up shard-local CAS to set `accountIndexState=ACKNOWLEDGED` and admit the binding. This ordered protocol closes the cross-slot crash windows without claiming a cross-store transaction.
- Region-local gameplay binding is intentionally outside that atomic boundary and follows the separate session-to-region bridge contract in the Redis architecture docs.
- Normal termination, revocation, takeover, and expiry remove or expire all applicable index entries idempotently. Reconciliation verifies indexed session records, removes stale entries, and recreates missing entries from active authoritative bindings without wildcard keyspace scans.
- Account-wide cutoff and logout-all processing reads both the account index and outstanding durable repair obligations. It must not report complete while an active or provisional binding is omitted, an obligation is unresolved, or index coverage is unavailable or ambiguous. The fail-closed fallback in ADR 0030 fences the affected account scope, terminates locally owned bindings, and keeps any unaccounted binding blocked until reconciliation proves coverage.
- A provisional binding whose account-index acknowledgement does not complete within the active-revocation deadline is terminated and recorded as failed closed; reconciliation does not extend the deadline or convert tenant-local presence into complete account-wide coverage.
- Billing- and membership-driven revocation flows must use these bounded indexes and obligations rather than wildcard key scans.

Each gameplay session binding must store the server-side auth token identity it is operating under:

- `authTokenHash` – the token hash for the private `game-session-account-delegation` JWT that Game Session uses when making backend calls for this session. Clients never see or transmit this token.
- `authTokenIssuedAt` (`iat`) – the issuance time of that JWT.
- `authTokenExpiresAt` (`exp`) – the deadline used to schedule rotation before expiry.
- `membershipVersion` – the latest authoritative tenant-membership version used when the session was admitted or last refreshed.
- `membershipAuthorityGeneration` – the Account-owned authority fence used to reject caller-bound tenant authority issued before a membership or tenant-role invalidation.
- When roles or private delegation are refreshed mid-session, Game Session must atomically update `authTokenHash`, `authTokenIssuedAt`, and `authTokenExpiresAt` in the gameplay session binding and persist the refreshed `membershipVersion` when tenant membership authority is consulted.

On reconnect/resume (after the client re-`LOGIN`s and re-`PLAY`s), Game Session must load the gameplay session binding and confirm:

- The newly authenticated caller `accountId` matches the stored gameplay binding subject.
- Current tenant membership and role authority still permits gameplay admission for the target tenant, is not older than the stored `membershipVersion`, and matches the current `membershipAuthorityGeneration` authority fence.
- The gameplay session key remains logically resumable (key present, `continuityBindingExpiresAt` has not passed, the current `resumeDeadline` has not passed, and the uniqueness key is unchanged).
- Current revocation state does not block the account or tenant for gameplay admission.
- Current entitlement authority is fresh for a new binding. Only resume of the same still-resumable binding may use an eligible positive last-known-good snapshot no older than five minutes when refresh is unavailable; the snapshot must be authoritative for the same target and authority tuple. New joins, fresh bindings, expansion, target changes, and any uncertain, missing, stale, negative, revoked, or gapped authority fail closed.

When resume relies on grace-period continuity, Account issues an exact-binding, exact-resume-episode activation lease only after validating current lifecycle, billing, membership, grant, and security authority. Game Session may move the binding only to provisional `RESUME_PENDING` under the lease fence. `RESUME_PENDING` is never admissible: Game Session must observe the exact lease, binding, and resume-episode identity durably finalized as `COMMITTED` before it may replace token state, consume the episode, or publish the binding as admitted. If the matching lease is missing, expired, fenced, ambiguous, or not `COMMITTED`, Game Session performs none of those mutations, retires any candidate token through Account's idempotent abort path, and fails closed. A concurrent Account cutoff fences the lease, and stale local CAS or late finalization fails closed. This is an ordered idempotent cross-service protocol, not a cross-store atomic transaction.

Resume validation must not depend on the previous private player-delegation token remaining valid. After a fresh successful `LOGIN`, Game Session must request and obtain a fresh `game-session-account-delegation` token issued by Account; Game Session never mints this token and Account is the sole JWT issuer. Only after the exact activation lease is `COMMITTED` may Game Session atomically replace stored `authTokenHash`, `authTokenIssuedAt`, and `authTokenExpiresAt`, update `membershipVersion`, consume the current disconnection episode, and transition the binding from `RESUME_PENDING` to admitted. A binding CAS that loses the lease fence or otherwise fails must not leave the replacement token usable; Game Session must invoke Account's idempotent retire/abort path for the orphan token before reporting resume failure. The new token's expiration must be persisted before resumed backend calls use it. Resume is rejected for any failed validation above, including subject mismatch, stale or lost gameplay membership, expired or non-resumable gameplay state, an expired resume window, a changed uniqueness key, or revoked account or tenant state. The fresh token's validity remains bounded by its own `exp`; obtaining it does not extend `continuityBindingExpiresAt` or the current episode's `resumeDeadline`.

### Active-Socket Auth Revocation

When an `auth-revoked` result reaches a currently connected gameplay binding, Game Session must fail closed and atomically transition the gameplay binding out of `connected` to an auth-revoked/disconnected state, record the connected-to-disconnected transition at that termination time, and close the active gameplay socket before any re-admission is possible. That transition creates the current immutable disconnection episode. If current authoritative account, membership, entitlement, revocation, uniqueness, and continuity checks later permit gameplay, a fresh `LOGIN` plus `PLAY` may consume that episode; otherwise the binding remains terminated and non-resumable. A connected binding must never be re-admitted by consuming an episode that was never created.

### Active Session Token Refresh (Required)

Long-lived gameplay sessions require periodic rotation of the private `game-session-account-delegation` JWT used for Account calls. Gameplay clients never receive it, and gameplay-domain authorization continues to use the mTLS workload and typed execution-context contract rather than adding token refresh to each gameplay RPC. Game Session must:

1. Schedule planned refresh at approximately 50% of the current JWT lifetime with random jitter, but always before `exp` by the configured safety margin. A 60-second minimum interval may throttle repeated attempts only when enough validity remains; it must never postpone the final safe refresh beyond expiry.
2. Single-flight concurrent refresh demand for the same binding. A transient failure while the current token remains valid retries with bounded jittered backoff and does not rewrite gameplay continuity deadlines.
3. Present the current token identity, per-lineage `tokenGeneration`, binding identity, the Account-owned token-identity fence version, and idempotent request ID to the Account-owned refresh surface. Account revalidates the applicable issuer/account/tenant/membership authority generations, account state, membership/version, and current token fence before issuing a replacement. A pending or committed exact-token logout fence rejects the request rather than allowing refresh to recreate that token. A restart/takeover owner that no longer has the raw token instead presents the binding's Account-issued single-use rebind handle; Account consumes the handle only after validating its binding, lineage, authority tuple, continuity deadline, and current token fence and returns a fresh token plus replacement handle. Concrete Game Session mTLS identity, binding identifiers, or token hash alone cannot refresh or rebind.
4. Treat auth-expired backend failure as an immediate refresh opportunity while the refresh authority remains valid. Auth-revoked failure is non-refreshable and requires authoritative reconciliation; when the binding is connected, Game Session first applies the active-socket auth-revocation transition above. Logout-all, password reset, security lock, membership loss, or another blocking authority-generation advance cannot be crossed by minting a token with a newer `iat`, and revalidation alone cannot resume gameplay actions.
5. On success, atomically replace `authTokenHash`, `authTokenIssuedAt`, `authTokenExpiresAt`, the single-use rebind handle, and refreshed membership metadata in the gameplay binding before new calls use the replacement. The binding CAS and Account installation acknowledgement must carry the current token-identity fence version; a pending or committed exact-token logout fence, or a newer fence for the lineage, rejects stale installation and prevents recreation of the logged-out identity. The raw delegation token remains process-local and is not persisted. The prior token may overlap only through the shorter of its original expiry or the maximum already-started internal RPC deadline. Game Session then sends Account an idempotent installation/retirement acknowledgement containing the prior token identity, refresh lineage, replacement identity, and retirement request ID. Account, as registry owner, immediately completes the matching fenced installation state and returns stored success for a replay of the same acknowledgement. Game Session uses only the replacement for new calls, while Account keeps the predecessor record in `retiring` through `predecessorUsableUntil` and removes it idempotently after that bounded overlap. An absent record without matching completed-request evidence is not sufficient authority.
6. Never rewrite the immutable `continuityBindingExpiresAt` anchor or the current disconnection episode's `disconnectAt` / `resumeDeadline` pair during token rotation. Successful resume consumes that episode; a later connected-to-disconnected transition creates a new pair still bounded by the original continuity anchor.
7. If refresh cannot establish authority before the current token expires, fail closed for backend-authenticated actions, atomically transition the affected gameplay authentication state to token-expired/disconnected and create its immutable disconnection episode, close the gameplay socket, and require fresh login. Fresh `LOGIN` plus `PLAY` may resume a still-authorized binding only after that defined disconnection episode exists; successful re-admission consumes it without extending `continuityBindingExpiresAt`.

Security- and billing-related events (for example, account bans, password resets, tenant suspension, or subscription state changes) do not all behave identically; they follow subscription-aware rules:

- For **account-level security events** such as account bans or password resets, services must:
  - Advance the Account authority generation so previously issued tokens become invalid without requiring key scans or timestamp comparisons.
  - Revoke any gameplay session keys bound to the affected account across tenants so active sockets are kicked and must re-login under the new security conditions.
- For **tenant-level billing events**, see the subscription-state mapping below and [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement) for when revocation is mandatory versus when quotas and warnings apply.
- For **tenant membership changes**, the Account Service is the authoritative source of membership versioning and change events. Gameplay sessions must be revoked immediately when the caller loses tenant membership or gameplay-admission authority for that tenant.

### Billing State and Revocation Rules

Subscription and billing state drives how aggressively sessions are revoked:

- `trialing`, `active`
  - No automatic revocation based solely on billing state.
  - Quotas and entitlements from the plan apply; gameplay and control-plane access behave normally.
- `past_due`
  - No automatic revocation or gameplay-admission restriction applies solely because of this state. Existing and new gameplay continue under ordinary plan quotas while operator and creator UIs surface strong warnings.
- `grace`
  - Existing connected gameplay sessions and reconnect of the same still-resumable session remain allowed.
  - First-time public join, fresh gameplay bindings, new instances, scale-out, and quota-increasing operations are denied until billing returns to an eligible state.
  - Auth token sessions remain valid unless separately revoked for security or membership reasons, and billing-safe management remains available.
- `suspended`, `canceled`
  - Tenant-level hosting is disabled for gameplay:
    - Game Session and world-management flows must reject new game instance creations, restarts, or tenant selection for gameplay for the affected `tenantId` based on `GetTenantEntitlementsForRuntime`.
    - New player logins and tenant-selection attempts for that tenant are rejected with a dedicated billing error code.
  - Existing gameplay authority is revoked. Game Session sends one bounded, non-sensitive game-unavailable notice, stops further gameplay admission, closes connected sockets, and prevents reconnect into that tenant. The notice flush is not a continuation grace period.
  - Instance processes may then use the separate five-minute maximum drain window for internal cleanup; they are not player-admissible during that drain.
  - Tenant-scoped authorization must be bulk-revoked by advancing the Account-owned tenant authority generation. Downstream services must not write Account authority state directly. Services must not rely on wildcard token-record scans or deletes in hot paths. Billing-safe and support-safe control-plane routes, including tenant-scoped export, remain available as described in [Subscription Management](./microservices/account-service/subscription-management.md#tenant-availability-and-quota-enforcement).

Entitlement evaluation is not routine gameplay action authorization. Existing uninterrupted sessions continue without per-action Account/cache reads until a hard billing event or another owning revocation rule ends them.

### Gameplay Logout and Resume Transcript

Explicit gameplay `LOGOUT` is terminal for the current binding. It immediately removes continuity/resume authority and makes the binding's private resume transcript non-replayable; physical deletion may complete asynchronously. A later successful `LOGIN` and `PLAY` is fresh admission and must not replay logged-out private context unless a separate explicit product policy authorizes that behavior.

### Control-Plane Logout

Control-plane logout for admin and creator UIs is implemented as explicit Account Service APIs:

- `POST /auth/logout` (or equivalent gRPC method) – per-token logout for the currently presented token.
- `POST /auth/logout-all` (or equivalent gRPC method) – bulk logout for all active tokens belonging to the authenticated account.

For per-token logout, clients call `POST /auth/logout` with the current JWT in the `Authorization` header. The Account Service:

- Computes the `tokenHash` from the presented JWT and, in one Account transaction, commits a durable `PENDING` operation plus a new exact-token `PENDING_LOGOUT` fence version bound to the request ID, immutable request digest, and exact token identity before touching Redis.
- Only after that transaction commits, idempotently deletes the corresponding `session:auth:token:<tokenHash>` registry record, then advances the matching operation and fence to `COMMITTED` with its completed tombstone, per-token logout audit, and outbox state in an Account database transaction before reporting success.
- The bounded Account operation reconciler retries matching `PENDING` work. If a crash occurs after the fence commit and before deletion, it may delete and commit only after an Account CAS confirms that the operation still owns the same fence version. If a newer valid installation or other lineage operation has advanced that fence, the reconciler records a stale/no-op outcome and cannot commit over the newer state. A stale installation or retry that captured a pre-logout fence fails its own Account/Game Session CAS and cannot recreate the logged-out registry record or binding. Bare registry absence without matching `PENDING` or `COMMITTED` evidence remains denied. Only `COMMITTED` evidence satisfies an idempotent success response; the outbox publishes from that state and retries delivery independently.
- Treats the exact-token `PENDING_LOGOUT`/`COMMITTED` tombstone as a monotonic Account-owned token-identity fence. Refresh, rebind, installation, and reconciliation compare-and-set operations for that lineage must validate the current fence before changing Coordination Redis or Game Session state. This ordering does not claim a transaction across Account state, Coordination Redis, and Game Session state.

This flow performs a per-token logout: it invalidates the current browser or device session without affecting other devices or unrelated gameplay bindings for the same account. A first-party player UI separately stops reconnect, closes its socket through gameplay `LOGOUT`, and then revokes that device's `player-bootstrap` token; Account does not discover sockets from the per-token endpoint.

For `POST /auth/logout-all`, the Account Service must:

- Use a stable high-entropy `requestId` bound to an immutable logout-all request digest. A durable Account operation row has a uniqueness constraint on `(accountId, requestId)` and records the expected and committed authority generation plus the response outcome.
- In one Account database transaction, compare-and-set the expected account authority generation and commit the operation result, account-wide logout event, audit record, and outbox entry exactly once with distinct action type, actor, account, and request identity without recording raw tokens. A retry with the same request ID and digest returns the previously committed result; reuse with a different digest is rejected. A retry cannot advance the generation or duplicate audit/outbox records.
- Do not infer success from the absence of active tokens. Success requires the authorized logout-all operation and its durable account-generation/event evidence, or matching durable evidence that an earlier logout-all already superseded the presented authority.
- Treat the account authority generation as immediate authority for bulk revocation; older token records may be removed by bounded background cleanup and must not be required for correctness.
- Terminate control-plane tokens, player-bootstrap tokens, and all `game-session-account-delegation` lineages plus active gameplay bindings for the account across tenants through the account-security event and bounded reconciliation contract in ADR 0030. The event is consumed idempotently; account-wide completion is not reported while an active binding or durable index-repair obligation remains unresolved.

See [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding) for Redis structure and gameplay rebinding.

### Control-Plane Session UX Expectations

Control-plane UIs must treat certain auth failures as hard logout conditions and others as tenant-level billing issues:

- Meta/control APIs that rely on JWTs return canonical error codes such as:
  - `AUTH_TOKEN_EXPIRED` – The presented JWT is no longer valid because its cryptographic lifetime has ended. Frontends must clear any in-memory token, redirect to login, and display a "Session expired" message.
  - `AUTH_SESSION_REVOKED` – The JWT’s auth token sessions have been revoked due to a security event (for example, password reset, account ban, or "logout all devices"). Frontends must clear in-memory token state, redirect to login, and indicate that the session was ended for security reasons.
  - `TENANT_BILLING_BLOCKED` – The operation is blocked because the tenant’s billing state (for example, `suspended` or `canceled`) does not allow the requested action. Frontends must keep the user logged in but surface a billing-specific banner or UI state for that tenant and disable gameplay and instance-management actions while still allowing the billing-safe control-plane surface (for example, updating payment details or exporting tenant-scoped data).
  - `MEMBERSHIP_AUTH_UNAVAILABLE` – Billing-safe mutation authorization could not be established from live membership authority. Frontends keep auth state, show retriable availability feedback, and block billing-safe mutations until authority recovers.
  - `ADMISSION_POINTER_UNAVAILABLE` – Gameplay admission pointer state is unavailable or ambiguous. Frontends keep auth state and retry admission with bounded backoff instead of logging out.
  - `REALM_UNAVAILABLE` – The selected realm is explicitly `CLOSED` or in maintenance. Frontends keep auth state, show the realm state, and refresh discovery rather than treating closure as corrupt pointer authority.
- Closing a browser tab or window does not automatically revoke auth token sessions; users must call explicit logout (or an operator must use "logout all devices") to revoke server-side registry records before TTL expiry. On shared devices, UIs must encourage explicit logout from admin and creator sections.
