# FireMUD System Architecture: Frontend Architecture

This document describes the structure and tooling for FireMUD's browser-based user interfaces. The `web-client` module houses the player-facing React application built with **Vite** and **TypeScript**. FireMUD's target architecture is a dedicated first-party web application service that owns browser asset hosting and first-party web bootstrap concerns rather than treating Spring Cloud Gateway as the long-term home for frontend assets or product-specific browser logic. The first practical version of that service may be a terminal-style browser client before richer web UX is added. Additional React modules for the admin tools and Game Design interface are available.

Other UIs include a role-based admin interface and a game design editor. See [Role-Based Admin UI](./microservices/logging-admin-service/admin-ui.md) and [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md).

## Implementation Status

Explicit `JOIN`/`Join & Play` is target behavior, not a complete current runtime flow. In the target, `JoinPublicProductionMembership` is the sole membership writer for explicit public-production join, creating missing membership or restoring `INACTIVE`; Game Session admission, connect-token issuance, character creation, and `PLAY` require existing membership and never create or restore it implicitly. The current membership response exposes only `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`; current connect-token issuance and text `PLAY` proceed only when the two admission fields are true, and an otherwise eligible public-production request with `membershipExists=false` may fail closed with `JOIN_REQUIRED`. An existing response with `gameplayAdmissionAllowed=false` remains the established denial and cannot be called `INACTIVE` because lifecycle state is target-only. The obsolete implicit membership-writer surface has been removed. Explicit `JOIN`/`Join & Play` and the required connect-token `membershipVersion` plus `membershipAuthorityGeneration` reread remain unimplemented/proof gaps, so the current fail-closed result must not be read as an actionable join flow or as proof that the complete target sequence is implemented. The protected-cookie `/ws/game/**` handshake and bootstrap-backed bare `LOGIN` are implemented but non-promotable until Gateway carrier requirements, replay-marker durability, replay-quarantine continuity, and Account planned-rotation/compromise-cutover evidence are complete. The frontend availability gate applies to the otherwise eligible `membershipExists=false` case; target lifecycle-specific handling of missing or `INACTIVE` membership remains target-only. Returning members and grant-backed private/playtest flows remain eligible only when the live membership response fields, grant, entitlement, and routing checks pass; target generation/version rereads remain unimplemented. Gateway `POST /ws/game/connect-token/revoke` and the dependent immediate edge-revocation step described below are target-only and are not implemented in the current runtime. Account `POST /auth/logout` and `POST /auth/logout-all` guidance below is also target-only and unavailable. The current logout fallback is local reconnect-state clearing plus bounded credential expiry; it is not Account revocation, Account generation advancement, immediate Gateway revocation, an edge deny/replay fence, Game Session gameplay-binding termination, or downstream reconciliation evidence. Edge replay/deny enforcement is claimed only when that Gateway revoke path is implemented and reachable.

The explicit `JOIN`/`Join & Play` action and the independent `membershipVersion` plus `membershipAuthorityGeneration` reread at connect-token issuance are both unimplemented/proof gaps. Until both are implemented and proven, the frontend must keep first public-production entry with a missing or non-admitting current membership response fail-closed and unavailable rather than exposing a retry path that can create membership implicitly or issue a token from stale authority. Returning members and private/playtest callers with existing membership remain eligible when the live response fields (`membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`) and the other current admission checks pass; the exact `membershipAuthorityGeneration` and independent issuance-time `membershipVersion` rereads remain target/proof gaps. Target lifecycle-specific handling of missing or `INACTIVE` membership remains unavailable until that target response exists. This status is the frontend summary of the target/current distinction in [Authentication & Authorization](./system-architecture-authentication.md#implementation-status).

## Canonical Gameplay Enablement Gate

The frontend is a consumer of the canonical [realm catalog and admission-pointer contract](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract), not an owner of a second enablement policy. It may enable a selected realm's Join/Play action only from one current discovery snapshot whose `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` result is complete, resolves the same catalog `catalogRevision`, and reports the canonical `OPEN` target with its `admissibleGameInstanceId` and `pointerVersion`; the catalog's visibility and `publicProduction` facts remain the authority for their respective policies. The client must not derive enablement from a local flag, cached slug, cached `gameInstanceId`, or a duplicated pointer/policy contract.

Reachable missing, malformed, ambiguous, stale, or otherwise invalid pointer evidence keeps auth state but disables gameplay entry and maps to `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out pointer authority maps to `AUTH_UNAVAILABLE`; in the target state, a complete `CLOSED` pointer maps to `REALM_UNAVAILABLE`. The frontend reruns canonical discovery before retrying and never substitutes a cached target or infers one from display metadata. Pointer and continuation authorization remain defined by [Authentication](./system-architecture-authentication.md#login-and-session-flow) and [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake), not by frontend state.

### Target-only Gameplay Gates

The frontend keeps the target `PLAY` gate separate from the target `Join & Play` gate:

- **`PLAY` gate:** enable only after fresh selected-target routing/pointer evidence, entitlement, caller-bound `membershipLifecycleState=ACTIVE`, any required realm grant, and a valid current character all pass. `PLAY` never creates or restores membership.
- **`Join & Play` gate:** enable only for the selected public-production realm when the fresh public-joining policy is `allowPublicJoin=true` and caller-bound membership is missing or `INACTIVE`; it invokes the explicit Account join route and waits for the post-join snapshot before character discovery, creation, connect-token issuance, or `PLAY`.

These are target-only gates. The current frontend must keep missing or non-admitting membership unavailable and must not expose an actionable Join route while the explicit join endpoint and required issuance reread remain unimplemented. A private/playtest target never uses the public `Join & Play` gate; it requires existing `ACTIVE` membership plus the current realm grant.

## Implementation Notes

- The current `web-client` baseline now uses `TanStack Query` plus local feature state rather than Redux starter scaffolding.
- The current frontend baseline and its broader gaps are recorded in [Player Experience, Commands, and Communication implementation tracking](../project-management/implementation-tracking/player-experience-commands-and-communication.md); later frontend/editor work should treat Redux as an explicit exception rather than silently reintroducing it as default infrastructure.

---

## Component Hierarchy

FireMUD uses React components with a **feature-first** organization. Each feature folder contains its own components, tests, and styling.

```text
web-client/
  src/
    features/
      account/
        AccountPage.tsx
        accountQueries.ts
        accountState.ts  # local UI/editor state when needed
      gameplay/
        GameplayPage.tsx
        gameplayQueries.ts
        gameplayState.ts
      ...
```

- **Pages** represent top-level routes and compose smaller **UI widgets**.
- Reusable UI elements live under a shared `components/` directory.
- Material-UI provides the base widgets and theme customization.

## State Management

FireMUD's default frontend state model is:

- **TanStack Query** for server state;
- local component/form/editor state close to the owning feature;
- shared global client-state layers only when later work proves they are needed.

This is deliberate. Large server-backed world or admin datasets are not automatically "complex client state." They are usually a query/caching concern first. FireMUD should add a richer browser-wide state layer only when the browser truly becomes a stateful editor/runtime that needs long-lived cross-screen draft orchestration, collaborative editing state, undo/redo, or similarly heavy client-owned behavior.

- The canonical shared browser data substrate is a `QueryClient` configured in `src/main.tsx`.
- Shared API/query helpers live under `src/api/` or feature-local query modules and expose typed hooks for reads and mutations.
- Cache invalidation, background refetching, retry policy, and mutation lifecycle should be expressed through `TanStack Query` rather than a repo-wide Redux default.
- Local UI state should stay local until concrete later work proves a true shared client-state need.
- Redux Toolkit remains an allowed later tool, but only by explicit exception once a real client-state complexity case exists; it is not the baseline house style.

## Authentication and Session Handling

Frontend flows are split between **player gameplay sessions** and **admin/creator tools** so that gameplay auth remains simple while control-plane operations use JWTs:

- **Player UI (gameplay)**  
  - First-party browser clients are expected to be hosted by the dedicated first-party web application service, even when the first version is only a terminal-style browser client. Spring Cloud Gateway remains the public gameplay/API edge, but it should not become the long-term host for the player web application itself.  
  - Target first-party clients use one of two explicit gameplay flows:
    - **Returning member:** `POST /auth/player-bootstrap` -> bootstrap discovery -> bootstrap-authenticated character discovery/creation -> `POST /auth/connect-token` with the discovery-provided `connectScopeId` -> `/ws/game/**` -> bare `LOGIN` -> `PLAY`. This flow requires the selected existing caller-bound membership and does not call `/auth/bootstrap/join`.
    - **First join:** `POST /auth/player-bootstrap` -> public-production discovery -> `POST /auth/bootstrap/join` -> rerun public-production realm discovery -> use the new complete `{connectScopeId, tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion, catalogRevision, evaluatedAt, connectScopeExpiresAt}` snapshot for bootstrap-authenticated character discovery/creation -> `POST /auth/connect-token` with that new snapshot's `connectScopeId` -> `/ws/game/**` -> bare `LOGIN` -> `PLAY`. `/auth/bootstrap/join` may create missing membership or restore `INACTIVE` membership and must commit it against the exact `{catalogRevision, pointerVersion}` pair before the post-join discovery, character discovery, character creation, or connect-token issuance; no later step may create membership implicitly. The join transition emits one audit/outbox event for create or restore; an `ACTIVE` retry is event-free.
  - The first-join, returning-player, reconnect, resume, grant-backed private/playtest, and related gameplay-admission descriptions in this section are target-state contracts, not confirmed current supported behavior. Current text `PLAY` and connect-token issuance consume `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`; they proceed only when the two admission fields are true and may return non-actionable `JOIN_REQUIRED` only for an otherwise eligible public-production request with `membershipExists=false`. An existing response with `gameplayAdmissionAllowed=false` remains the established denial and cannot be classified as `INACTIVE` until Account exposes lifecycle state. That current `JOIN_REQUIRED` result is non-actionable while explicit `JOIN` / `Join & Play` is unimplemented; the frontend must not expose it as an available repair action. The obsolete implicit membership-writer surface has been removed; the required `membershipAuthorityGeneration` plus `membershipVersion` reread at connect-token issuance remains an unimplemented/proof gap. Current returning-member behavior may proceed when the existing live membership/character checks pass, but it does not prove the target exact generation/version reread; the target requires both independent values from one fresh membership snapshot. Current non-public missing or non-admitting membership behavior remains `WORLD_ACCESS_DENIED`; target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` is separate. Returning members and private/playtest callers with existing membership plus the current grant remain eligible only when their current runtime checks pass. The target descriptions remain the behavior to implement.
  - For every `POST /auth/connect-token` issuance, including the first issuance immediately after explicit join and both returning-member and grant-backed private/playtest flows, the service must independently validate the scalar `membershipVersion` returned by `GetTenantMembershipForRuntime` for the selected `{accountId, tenantId}` and the `membershipAuthorityGeneration` from that same authoritative membership snapshot. It must compare both values independently with the selected admission baseline, bind the scalar `membershipVersion` and generation into the connect-token issuance/binding evidence, and fail closed when either value is missing, stale, mismatched, unavailable, or assembled from another snapshot; a current grant never substitutes for the membership-version validation.
  - Browser and mobile-browser clients receive the short-lived `Firemud-Connect-Token` HttpOnly cookie used by the `/ws/game/**` handshake; first-party native-mobile clients using a cookie jar remain cookie-only. This protected-cookie path is the current supported path to bare `LOGIN`. Explicitly classified non-first-party/public non-browser clients are target-only and unavailable until their dedicated issuance route is fully registered and proven; the conditional target carrier is protected secure storage plus `X-Firemud-Connect-Token`, not a fallback for the current cookie path. The client then opens `/ws/game/**` and completes gameplay authentication by issuing `LOGIN` over the WebSocket channel using the already-verified bootstrap/connect context; Game Session must validate the complete context before using any field for `LOGIN`, `PLAY`, or scope comparison. The browser never prompts for or replays Telnet-style `LOGIN <email> [secret]` credentials, as described in [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow).
  - Browser transport constraints matter: a browser WebSocket cannot set arbitrary custom handshake headers the way Telnet smart clients, Mudlet integrations, or server-side clients can. The first-party browser flow therefore uses the `Firemud-Connect-Token` cookie set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The React client must not read or persist the connect token itself; it only consumes non-secret response metadata such as `expiresAt`, `tenantId`, `realmSlug`, and `gameInstanceId`. Query parameters must never carry connect tokens or other authentication credentials; non-secret bootstrap discovery selectors such as `connectScopeId` may be sent as query parameters. Clients must obtain a fresh token after any failed attempt that may have consumed it. After Account has durably committed an issuance result or Gateway has consumed its token, any new token issuance requires a new `requestId`; the old `requestId` may only reconcile that same request while its outcome is `PENDING` or otherwise indeterminate and never mints a replacement token.
  - The React client does not store or expose `control-ui` JWTs for gameplay. It may hold only the short-lived, memory-only `player-bootstrap` token described in the authentication design and use it solely for gameplay bootstrap surfaces such as bootstrap discovery, bootstrap-authenticated character discovery and character creation, `POST /auth/bootstrap/join`, and `POST /auth/connect-token`.
  - **Target-only player logout:** Explicit player logout would revoke the current `player-bootstrap` token with Account Service `POST /auth/logout`, clear local reconnect/client state, and use the Gateway `POST /ws/game/connect-token/revoke` operation. That Account endpoint and Gateway operation are unavailable in the current runtime. The current fallback only clears local reconnect/client state and relies on bounded credential expiry; it does not perform Account revocation, immediate Gateway revocation, an edge deny/replay fence, or client-side clearing of the HttpOnly cookie. The target Gateway revoke operation is an HTTP operation, not a WebSocket upgrade: it would read the HttpOnly cookie server-side, record a bounded deny marker for its `jti` or equivalent edge revocation fence checked before token consumption, and return the clearing `Set-Cookie` response where that response surface supports it. The browser must satisfy the fail-closed `Origin` and anti-CSRF policy defined by [ADR 0021](./decisions/adr-0021-staged-player-authentication-and-gameplay-binding.md); the React client never reads the token. The client clears active player state immediately even if Account is unavailable. The shared frontend auth/session controller is the sole owner of one dedicated, memory-only pending-revocation slot and never persists, logs, or uses its credential for gameplay/bootstrap calls. The pending-revocation copy is never used as an alternate active credential: the active in-memory `player-bootstrap` token remains the sole usable bootstrap credential until logout atomically captures it, after which the captured copy is used only for the logout/revocation request and is never reused for gameplay or bootstrap.
  - No first-party browser or mobile-browser flow may fall back to direct `LOGIN + PLAY`; that compatibility exception is limited to credential-bearing text clients that already have existing membership and a selected character.
  - The target canonical player logout order is: stop reconnect attempts, capture the current `player-bootstrap` token into the pending-revocation slot, ask Account to durably commit the exact-token `PENDING` intent and immediately advance the exact-token issuance fence to `PENDING_LOGOUT` in that same durable step, then send gameplay `LOGOUT` and close the gameplay socket, ask Gateway to deny the gameplay-connect `jti` and clear the HttpOnly cookie, and finally ask Account to invalidate the underlying token and commit the `COMMITTED` tombstone. Account must establish the durable issuance fence before socket closure or any racing `/auth/connect-token` issuance can succeed; every issuance path rejects the fenced lineage rather than returning a usable late credential. Once `PENDING` is durable, Account must reject racing issuance, refresh, rebind, installation, and every unrelated reconciliation for that token lineage; only the exact matching Account lifecycle reconciliation may complete or abort that pending logout operation. `PENDING` is never success and remains resumable through that exact Account operation until terminal completion. If Gateway revoke is unavailable, Account must not report the edge deny/clear outcome as confirmed; clear only local reconnect/client state and rely on the bounded token lifetime; Gateway mitigation must not be reported as full logout when Account is unavailable or its fence is unconfirmed. The browser never directly clears the HttpOnly gameplay cookie; only a server response can clear that cookie. The target Gateway revoke call uses a two-second per-attempt timeout, at most two retries, and 250 ms then 500 ms backoff where time remains, but every request timeout and backoff is capped by the remaining five-second total deadline. This Gateway controller budget is independent and non-nested: it is not shared with or added to Account's revocation budget. Account revocation retains its separate policy of at most five attempts, a five-second timeout per attempt capped by a 30-second overall deadline, and full-jitter exponential backoff starting at 250 ms and capped at four seconds. The controller tracks Account PENDING, Gateway denial/clearing, and Account COMMITTED finalization as separate outcomes. It enters terminal `LOGGED_OUT_REVOKED` only after Account confirms the exact-token fence/Bootstrap-token revocation and Gateway confirms denial/clearing of the gameplay-connect token. Because Gateway gameplay-connect denial and cookie clearing are not currently routable, the current runtime cannot reach `LOGGED_OUT_REVOKED`; current logout flows terminate as `LOGGED_OUT_REVOCATION_UNCONFIRMED`. Account-only confirmation remains `LOGGED_OUT_REVOCATION_UNCONFIRMED` with edge revocation explicitly unconfirmed until Gateway confirms or the known gameplay-connect acceptance window ends; expiry closes that bounded edge risk but is reported as token-window expiry, not confirmed server revocation. Retry exhaustion, an unconfirmed Account result, or bootstrap-token expiry likewise remains `LOGGED_OUT_REVOCATION_UNCONFIRMED`, stays locally logged out, and requires a fresh `POST /auth/player-bootstrap` before any later gameplay flow. The frontend may retry only while the captured credential remains valid and its local retry deadline remains open. At the earlier of signed token expiry or that deadline, it wipes the raw credential and stops frontend retries while retaining only the opaque server-side logout handle and non-secret operation state needed to observe Account's durable reconciliation. Browser or process loss likewise destroys the memory-only credential and ends frontend retries, but neither local expiry nor frontend loss cancels the durable Account `PENDING` operation: Account's persisted operation and opaque logout handle are the authoritative revocation proof, and Account's reconciler continues exact-token revocation without the raw token. A raw credential may never be reused for gameplay/bootstrap calls, and retaining it in memory never extends its signed lifetime. Local clearing or process termination is not confirmed server revocation.
  - Logout invalidates the local bootstrap/connect-token request epoch and aborts every in-flight bootstrap, discovery, join, character, and connect-token request owned by that epoch. A late response is ignored for all client state transitions, but abort and epoch invalidation cannot guarantee that a late `Set-Cookie` was not already committed by the browser. The server-side logout issuance fence is therefore authoritative: Account rejects bootstrap or connect-token issuance that races a durable `PENDING` or `COMMITTED` logout for the same account/token lineage, and Gateway rejects a connect token whose deny/revocation fence wins before consumption. Where a logout response reaches the browser after the old issuance, the server must also return an explicit clearing or deny response so the late cookie cannot remain usable. The client must never treat ignored local state or local cookie clearing alone as proof of server revocation.
  - A server must reject an expired `Firemud-Connect-Token` cookie as `CONNECT_TOKEN_EXPIRED`, never fall back to a stale cookie, query parameter, or unapproved header, and return a clearing `Set-Cookie` response where the HTTP surface can do so. The browser cannot inspect the HttpOnly value; it discards the failed handshake attempt and obtains a fresh connect token only from a still-valid bootstrap session. If the bootstrap token is expired or revoked, the client restarts `POST /auth/player-bootstrap` and discovery instead of treating the expired cookie as a recoverable gameplay credential.
  - Tenant membership, non-public realm-access-grant checks, and runtime entitlement checks occur after account bootstrap. The `player-bootstrap` token is still a tenant-free, non-gameplay bootstrap credential: it authorizes caller-bound discovery and the explicit join/connect-token APIs, but it does not itself carry or grant gameplay scope. The bounded `gameplay-connect` token is the only edge-admission credential carrying the selected target snapshot. In target behavior, a first public-production join presents an explicit `Join & Play` action that may create missing durable Account-owned membership or restore `INACTIVE` membership, before character creation and `POST /auth/connect-token`; connect-token issuance never creates membership implicitly. Current connect-token issuance and text `PLAY` consume `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`, proceed only when the two admission fields are true, and may fail closed with `JOIN_REQUIRED` only for an otherwise eligible request with `membershipExists=false`. An existing response with `gameplayAdmissionAllowed=false` remains the established denial and is not lifecycle-aware. The obsolete implicit membership-writer surface has been removed, while explicit `JOIN` / `Join & Play` and the membership-authority-generation reread at issuance remain unimplemented/proof gaps. The frontend must mark only the current otherwise eligible missing-membership predicate unavailable/unsafe rather than treating a stale-generation path or established denial as a target fallback; existing-member and grant-backed flows remain separately gated by their own current checks. The selected target must come from canonical discovery and may be the public production realm or an explicitly authorized alternate realm such as a playtest fork.
  - Gameplay admission has three separate applicability modes. For public-production onboarding, discovery may precede membership, but only explicit `JOIN`/`Join & Play` creates missing membership or restores `INACTIVE` membership; `gameplay-connect`, trusted TCP Proxy admission, and `PLAY` are applicable only after that commit. For returning membership, those transports and `PLAY` require the existing caller-bound membership and current authority checks and do not perform onboarding. For a grant-backed private or playtest realm, missing or non-`ACTIVE` membership is `NON_PUBLIC_ENROLLMENT_REQUIRED`; those targets require the applicable grant and the existing caller-bound `ACTIVE` membership plus its current membership authority generation, never use public `JOIN`, and never create membership or treat the grant as a substitute for membership. The trusted TCP Proxy is only a transport trust path and does not replace the mode-specific downstream checks; a `gameplay-connect` token and `PLAY` command likewise do not establish membership or grant eligibility.
  - The client must treat `connectScopeId` as an opaque short-lived selector, not as a stable saved identifier. The discovery snapshot bundle is `{connectScopeId, tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion, catalogRevision, evaluatedAt, connectScopeExpiresAt}`. If reconnect or retry reports `CONNECT_SCOPE_MISMATCH`, it must discard the entire bundle and all derived connect-token metadata, rerun bootstrap discovery, complete target-only `Join & Play` and required character discovery/creation when applicable, and obtain a new selector and connect token rather than reusing any old bundle field or guessing a replacement target from cached `tenantId` or `realmSlug`. `ADMISSION_POINTER_UNAVAILABLE` likewise requires fresh discovery before retry rather than substitution from cached target fields.
  - Returning players with an existing Account-owned membership use browser bootstrap, bootstrap discovery, and `POST /auth/connect-token` before taking the normal `PLAY <world> [realm] [character]` path; the target skips the membership-creation action only after a fresh authoritative check confirms `ACTIVE` membership and its exact authority/version evidence. A valid current character is additionally required only before connect-token issuance or direct `PLAY`; otherwise the player proceeds through realm-scoped character discovery or creation. Target behavior requires an account with missing or `INACTIVE` membership for the selected game, including an otherwise returning account whose membership is no longer active, to take the explicit `Join & Play` path before character creation/connect, and forbids a connect-token or `PLAY` path from creating or restoring that membership implicitly. Current text `PLAY` and connect-token issuance consume `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`, proceed only when the two admission fields are true, and may fail closed with non-actionable `JOIN_REQUIRED` for an eligible public-production request with `membershipExists=false`; an existing non-admitting response cannot be classified as `INACTIVE` because lifecycle state is target-only. The obsolete implicit membership-writer surface has been removed; explicit `Join & Play` remains unimplemented, as does the required membership-authority-generation and independent membership-version reread proof at issuance. Current returning-member success therefore reflects the live existing-membership path, not proof of the target reread contract. The resulting membership powers subsequent “my games” and return flows. Game Session resolves world/realm/character identifiers, rechecks membership, routing, and caller-bound entitlements, returns bundle metadata, and binds the socket in Redis.
  - After connectivity loss while the in-memory `player-bootstrap` token survives, the client may request a fresh connect token, reconnect the WebSocket, then replay `LOGIN` and `PLAY` without prompting for credentials again only when the complete discovery snapshot is present and unexpired, and a fresh authoritative read confirms `membershipLifecycleState=ACTIVE`, the exact current `membershipAuthorityGeneration`, the independently current `membershipVersion`, and a valid current character for the selected target. A full page reload loses that memory-only token and must restart at `POST /auth/player-bootstrap`, then rerun discovery before requesting a connect token; the architecture defines no hidden refresh credential. `WORLDS`, `REALMS <world>`, and `CHARS <world> [realm]` remain optional discovery helpers only while the complete snapshot remains unexpired and those current membership, generation, version, and character checks pass; otherwise the client must rerun the full discovery/join/character path. Shared state restores session context only. Game Session must revalidate continuation authorization, the active binding and its fence, revocation state, and the current server-resolved target authority before resuming; Redis key presence or fresh backend-token rebinding is not authorization. First-party reconnects must not prompt the browser to replay Telnet-style `LOGIN <email> [secret]` credentials after bootstrap has been re-established.

- **Admin and creator UIs**  
  - Admin/creator interfaces authenticate through the Account Service (for example, via `/auth/login` exposed behind the Gateway). Successful login issues a short-lived `control-ui` JWT for control-plane APIs that represents a **control-plane browser session** for the current account.
  - A durable logout `PENDING` state rejects racing issuance, refresh, rebind, and installation for the fenced token lineage. It rejects every reconciliation except the matching Account lifecycle reconciliation that carries the exact opaque `logoutHandle`, request tuple, token identity/digest, and issuance fence for that pending operation. The handle never authenticates a request, restores authority, or returns a replacement credential.
  - `control-ui` JWTs are stored only in in-memory frontend state managed by the auth layer; they must not be written to `localStorage`, session storage, or exposed to third-party origins.
  - The frontend sends these tokens on meta/control API calls by setting the `Authorization: Bearer <token>` header on shared query/mutation requests. Backend services validate these JWTs with the shared `AuthTokenInterceptor` and enforce tenant access via the Tenant Authorization Contract described in [Authentication & Authorization](./system-architecture-authentication.md#tenant-authorization-contract).  
  - **Target-only logout:** The target flow immediately clears the active in-memory `control-ui` JWT and auth state, then calls Account Service `POST /auth/logout` to revoke only the presented browser/device token; account-wide “logout all devices” uses `POST /auth/logout-all`. For logout-all, Account revocation completion is a distinct outcome: Account commits the account authority-generation advance, security cutoff, and issuance fence, which blocks new authority and issuance but does not itself terminate or prove termination of Game Session gameplay bindings. Game Session separately reconciles every affected gameplay binding and binding index and returns complete, partial, or unavailable evidence; a generation event or local socket cleanup is not that evidence. The client reports `LOGGED_OUT_ACCOUNT_REVOKED` when Account has completed but downstream evidence is pending, `LOGGED_OUT_ACCOUNT_REVOKED_GAMEPLAY_PARTIAL` when Game Session evidence is partial or ambiguous, and `LOGGED_OUT_FULL` only when the required Gateway gameplay-credential deny/clear and complete Game Session binding/index reconciliation evidence are both committed. Account or Game Session unavailability never becomes full logout. These Account endpoints are unavailable in the current runtime. The current fallback clears the in-memory JWT, auth state, and local reconnect state and relies on bounded credential expiry; it records server-side revocation, Account generation advancement, Gateway denial, and Game Session termination/reconciliation as unconfirmed and does not imply any of them occurred. Closing a browser tab does not revoke auth token sessions.

All new frontend features that interact with protected APIs should reuse the shared auth utilities and the canonical query/mutation transport helpers so token handling, logout, and error behavior remain consistent across player, admin, and creator experiences. In particular, the shared browser API layer should interpret canonical error codes from backend services as follows:

- `AUTH_TOKEN_EXPIRED` – Clear in-memory auth state, redirect to login, and show a “Session expired” message.
- `AUTH_SESSION_REVOKED` – Clear in-memory auth state, redirect to login, and show a security-focused message (for example, “You were signed out because your account security changed.”).
  - `JOIN_REQUIRED` – Preserve in-memory authentication when an otherwise eligible public-production request does not admit because `membershipExists=false`, mark the affected admission unavailable, and do not expose an actionable `Join & Play` retry while explicit `JOIN` and the required membership reread are unimplemented. An existing response with `gameplayAdmissionAllowed=false` remains the established denial and is not a `JOIN_REQUIRED` or lifecycle-state result. Keep character discovery/creation, connect-token issuance, and `PLAY` blocked; do not retry those operations automatically or create membership implicitly. When implemented, explicit `JOIN`/`Join & Play` creates missing membership or restores target-only `INACTIVE`; private/playtest targets currently retain `WORLD_ACCESS_DENIED` for missing or non-admitting membership, while target behavior uses `NON_PUBLIC_ENROLLMENT_REQUIRED`, remains separately gated by the current grant, and never uses public `JOIN` as a fallback.
  - `AUTH_UNAVAILABLE` – Keep in-memory auth state and show a retriable authentication-service availability message for ordinary API calls when authority availability is `UNAVAILABLE` for live membership, registry, or another required non-routing, non-entitlement authority. Reachable invalid pointer evidence uses `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out pointer authority uses `AUTH_UNAVAILABLE`; entitlement authority outage uses `ENTITLEMENT_UNAVAILABLE`. None may be relabeled as a billing block. An unavailable authority is not equivalent to missing or non-admitting membership and must not produce `JOIN_REQUIRED`. Block only the affected authority-dependent or billing-unsafe mutation, including gameplay-admission mutations, until authority recovers; billing-safe operations that remain permitted under `TENANT_BILLING_BLOCKED` remain available unless they independently require the unavailable authority. Retry only idempotent reads, or mutations carrying the same stable request idempotency key that the service durably deduplicates, with at most one automatic retry inside a five-second total deadline and bounded backoff. The dedicated logout controller uses its separate bounded logout/revocation budget and is excluded from this generic retry rule. Never replay an unkeyed or non-idempotent mutation, and do not convert this infrastructure failure into logout.
- `ENTITLEMENT_UNAVAILABLE` – Preserve authentication state and show a retriable entitlement-availability message when entitlement authority is `UNAVAILABLE` or the required fresh Account entitlement evaluation cannot be established. Block only the affected admission, capacity, or other entitlement-dependent operation; retry under the same idempotency and bounded-backoff rules as `AUTH_UNAVAILABLE`. Do not relabel the outage as `TENANT_BILLING_BLOCKED`, `AUTH_UNAVAILABLE`, or `JOIN_REQUIRED`, and do not use stale entitlement state unless the exact bounded same-binding continuity contract applies.
- `TENANT_BILLING_BLOCKED` – Keep the user logged in, mark the denied admission, capacity, or mutation operation as billing-blocked in UI state, and show a prominent billing banner. This is not a blanket tenant gameplay shutdown: preserve connected sessions, eligible same-session resume, permitted `past_due` gameplay, and billing-safe operations such as viewing invoices or updating payment details; `grace` preserves connected sessions and same-session resume but denies first-time public join, first/new gameplay bindings, new instances, scale-out, and quota growth. Apply this code only when authoritative Account evidence explicitly denies that operation; `ENTITLEMENT_UNAVAILABLE` takes precedence when entitlement authority cannot be obtained, while `AUTH_UNAVAILABLE` remains the corresponding non-entitlement authority outage. Neither unavailable result may be converted into a billing block.
- `ADMISSION_POINTER_UNAVAILABLE` – Keep the current auth state for reachable missing, malformed, ambiguous, stale, or otherwise contract-invalid catalog/pointer evidence, show a retriable gameplay-admission unavailable message, rerun bootstrap discovery or reconciliation, and only then retry lobby admission with bounded backoff; never substitute a cached tenant or runtime target. Unreachable or timed-out pointer authority is `AUTH_UNAVAILABLE` instead.
- Target-state `REALM_UNAVAILABLE` – Keep the current auth state when the resolved realm pointer is complete and `CLOSED`, create no gameplay binding, and wait for fresh discovery or an authoritative availability change rather than fast-looping retries. This is distinct from reachable invalid pointer evidence (`ADMISSION_POINTER_UNAVAILABLE`) and unreachable or timed-out pointer authority (`AUTH_UNAVAILABLE`).
- `CONNECT_CONTEXT_INVALID` – Keep the current auth state, force gameplay reconnect flow (`connect-token` refresh + new socket + `LOGIN`), and block `PLAY` retries on the current socket.
- `CONNECT_SCOPE_MISMATCH` – Keep the current auth state, discard the entire discovery snapshot bundle and all connect-token metadata derived from it, including `catalogRevision`, rerun bootstrap discovery, and do not expose an actionable current repair because explicit join is unimplemented. Target behavior additionally completes `Join & Play` when the newly selected public target requires membership, performs required character discovery/creation, requests a fresh connect token only from the newly returned bundle, and retries on a new socket.

Client rule for discovery-issued selectors:

- Treat `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `evaluatedAt`, and `connectScopeExpiresAt` as one short-lived snapshot bundle for the selected realm target.
- Do not mix a stale `connectScopeId` with newly cached realm metadata or vice versa.
- When `connectScopeExpiresAt` has passed, rerun discovery before requesting another connect token even if the visible selection has not changed.

For gameplay WebSocket handshake failures on `/ws/game/**`, capable non-browser clients and operator tooling may differentiate HTTP `403` handshake classes:

- `CONNECT_TOKEN_MISSING`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_EXPIRED`: request a fresh connect token and retry the socket open with bounded backoff.
- `CONNECT_TOKEN_REPLAYED`: request a fresh connect token and retry with bounded backoff; repeated replay failures should surface a session-recovery action instead of fast-looping.
- `CONNECT_SCOPE_MISMATCH`: discard the entire failed discovery snapshot bundle and all connect-token metadata derived from it, including `catalogRevision`, rerun bootstrap discovery, complete target-only `Join & Play` and required character discovery/creation when applicable, then request a fresh connect token from the newly returned scope and retry on a new socket. Do not reuse any failed bundle field or infer a target from cached tenant/realm fields.
- `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`: keep the current auth state, show a temporary edge-auth-unavailable message, and retry with slower bounded backoff.
- `CONNECT_TOKEN_REJECTED` with `reason=unsupported_carrier_or_route`: only a first-party client that accidentally used the `non_first_party_public` header carrier may switch to the supported cookie path and retry without refreshing the token. Any other client or unsupported route/carrier must stop with a non-retriable unsupported-route/carrier error. With `reason=invalid_token_content`, request a fresh token and retry with bounded backoff, restarting the first-party bootstrap flow if rejection repeats. Do not infer the reason from HTTP status alone.
- `POLICY_DENY`: treat as non-retriable until configuration is corrected and surface an actionable error.

These handshake classes are edge-handshake outcomes, not gameplay text-protocol `ERROR <CODE>` frames. Clients only start handling protocol-level `ERROR <CODE>` responses after the WebSocket has been established and `LOGIN`/`PLAY` exchange begins. If Game Session rejects the signed context after the socket opens, the browser observes the existing `ERROR CONNECT_CONTEXT_INVALID reason=<bounded-reason>` protocol frame and the socket close; the bounded reasons are `missing_field`, `wrong_field_type`, `altered_claim`, `unbound_claim`, `invalid_signature`, `unknown_kid`, `expired`, `audience_mismatch`, and `recipient_mismatch`. Before HTTP 101, browser WebSocket APIs cannot read the Gateway response status or headers, so the browser treats the token as potentially consumed, discards the failed discovery/connect-token bundle, reruns bootstrap discovery, requests a fresh token, and retries with bounded backoff. Repeated failure surfaces generic connection/session-recovery guidance; detailed handshake classes remain for capable non-browser callers and operator telemetry.

### Current Implemented Browser Reconnect Behavior

Current first-party reconnect proof is limited to the protected-cookie path. When an unexpired in-memory `player-bootstrap` token remains, the client may request a fresh connect token for the current discovery scope, open a new `/ws/game/**` socket with the HttpOnly cookie, send bare `LOGIN`, and issue `PLAY` only when the current Account membership/admission fields and the other implemented checks pass. A page reload or expired bootstrap token restarts `POST /auth/player-bootstrap` and discovery. Current `CONNECT_SCOPE_MISMATCH` handling discards the stale bundle and reruns discovery, but missing or non-admitting membership remains unavailable/non-actionable because explicit `JOIN`/`Join & Play` and the target issuance reread are not implemented. This behavior does not prove the target lifecycle, grant, generation, or independent membership-version reconnect contract.

### Target-Only First-Party Browser Reconnect Sequence

The internal `tenantId` and `gameInstanceId` values in this example use the UUID-governed target-state contract. Some current runtime DTOs remain numeric migration gaps; clients must use the stable world/realm selectors and opaque `connectScopeId` rather than depending on either representation.

The following target-only returning-member example omits conditional `Join & Play` because it assumes existing target-valid membership. For a newly discovered public target that requires membership, complete `Join & Play` and required character discovery or creation before requesting the fresh connect token.

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]  # abbreviated world list; full realm snapshot follows

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [{
   worldSlug: "demo",
   realmSlug: "production",
   displayName: "Live Realm",
   tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
   gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
   connectScopeId: "cs_demo_production_v17",
   pointerVersion: 17,
   catalogRevision: 42,
   evaluatedAt: "2026-08-02T00:00:00Z",
   connectScopeExpiresAt: "2026-08-02T00:00:30Z"
 }]

GET /auth/bootstrap/worlds/demo/realms/production/characters?connectScopeId=cs_demo_production_v17
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara", playableStateScope: "PLAYABLE_STATE_SCOPE_SHARED" }]  # server-derived response projection from the exact realm snapshot; never a caller-selected query or join field

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, expiresAt, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Entered world: Demo World / Live Realm as Mara

...socket drops...

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-reconnect-2" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, expiresAt, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
PLAY demo production Mara
OK PLAY Resumed session
```

This reconnect flow assumes the player still holds a valid in-memory `player-bootstrap` token. If the bootstrap token has expired or the page was reloaded, the client must obtain a new bootstrap token first, then repeat bootstrap discovery as needed and request a fresh connect token. A new connect-token issuance after the prior request committed or its token was consumed uses a new `requestId`; the prior request ID is reused only to reconcile that same pending or otherwise indeterminate issuance. There is no separate silent refresh/bootstrap-restoration mechanism in the current architecture; reload behaves like bootstrap-token loss and restarts the first-party bootstrap flow. In all cases, first-party reconnects must not prompt the browser to replay Telnet-style `LOGIN <email> [secret]` credentials after bootstrap has been re-established. Clients may skip visible `WORLDS` / `REALMS <world>` / `CHARS <world> [realm]` steps only while the complete discovery snapshot is present and unexpired and fresh authority confirms current `ACTIVE` membership plus a valid current character for the selected target; a remembered world/realm/character choice alone cannot authorize the shortcut. Otherwise they must rerun discovery and the applicable join and character flow before driving the canonical `PLAY <world> [realm] [character]` selection on reconnect.

If any reconnect attempt returns `CONNECT_SCOPE_MISMATCH`, the shortcut above is no longer valid: the client must discard the entire cached discovery snapshot bundle and all connect-token metadata derived from it, rerun bootstrap discovery, complete the applicable target-only `Join & Play` and required character discovery or creation, and use only the newly returned world/realm/character and scope values before requesting a fresh connect token. No field from the failed bundle, including `connectScopeId`, `tenantId`, `worldSlug`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `evaluatedAt`, or `connectScopeExpiresAt`, is sufficient to reconstruct or attest a target.

The logout ordering above uses `reconciliation` to mean an unauthenticated or identity-recreating attempt. A matching lifecycle reconciliation through the Account-issued opaque `logoutHandle`, exact request tuple, exact token identity and digest, and current issuance fence is explicitly permitted while `PENDING`; it may only complete or abort that logout operation and can never restore authority or issue a replacement credential.

## API Usage Patterns

All API communication should be handled by **TanStack Query**-backed fetch/mutation helpers defined in `src/api/` or feature-local query modules. Endpoints such as bootstrap discovery, login/logout, world browsing, and admin control-plane reads should follow the same typed-query pattern.

`TanStack Query` is responsible for:

- Data caching and revalidation
- Request deduplication
- Error and loading state tracking
- Background polling and refetching

WebSocket interactions for real-time gameplay are handled by `src/websocket.ts`, which manages the connection lifecycle and message routing. Query-backed browser state should react to socket events through targeted cache updates or invalidation rather than an assumed global Redux store.

## Hosting Direction

FireMUD has two different priorities in this area:

- The first reviewer-usable preview proof path should be **TCP/Telnet first**, because that is the lowest-friction manual proof path for core MUD play and does not require browser-bootstrap work before hosted validation is possible.
- The long-term first-party browser direction should still be a **dedicated first-party web application service**, not product-specific frontend/helper logic embedded permanently in Spring Cloud Gateway.

This means the intended sequence is:

1. Hosted preview deployment supports manual `LOGIN -> PLAY -> LOOK` proof over the TCP Proxy Service.
2. A dedicated first-party web application service is introduced after that preview proof path is working.
3. The first UI hosted by that service may simply be a terminal-style browser client, with richer first-party UI following later.

## Build Tooling

The frontend uses **Vite** for fast development and production builds:

- `npm run dev` starts the local development server with hot module replacement.
- `npm run build` produces an optimized bundle under `dist/`.
- `npm run preview` serves the production bundle locally for verification.
- `npm run test` runs unit tests with Jest and React Testing Library. The script runs the test suite.
- `npm run lint` and `npm run format` ensure consistent code style.
- `npm run format:fix` writes formatting changes back to disk.
- `npm run accessibility` audits the compiled site with axe-core. See [Developer Setup](../../DEVELOPER_SETUP.md#frontend-lint--accessibility) for Chrome requirements.

See `web-client/README.md` for additional setup tips.

TypeScript configuration lives in `tsconfig.json`, and ESLint/Prettier enforce coding standards consistent with the rest of the project.

`TanStack Query` should be the default browser server-state substrate for new frontend work. API code generation and mocking can be extended using **msw** (Mock Service Worker) for testing. If future editor/admin work truly needs a broader client-state layer, that work should document why local feature state plus `TanStack Query` is no longer sufficient before introducing Redux or another global state tool.

## Game-Specific Customization

See [Game Customization](./system-architecture-game-customization.md) for the broader design.
Game-specific themes rely on the multi-tenant model described in [Multi-Tenancy](./system-architecture-multi-tenancy.md).

FireMUD aims to let each hosted game supply its own UI styling and layout tweaks.

- When a game version is published, branding assets and a `manifest.json` are
  uploaded to tenant- and version-scoped object storage (e.g., S3, MinIO, or a
  CDN).
- Published version metadata stores the manifest URL. At runtime the React app
  fetches this manifest to load logos, favicons, theme JSON, and optional route
  definitions, then applies Material-UI overrides.
- The current admitted realm bundle is the source of truth for branding. `PLAY`
  success, reconnect resume, and any realm switch must provide the resolved
  bundle identity (`versionId`, optional `scriptPatchVersion`, and manifest
  location/hash or equivalent), and the client must swap theme assets whenever
  that resolved bundle changes.
- Assets are loaded directly from the CDN or via the gateway's `/assets/**`
  route when a self-hosted MinIO instance is used; the Game Design Service is
  never queried during gameplay.
- If the manifest omits an asset, the default platform styling is used.
- Core components remain shared so feature updates reach all games without
  forks.

## Internationalization Strategy

The React client uses **react-i18next** to load translation JSON files at runtime. Players select a language in the settings menu, and the UI strings update without a page reload. Locale files live under `src/i18n/` and can be extended by hosted games.

## End-to-End Testing

**Playwright** tests exercise key flows by starting the Docker Compose stack and running a headless browser against the web client.

---

This architecture keeps the web client modular and maintainable while aligning with the backend microservices. Additional frontend services or features should follow the same separation of concerns: browser assets and first-party web bootstrap belong in the dedicated web application service, while Spring Cloud Gateway remains the public API/gameplay edge.
