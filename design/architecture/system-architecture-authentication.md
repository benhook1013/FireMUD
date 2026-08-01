# FireMUD System Architecture: Authentication & Authorization

This document describes how FireMUD authenticates clients, issues the exact JWT profiles defined by the token contract, manages session state, and enforces role-based access across services.

Current gameplay authentication supports `LOGIN <email>` / `LOGON <email>` to request a verified-email login code and `LOGIN <email> <secret>` / `LOGON <email> <secret>` for an immediate password-or-code attempt on raw Telnet and other non-WebSocket text clients; first-party WebSocket gameplay uses bare `LOGIN` only after its verified bootstrap/connect context. Bare raw-text `LOGIN`/`LOGON` prompt exchange is target-only and currently returns `PROMPT_LOGIN_UNSUPPORTED`. Control-plane clients use `/auth/login`, while first-party gameplay clients use `/auth/player-bootstrap` plus connect-token flows. Clients are stateless; server-side “sessions” are split between gameplay bindings in Redis and short-lived issued-token registry records in Coordination Redis. The Game Session Service restores gameplay session state from Redis, while the Account Service validates the supplied login secret and issues the exact `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT profile required by the destination. Raw Telnet gameplay command streams never carry JWT authorization. Browser and mobile-browser gameplay clients temporarily use a `player-bootstrap` JWT for HTTPS bootstrap calls and a cookie-carried one-use connect token for the `/ws/game/**` handshake; first-party native-mobile clients that use a cookie jar remain cookie-only. Public non-browser issuance and the dedicated handshake-header carrier are target-only and unavailable until a dedicated route is fully registered and its issuance, registry, profile, and carrier proof is complete; the target design uses protected secure storage plus `X-Firemud-Connect-Token`. First-party admin/creator UIs and backend services use their own permitted token profiles. Provider-specific HTTPS sign-in remains unavailable until the provider-specific verification, collision, recovery, and end-to-end proof required by [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md) is complete.

Optional provider-specific HTTPS sign-in is permitted only after complete provider proof; password and verified-email code remain the Account-owned baseline and fallback, and Telnet never carries provider credentials. See [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md).

The current raw-text email-code challenge is two commands: `LOGIN <email>` (or `LOGON <email>`) requests the verified-email code without authenticating the session, then `LOGIN <email> <code>` (or `LOGON <email> <code>`) submits that code. The separate immediate-auth form is `LOGIN <email> <secret>` (or `LOGON <email> <secret>`) with the account-selected password or already-issued email code.

## Normative Target Contract

The canonical target is Account-owned authentication and authority, exact JWT profiles, explicit membership before gameplay admission, caller-bound tenant authorization, and concrete mTLS identity for internal workload calls. Global roles authorize only the route classes declared by the shared matrix; they do not create tenant membership or gameplay authority. A protected route, bootstrap operation, or connect-token issuance fails closed when its exact authority, scope, replay, or freshness evidence is unavailable or ambiguous.

### Canonical Authority Tuple

The canonical `authorityTuple` schema, exact nested field names, profile requirements, and registry mapping are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#canonical-authority-tuple). This document does not duplicate that schema. Authentication applies the canonical tuple unchanged to JWT claims, issued-token records, revocation events, Account leases, gameplay bindings, refresh requests, rebind proofs, and installation acknowledgements; a missing applicable field, extra scope, malformed value, or mismatch fails closed.

Authentication-local rules are that Account owns every canonical authority-generation field and every canonical `session:auth:generation:*` projection, while Game Session may keep only the derived consumer-local issuer projection defined by [ADR 0022](./decisions/adr-0022-account-authority-and-gameplay-session-ownership.md). The `billing_safe_tenant` route exception omits target-tenant generation comparison and permits a billing-safe-only token to omit that tenant-authority key; it never removes the required caller-membership-generation or `membershipVersion` key. `membershipVersion` remains separate membership projection/version data and never substitutes for `membershipAuthorityGeneration`.

## Implementation Status

- **Target only:** Multi-line interactive raw-text `LOGIN`/`LOGON` flows (email then secret prompts) are not the current gameplay login; bare raw-text invocation currently returns `PROMPT_LOGIN_UNSUPPORTED`.
- **Target only/currently unavailable:** The independently enrolled TOTP-backed `privileged_control` elevation window for `platformAdmin` and cross-tenant `billingAdmin` is not implemented; current runtime must not claim those elevated paths are available.
- **Current fail-closed boundary:** Any route branch that requires the unavailable `privileged_control` window must reject that `platformAdmin` or cross-tenant `billingAdmin` authorization path. A separately declared tenant-role or unelevated `support` branch may remain available only when all of its own live checks pass.
- **Current supported challenge flow:** Raw text clients use `LOGIN <email>` / `LOGON <email>` to request a verified-email login code, then `LOGIN <email> <code>` / `LOGON <email> <code>` to submit it. **Current supported immediate-auth flow:** `LOGIN <email> <secret>` / `LOGON <email> <secret>` authenticates immediately with the account-selected password or an already-issued verified-email code.
- Character selection and gameplay takeover semantics are canonicalized on `{tenantId, gameInstanceId, characterId}`.
- **Current versus target gameplay opening:** Email challenge and credential-bearing `LOGIN` / `LOGON` are the implemented raw-text authentication paths. The complete first-party browser/mobile sequence using `POST /auth/player-bootstrap`, explicit `POST /auth/bootstrap/join`, `POST /auth/connect-token`, bare `LOGIN`, and then `PLAY` remains target-only as a complete flow; individual bootstrap/connect-token endpoints are partial implementation surfaces. The target `JOIN_REQUIRED` outcome remains canonical, but current connect-token issuance and text `PLAY` may still invoke Account's `EnsurePublicProductionPlayerMembership` and implicitly create missing public-production membership until convergence. Explicit `JOIN` / `Join & Play` and the required `membershipAuthorityGeneration` reread at connect-token issuance are both unimplemented/proof gaps. Returning active members and grant-backed private/playtest callers remain separately eligible only when their own current membership, grant, entitlement, routing, generation, and version checks pass.
- **Target-only/currently unavailable public non-browser issuance:** Public non-browser connect-token issuance and `X-Firemud-Connect-Token` carriage are not current supported capabilities. They may be exposed only after a dedicated route is fully registered in the authorization matrix and its profile, audience, active issued-token record, generation/evidence, response, and carrier proofs are complete. The target `gameplay-connect` profile and protected-secure-storage/header design remains documented below, but current clients must not be told that header support exists.
- First-party browser and mobile-browser gameplay use the short-lived `player-bootstrap` JWT for HTTPS bootstrap calls and carry the resulting gameplay-connect token only in the `Firemud-Connect-Token` HttpOnly cookie. First-party native-mobile and other first-party non-browser clients using a cookie jar remain cookie-only. Telnet and other non-WebSocket text transports use credential-bearing `LOGIN` and do not carry public JWTs or connect tokens.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook, but current role-refresh token regeneration and periodic active-session `game-session-account-delegation` rotation remain implementation gaps; the placeholder response is not proof of refresh.
- The current Account `Authenticate` proto path still lacks the target `requestId`/immutable-digest replay envelope and orphan-token retirement contract described below; those fields and recovery semantics remain implementation/proof gaps rather than implied current behavior.
- Account's JWKS endpoint and conditional secret watcher are implemented, but Account-only asymmetric validation, non-exportable signer delegation, rotation/convergence, issued-token registry enforcement, and Account-owned authority generations remain target-state. No authority-generation issuance, advancement, propagation, or validation proof is currently claimed.

## Contract Decisions (Normative)

The following contract decisions are mandatory and resolve cross-document ambiguity:

- **Authority-generation writer** – The Account Service owns durable issuer, account, tenant, and `{accountId, tenantId}` membership authority generations and is the sole writer of every applicable canonical `authorityTuple` field, cutoff, and `issuanceFence`, plus their canonical `session:auth:generation:*` projections. Account commits the durable tuple/fence, applicable account-security or tenant-billing cutoff, and monotonic outbox event together; the projection is an asynchronous outbox output, not part of that atomic durable transaction. Consumers fail closed while the projection or its freshness/source evidence is missing, stale, malformed, regressed, or ambiguous. Other services must publish billing/security events and must not write canonical authority-generation state or projection keys; Game Session may write only its distinct derived issuer projection under the ADR 0022 schema.
- **Issuer projection checkpoint** – The Redis issuer-generation projections use `lastAppliedSourceOutboxSequence`, `lastAppliedSourceEventId`, and `lastAppliedSourceEventDigest` for the exact Account authority outbox stream. Sequence `0` is permitted only when that stream has no committed event; at that baseline, `lastAppliedSourceEventId` and `lastAppliedSourceEventDigest` are omitted from the projection, never serialized as `null`, an empty string, a zero digest, or another sentinel. For every positive sequence, both source-event fields are required non-null values in their canonical formats, and the projection advances only on the next contiguous sequence after exact checkpoint validation. These are projection-local replay/checkpoint fields, not JWT claims; JWT claims carry only the applicable authority tuple, including `issuerAuthGeneration`.
- **Authority outbox stream** – Account emits each revocation payload on exactly `account:auth-authority:v1:<scopeId>`, where `scopeId` is `issuer/<issuerId>`, `account/<accountId>`, `tenant/<tenantId>`, `membership/<accountId>/<tenantId>`, or `grant/<accountId>/<tenantId>/<worldSlug>/<realmSlug>`. `outboxSequence` starts at `1` independently for each exact stream key; `tenantBillingSequence` remains the separate tenant billing sequence. Consumers keep watermarks per exact stream key: duplicates with matching evidence at or below the watermark are no-ops, the next sequence is contiguous, a higher sequence is a gap only within that same key, and an unrelated scope never creates a gap. Conflicting duplicate evidence or a same-key gap stops affected validation/admission until Account reconciliation proves the exact checkpoint.
- **Authority replay evidence** – Consumers retain only a finite, deployment-configured replay-evidence window per exact `outboxStreamKey`, with a maximum sequence count and retention TTL, keyed by `outboxSequence`; each retained entry includes `eventId`, the canonical event digest, `sourceScope`, the complete applicable `authorityTuple`/`issuanceFence`, and the relevant event payload evidence. When a duplicate at or below the watermark falls outside that window, the consumer must use an Account-owned authoritative lookup for the exact `(outboxStreamKey, outboxSequence)` and compare the supplied evidence for exact equality. Matching evidence is accepted as a no-op; unavailable lookup is retryable `AUTH_UNAVAILABLE` and keeps the affected scope fail closed; reachable missing or conflicting evidence is quarantined for reconciliation and fails closed. Advancing a watermark never makes an unverified older duplicate acceptable.
- **Membership freshness evidence** – Any authoritative Account membership response/evidence used for billing-safe checks, gameplay admission, reconnect, or revocation must carry or authenticate the complete applicable `authorityTuple`, exact `membershipLifecycleState`, separate `membershipVersion`, `evaluatedAt`, and the matching `outboxCheckpoints` entry from one authoritative Account snapshot/transaction or equivalent Account-owned fence. The target response fields are `accountId`, `tenantId`, `membershipExists`, `membershipLifecycleState`, `roles[]`, `gameplayAdmissionAllowed`, `membershipVersion`, `membershipAuthorityGeneration`, `authorityTuple`, `evaluatedAt`, and `outboxCheckpoints[]`; `membershipAuthorityGeneration` is the tuple's membership member, and a separately carried copy must authenticate the same value. The matching checkpoint is `{outboxStreamKey: account:auth-authority:v1:membership/<accountId>/<tenantId>, outboxSequence: <committed sequence>}`. `evaluatedAt` describes that complete snapshot and is not restamped from a projection or cache. Missing fields, a checkpoint that does not cover the tuple, lifecycle state, or membership version, or values assembled from different checkpoints are invalid and fail closed.
- **Membership event continuity** – Membership changes use only `account:auth-authority:v1:membership/<accountId>/<tenantId>`. Each event names `outboxStreamKey`, `sourceScope` (`membership/<accountId>/<tenantId>`), `outboxSequence`, `eventId`, the complete applicable `authorityTuple`/`issuanceFence`, and the Account snapshot's `accountId`, `tenantId`, `membershipVersion`, membership payload, admission flag, and `callerBoundAuthorityInvalidated` result. `sourceScope` must decode to `outboxStreamKey`; each consumer stores a `watermark` and event identity/digest per exact stream and marks delivery `contiguous` only when the next sequence is `watermark + 1` (with first-stream initialization from the Account checkpoint). A duplicate at or below the watermark is valid only when all evidence matches; a same-sequence conflict quarantines the affected scope. A higher sequence is a gap only within that same stream; unrelated streams do not advance or gap this watermark. Conflicts, non-contiguous delivery, missing or mismatched tuple/checkpoint evidence, or an unresolved membership-version/generation pair stop affected admission and reconnect/validation until exact Account reconciliation proves coverage; unavailable reconciliation returns retryable `AUTH_UNAVAILABLE`, while reachable contradictory or revoked evidence is denied/revoked.
- **Authority validation outcomes** – A registry, lease, gameplay binding, token-identity fence, Account authority source, or authority-projection freshness fence that cannot be reached or times out is a retryable `AUTH_UNAVAILABLE` / HTTP 503 condition; this includes runtime membership-authority reads. It does not revoke the client's authentication and no cached authority may authorize the failed operation. Reachable missing, malformed, expired, revoked, stale, regressed, or mismatched evidence is an authentication failure (`AUTH_SESSION_REVOKED` or the specific invalid-token outcome) and requires reauthentication. Registry presence or absence never overrides this classification. `MEMBERSHIP_AUTH_UNAVAILABLE` is not a second canonical application error for an unavailable membership-authority dependency.
- **Tenant authority-generation scope** – `session:auth:generation:tenant:<tenantId>` applies by default to tenant-scoped regular and gameplay-affecting operations. Only the closed route-class allowlist in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#explicit-route-class-generation-allowlist) may omit the target tenant generation. The `billing_safe_tenant` class intentionally omits that target-tenant generation only under this closed exception; it still requires issuer/account and caller-bound membership generations plus a live `tenantAdmin` check. `cross_tenant_support_safe` requires issuer/account generations, a live `support` role without elevation or an explicitly allowed `platformAdmin` role with `privileged_control` backed by independent TOTP, and global token scope; and `cross_tenant_billing_safe` requires issuer/account generations, a live `billingAdmin` or explicitly allowed `platformAdmin` role, global token scope, and `privileged_control`. None of these routes may use cached authorization, and no newly named route class inherits the allowlist.
- **Membership authority-generation scope** – `session:auth:generation:membership:<accountId>:<tenantId>` applies to caller-bound tenant authorization for one account in one tenant and advances when membership or tenant roles change without triggering a tenant-wide billing cutoff.
- **Gameplay session identity key** – Session uniqueness and takeover scope are keyed by `{tenantId, gameInstanceId, characterId}`.
- **JWT claim contract** – Services must validate a strict JWT claim profile (required claims and audience per token profile), not only signature plus ad-hoc fields. Registry-backed profiles carry the complete `authorityTuple` and positive `issuanceFence` with the exact nested names above. `control-ui` must include `scopedRoles` as `{}` when empty; omission is rejected. Its `tenantAuthorityGeneration` keys equal the non-empty scoped-role tenant keys whose allowed routes require target-tenant authority, while its independent `membershipAuthorityGeneration` and `membershipVersion` keys equal the caller-membership tenants needed by the token's allowed route classifications, including a `billing_safe_tenant` key that deliberately has no target-tenant authority entry. Explicitly unscoped profiles use empty maps/lists, never absent or fabricated scope values.
- **Authority tuple comparison** – Every applicable `issuerAuthGeneration`, `accountAuthorityGeneration`, exact tenant and membership map entry, private-realm `grantVersion`, `accountSecurityCutoff`, and `tenantBillingCutoff` is compared as part of one tuple. `membershipVersion` is a separate current/stored membership projection check and is never substituted for `membershipAuthorityGeneration`; a tuple, cutoff, fence, or membership mismatch fails closed.
- **Internal gameplay delegation boundary** – Gameplay services authenticate the concrete mTLS workload identity, enforce an exact method-level caller allowlist, and validate a typed `PlayerExecutionContext` against request and domain scope.
- **No universal player attestation** – Routine gameplay delegation does not use signed per-action player attestations or a replay cache. Mutation replay is controlled by the owning command/effect/request idempotency contract.
- **Route classification governance** – Protected routes must be classified in the shared route matrix document and enforced through middleware annotations/interceptors; behavior must not rely on per-service ad-hoc interpretation.
- **Gameplay session ownership** – Game Session owns gameplay binding records, the binding CAS, and bounded secondary indexes that map gameplay bindings by uniqueness key, account/tenant scope, and tenant scope so takeover, reconnect, and revocation do not require scans. Account owns the admission decision and exact-binding lease only; Account finalization commits lease/decision evidence and never creates, deletes, or mutates Game Session bindings or indexes. Orphan evidence from Account reconciliation becomes a durable cleanup request that Gateway and Game Session consume under their respective edge and gameplay fences.
- **Gameplay admission semantics** – `LOGIN` authenticates account identity, while `PLAY` binds gameplay identity and gameplay scope. These must remain distinct concepts even when a client UX makes them feel nearly back-to-back.
- **Ordinary gameplay authentication** – Each gameplay `LOGIN` uses one account-selected mode, either `PASSWORD` or verified `EMAIL_OTP`. Account configuration may expose both choices separately, but no combined password-plus-email-OTP request is part of the wire contract. Gameplay authentication does not perform active-gameplay reauthentication or elevate the gameplay session into account/control-plane authority; HTTPS step-up remains separate.
- **Ingress identity validation** – Public and cross-service readers validate the declared shape of UUID-governed identifiers before authorization or lookup, then treat the values as opaque. Identifier contents never confer authority or determine tenant scope.

## Responsibility Split

- **Account Service** – Verifies login secrets according to account-selected password/email-code modes, issues JWTs, and remains authoritative for signing-generation validation, token-validation semantics, signer promotion, JWKS publication, and public/private pruning. A non-exportable signer may perform only private-key operations delegated by Account.
- **Game Session Service** – Fronts the `LOGIN` command, stores gameplay session context in Redis, and rebinds sockets on reconnect.
- **Spring Cloud Gateway** – Pass-through for gameplay login and admin/meta flows; enforces auth header presence on protected control-plane routes but does not validate control-plane JWTs. The deliberate exception is `/ws/game/**` edge admission: Gateway validates short-lived gameplay connect tokens, performs replay checks, and emits a signed connect context for Game Session as specified in [Gateway Architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake).

[ADR 0022](./decisions/adr-0022-account-authority-and-gameplay-session-ownership.md) is the authority for this ownership split. Current implementation gaps in authority-generation enforcement, monotonic membership versions, or gameplay token storage do not transfer authority to another service.

### Client Classes and Token Carriage

- Telnet and other non-WebSocket text clients use `LOGIN <email>` followed by `LOGIN <email> <code>` to complete a verified-email challenge, or `LOGIN <email> <secret>` to authenticate immediately. They do not receive or transmit `control-ui`, `player-bootstrap`, private delegation, or gameplay-connect JWTs. Any richer multi-line prompt flow is target-only and is not part of the current client capability contract.
- First-party browser and mobile-browser clients authenticate to `/auth/player-bootstrap`, keep that short-lived JWT in memory for bootstrap/discovery HTTP calls, and receive the gameplay-connect credential only as the HttpOnly `Firemud-Connect-Token` cookie. First-party native-mobile and other first-party non-browser clients using a cookie jar remain cookie-only. Public non-browser clients are target-only and unavailable until a dedicated issuance route is fully registered and proven; the target design uses protected secure storage and the dedicated header.
- Once that dedicated route is fully registered, explicitly classified non-first-party/public non-browser WebSocket clients may authenticate through the same bootstrap control plane and present the gameplay-connect token only through `X-Firemud-Connect-Token`. This is not current header support.
- After Gateway validates and consumes the gameplay-connect credential, public non-proxy WebSocket clients use bare `LOGIN` followed by `PLAY`; no transport sends an end-user JWT as gameplay command authorization.

The implemented account login modes are `PASSWORD` and verified-email `EMAIL_OTP`. Authenticator-app TOTP enrollment remains future account-security work; the REST and gRPC authentication contracts do not carry a separate `otp` field. Public player-facing text clients use Telnet-over-TLS, while plaintext Telnet is limited to local, test, and explicitly private-network compatibility. TOTP is not a transport gate or a substitute for channel protection; [ADR 0033](./decisions/adr-0033-public-player-facing-telnet-requires-tls.md) owns that boundary.

### Ordinary Login and Sensitive-Action Step-Up

- Ordinary Telnet, gameplay bootstrap, and account/control login use one account-selected mode per attempt: `PASSWORD` or verified `EMAIL_OTP`. Account configuration may expose both choices separately, but the wire contract does not combine the password and email code in one request. Gameplay never solicits TOTP or repeats account authentication per command, and a gameplay session cannot become elevated control-plane authority.
- Routine gameplay and ordinary tenant-scoped creator or moderation work rely on their existing authenticated session, tenant capabilities, route policy, and audit. They do not trigger an unexpected factor prompt.
- Account email/password/factor changes, external-identity changes, account deletion, new real-money charges, payment-instrument management, billing-owner transfer, and global administration complete only through the HTTPS account/control plane. The client may be web, native, or CLI; raw Telnet cannot complete them.
- Every HTTPS-sensitive action listed above, including global administration, requires recent ordinary reauthentication. **Target-only privileged elevation:** entering a bounded `platformAdmin` or cross-tenant `billingAdmin` elevated window additionally requires an independently enrolled TOTP. Account records the resulting role-scoped elevation as bounded server-side state tied to the current `control-ui` token and account authority generation; it is not a reusable JWT profile or gameplay authority. That factor is supplied once per elevated window rather than once per action and never appears in gameplay. The current runtime does not offer this elevation window.
- Gameplay may explicitly initiate a sensitive commercial or account action and receive a short-lived, single-use opaque HTTPS handoff URL. The handle grants no authority by itself and resolves to server-side intent bound to account, gameplay session, tenant where applicable, exact action, product and immutable amount/currency where applicable, and `requestId`. The HTTPS client independently authenticates, performs required step-up/provider work, and reports a verified idempotent outcome that gameplay may observe asynchronously.
- Spending an existing non-withdrawable premium balance remains gameplay. It requires exact purchase confirmation, idempotent identity, audit, and applicable caps, but no general account reauthentication. Withdrawal, cash redemption, or cash-equivalent transfer requires a new decision.

[ADR 0045](./decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) records this factor and protocol boundary.

Issued JWTs, registry records, authority generations, and token-profile validation rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This document still defines how those token contracts are applied to route classification, gameplay admission, and tenant authorization, but it no longer carries the full token catalog inline.

---

## Identity, Roles, and Tenant Access

Authentication always identifies a single platform account, represented by the `accountId` claim. Tenant-specific state and permissions are layered on top of this identity:

- `accountId` – Global platform identity managed by the Account Service.
- `globalRoles` – Cross-tenant roles such as `platformAdmin`, `billingAdmin`, or `support`.
- `scopedRoles` – A map from `tenantId` to roles granted to the account within that tenant (for example, `"tenant-abc": ["player", "designer"]`).

For the data model underpinning `accountId`, `tenantId`, characters, and membership relationships, see the [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) design.

### Role Model

FireMUD standardizes a small, explicit role set so tenant authorization and cross-tenant behavior remain consistent across services:

- **Global roles (`globalRoles`)**
  - `platformAdmin` – Full cross-tenant administrative access, including starting and stopping game instances, viewing cross-tenant analytics, and reading billing and subscription state for any tenant.
  - `support` – Limited cross-tenant support tools, subject to audit. Support roles may view high-level subscription state and entitlements for troubleshooting (for example, whether a tenant is `active` or `suspended` and what quotas apply), but cannot view detailed billing artifacts such as invoices or payment methods and cannot modify subscriptions.
  - `billingAdmin` – Cross-tenant access to billing-safe control-plane APIs (for example, viewing invoices, updating payment methods, and managing subscriptions) but no gameplay or design privileges.
- **Tenant roles (`scopedRoles[tenantId]`)**
  - `player` – Can join gameplay for the tenant subject to entitlements and quotas; no design, admin, or billing capabilities.
  - `designer` – Can edit design-time content for the tenant via Game Design tools; cannot control runtime instances or billing.
  - `tenantAdmin` – Owns the tenant in day-to-day operations: can manage game instances, configure runtime settings, and manage subscriptions and billing for that tenant.
  - `moderator` – Performs tenant-scoped moderation actions (for example, muting or banning players) but cannot alter billing or platform-wide configuration.

Services must not introduce ad-hoc roles without updating this model and the Tenant Authorization Contract. Where finer-grained behavior is required, services should prefer additional capabilities/flags derived from these core roles rather than inventing new top-level roles.

The tenant-role model is an accepted target decision, and tenantless control login with navigation-only tenant selection and reauthorization on each selected tenant is an accepted target decision under [ADR 0040](./decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md). These decisions do not claim complete implementation or proof: tenant-scoped role persistence and `scopedRoles` issuance, switching controls, and the associated end-to-end evidence remain tracked gaps in the [Player Access and Session implementation tracker](../project-management/implementation-tracking/player-access-and-session.md#capability-status), with focused evidence and missing proof listed under [Validation and Proof](../project-management/implementation-tracking/player-access-and-session.md#validation-and-proof).

### Global Role to Route-Class Matrix (Normative)

Global roles must not be interpreted as broad tenant access shortcuts. Authorization must use the route classification model, with the following mandatory limits:

| Global role | Allowed route classifications | Explicitly disallowed |
| --- | --- | --- |
| `platformAdmin` | `account_scoped` only when the exact route declares `platform_admin_override: platformAdmin_only`; `tenant_regular` (operational/control-plane only); `cross_tenant_support_safe`; `cross_tenant_billing_safe`; `cross_tenant_data_bearing` | `billing_safe_tenant` caller-bound variants; gameplay admission/switching |
| `billingAdmin` | `cross_tenant_billing_safe` | `tenant_regular`, `billing_safe_tenant`, `cross_tenant_data_bearing`, gameplay admission/switching |
| `support` | `cross_tenant_support_safe` | `tenant_regular`, `billing_safe_tenant` mutations, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, gameplay admission/switching |

Tenant-scoped operational/design/runtime actions may allow `platformAdmin` per the route-class matrix, but gameplay admission and gameplay switching must not. Anonymous `WORLDS` may expose only the bounded public-production catalog. Authenticated discovery may combine caller-bound membership with public-production visibility. Character access, character creation, connect-token issuance, and `PLAY` require an existing caller-bound membership plus any applicable non-public realm grant; global roles or public visibility alone must never grant gameplay access. The only membership-writing exception is explicit public-production `JOIN`/`Join & Play`, which may create the durable `player` membership only when it is missing. Private/playtest admission requires both existing membership and the current grant, and no grant, connect-token, character, or `PLAY` path may create membership implicitly. `billingAdmin` and `support` must never gain gameplay/design authority by virtue of being global roles.

Assurance is role-specific rather than route-label-specific: `platformAdmin` requires the bounded server-side `privileged_control` window with independent TOTP whenever a route explicitly allows that global role, including an explicitly allowed support-safe route; `billingAdmin` requires the same assurance on cross-tenant billing-safe routes; `support` uses its live global role and issuer/account/token checks without that elevation. A route must not treat the presence of `role_assurance` as elevating `support` when the route's predicate applies only to `platformAdmin` or `billingAdmin`.

Global roles also confer no authority after ordinary gameplay admission. Gameplay presence, commands, actor capabilities, and `PlayerExecutionContext` must ignore `globalRoles`; only explicit tenant-scoped gameplay grants may produce moderator, administrator, game-master, or equivalent in-world capabilities. A `platformAdmin`, `support`, or `billingAdmin` account that joins a public game without such a tenant-scoped grant appears and acts as an ordinary `PLAYER`. Break-glass platform operations remain separate audited control-plane actions and must not create a player actor or gameplay session.

The current target has no support impersonation, live-session attachment, or hidden-observer mode. Support uses minimized support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations. Adding any impersonation or observation product requires a new human-reviewed privacy, tenant-consent, notification, audit, and capability decision; implementations must not preserve speculative bypass hooks.

`account_scoped` routes are authorized by authenticated account context and explicit subject-binding rules. A `platformAdmin` override is not inherited from the route class: it is valid only when the exact route-matrix entry declares `platform_admin_override: platformAdmin_only`, and it additionally requires a valid server-side `privileged_control` window backed by independent TOTP.

### Tenant Authorization Contract

All meta/control services (Account, Game Design, Logging & Admin, and similar HTTP/gRPC APIs) must enforce a consistent tenant-authorization contract:

- Each incoming request uses the exact auth path for its route: normal account-bearing requests authenticate a single `accountId` with a JWT validated against the Account Service JWKS, while `pending_deletion_scoped` routes use only an opaque Account-owned pending-deletion credential validated against its server-side workflow registry.
- The effective tenant set for the request is derived from the token:
  - For tenant-scoped operations, the service computes the set of `tenantId` values from `scopedRoles` plus explicit global-role allowances from the route-class matrix above. Gameplay lobby/admission routes are stricter: they must derive authority from caller-bound tenant membership and `gameplayAdmissionAllowed`, not from global-role shortcuts. Billing-related global access must use explicitly cross-tenant billing-safe route variants.
  - For cross-tenant operations, the service must explicitly check that the caller has a `globalRole` that authorizes cross-tenant access for the specific API category (for example, only `platformAdmin` for gameplay- or data-bearing operations, `billingAdmin` or `platformAdmin` for billing-safe control-plane operations, and `support` or `platformAdmin` only for explicitly designated support-safe troubleshooting surfaces). Tenant-scoped roles must never implicitly grant cross-tenant privileges.
- For account-scoped operations, authorization must bind to authenticated `accountId` and route-level subject-binding rules, without deriving or requiring tenant scope.
- If an API accepts a `tenantId` (path, query parameter, or body field), the service must validate that:
  - `tenantId` is in the effective tenant set for tenant-scoped calls, or
  - The caller holds a cross-tenant `globalRole` that explicitly allows operating on the requested tenant.
- Services must apply the `tenantId` filter to all read and write queries, even when the client does not explicitly supply a `tenantId` (for example, when inferring tenant from a game instance).

A shared library helper (for example, a `TenantAccessGuard` used by `AuthTokenInterceptor`) should be used by all meta/control services so this contract is implemented in one place and kept in sync with future role/tenant model changes.

### Authentication Operation Paths (Normative)

Authentication is partitioned into four explicit paths; no path may substitute another path's authority:

- **JWT issued-token registry path** – JWT-presenting control-plane and bootstrap operations validate the exact token profile locally, require one matching `session:auth:token:<tokenHash>` record, and compare its tuple, cutoffs, fences, `membershipVersion`, and Account source/version/freshness evidence from one coherent Account snapshot. Registry absence or reachable invalid evidence denies; an unreachable or timed-out dependency is `AUTH_UNAVAILABLE`.
- **Pending-deletion workflow registry path** – `pending_deletion_scoped` routes use only the Account-issued opaque `pending-deletion-access` credential and its separate workflow registry. The validator binds the credential to the account and deletion workflow and checks live workflow state; it uses no JWT issuer/account/tenant/membership generations and never falls back to normal JWT or gameplay authority.
- **In-band gameplay bound-session path** – Non-JWT `LOGIN` establishes the authenticated Game Session socket/session context, and non-JWT `PLAY`, fresh admission, reconnect, resume, and rebind use the exact bound-session identity, binding fence, current Account membership/lifecycle/revocation authority, admission/resume lease, routing/ownership evidence, and CAS contract required by the gameplay path. Routine commands use the admitted binding, typed workload context, and bounded reconciliation rather than JWT registry middleware.
- **Gameplay-connect replay-fence path** – ADR 0029 intentionally owns the one-use `gameplay-connect` carrier, replay fence, quarantine, deny-marker, and exact-`jti` consume contract. The token is consumed only by Gateway and does not create or consult ADR 0035's Account issued-token registry; Game Session receives only the signed connect context. ADR 0022 owns the service-authority partition between those paths.

### Auth Middleware Algorithm (Normative)

Any HTTP/gRPC route that depends on identity, roles, or tenant scoping must be protected by the shared auth middleware (for example, `AuthTokenInterceptor` plus a `TenantAccessGuard`). Implementations must follow the same decision logic so authorization behavior does not drift across services:

1. **Validate the JWT** – Verify signature (JWKS), time-based claims (`exp`, `nbf`), and the expected token profile/audience (`aud`). Reject tokens with an unexpected profile (for example a `control-ui` JWT presented to a player-bootstrap endpoint).
2. **Check issued-token registry** – Compute `tokenHash` and require one matching `session:auth:token:<tokenHash>` record in Coordination Redis. Validate its account, profile, `jti`, per-lineage `tokenGeneration`, complete applicable `authorityTuple`, positive `issuanceFence`, applicable separate `membershipVersion`, and time fields against the already verified token. Lease, gameplay binding, token-identity fence, and membership-version evidence is required only when the token profile or exact route declares those predicates; every declared predicate and its applicable Account evidence must match current state. Reachable missing, stale, malformed, expired, revoked, or mismatched state means the token is revoked or unregistered and returns the canonical “session revoked” error (`AUTH_SESSION_REVOKED` or equivalent); an unavailable or timed-out dependency returns retryable `AUTH_UNAVAILABLE` and never uses cached state. This step applies only to a route that presents an issued JWT. Matrix-classified public no-JWT routes, the `pending_deletion_scoped` workflow credential, in-band `LOGIN`/`PLAY` and routine gameplay commands, and the exact `gameplay-connect` Gateway handshake use their separately defined paths above and never consult this registry. Fresh gameplay admission uses its bound-session contract. The intentionally separate ADR 0029 gameplay-connect path instead requires Account-signed claims, the current signed replay fence, quarantine cutoff, atomic exact-`jti` single-use replay consumption, Gateway deny-marker state, and bounded lifetime checks; ADR 0035 remains authoritative only for registry-backed JWT profiles. Logout retries use only their separate durable intent/tombstone reconciliation path and create no authorization context. No other JWT route or profile may bypass the registry.
3. **Check Account authority generations** – Enforce bulk revocation without relying on wildcard deletes, key scans, or JWT timestamps. Every route that presents an issued JWT applies one Account-owned composite comparison of every applicable `authorityTuple` field, `accountSecurityCutoff`, `tenantBillingCutoff`, `issuanceFence`, and separate `membershipVersion` evidence. The route matrix, not JWT presence alone, determines whether tenant authority, caller-membership authority, and `membershipVersion` are applicable. Public no-JWT routes, the `pending_deletion_scoped` workflow credential, in-band `LOGIN`/`PLAY` and routine gameplay commands, and `gameplay-connect` are outside this JWT generation step rather than exceptions to it; each follows its separately defined authority contract. In-band routine gameplay adds no per-command Account or Redis reads. Fresh `PLAY`, reconnect, and resume still perform the bound-session admission checks and only the current-authority checks explicitly required by those contracts. `gameplay-connect` omits the tuple and issuance fence and accepts the deliberate maximum 30-second lifetime plus at most five seconds of clock skew under ADR 0029's replay-fence, quarantine, deny-marker, and atomic-consume contract. Tenant and membership fields are additional route-class-specific checks; they do not replace the universal issuer/account checks on JWT-presenting routes. A refresh or competing revocation writer must use one serializable Account transaction or composite CAS/fence across every applicable field, not independent per-field updates. The route table distinguishes those conditional tenant and membership checks from the JWT path's universal checks:
   - `authorityTuple.issuerAuthGeneration` applies to every protected route and must advance for Account signing-key compromise or player-facing post-restore trust reset; it does not replace rejection of the affected `kid`.
   - `authorityTuple.accountAuthorityGeneration` applies to account-wide security cutoffs.
   - For routes classified as tenant-scoped regular or gameplay-affecting, the exact requested-tenant entry in `authorityTuple.tenantAuthorityGeneration` applies.
   - The exact caller-bound `{accountId, tenantId}` entry in `authorityTuple.membershipAuthorityGeneration` applies to tenant-scoped regular and billing-safe routes, caller-membership-scoped lifecycle routes, `player_bootstrap_tenant` routes where declared, and public-production onboarding after membership is created. The route-class table below is authoritative for each classification, and `membershipVersion` is checked separately.
   - The closed `billing_safe_tenant` exception intentionally omits the target-tenant authority generation; its issuer/account, caller-bound membership-generation, live-role, and route-class checks still apply. This is not a general target-tenant-generation bypass, and no other route class inherits it.
4. **Apply route classification** – Every protected route is classified as one of the following, and the middleware must enforce the corresponding registry and role rules:

| Route classification | Required issued-token state | Required role checks | Universal `authorityTuple.issuerAuthGeneration` / `authorityTuple.accountAuthorityGeneration` | Additional `authorityTuple` tenant/membership fields plus separate `membershipVersion` | Tenant validation rules |
| --- | --- | --- | --- | --- | --- |
| Public | *(none)* | *(none)* | None | None | *(none)* |
| Account-scoped | One matching token record for the exact profile declared by the route | Require authenticated caller and enforce subject binding (`accountId` path/body must match caller). A `platformAdmin` may bypass that binding only when the exact route entry declares `platform_admin_override: platformAdmin_only`, with a valid server-side `privileged_control` window backed by independent TOTP | Issuer + account | None | No tenant scope for auth |
| Caller-membership-scoped | One matching token record for the exact profile declared by the route | Bind the subject to the authenticated caller and require a live current membership for the selected tenant; any current membership role may perform the explicitly allowlisted self-lifecycle action | Issuer + account | Membership | Used only for caller-owned membership lifecycle such as leaving a game. It accepts no arbitrary account target and no global-role override |
| `player_bootstrap_tenant` | One matching `player-bootstrap` token record | Require the `player-bootstrap` token profile for the authenticated account | Issuer + account | Membership where declared | Used for caller-bound player-bootstrap routes targeting a tenant, including gameplay bootstrap; `IssueConnectToken` must use current caller-bound membership authority generation plus live membership, entitlement, and admission-pointer checks |
| `public_production_onboarding` | Exact route-declared profile for HTTP discovery/bootstrap routes (`player-bootstrap` where declared); in-band `REALMS`/`JOIN` uses the authenticated Game Session context; cookie revocation uses its dedicated edge contract | Require explicit caller-bound join before character creation or connect-token issuance; bootstrap writes derive the caller subject and target binding server-side; cookie revocation uses its exact token-tombstone and anti-CSRF contract | Issuer + account for JWT-presenting routes; non-JWT discovery, in-band `JOIN`, and cookie revocation use their declared route contracts | Membership after join; bootstrap routes carry their declared caller-bound target and discovery evidence | Public-production discovery may precede membership, but join creates the durable Account-owned membership and does not grant gameplay authority from global roles. This class does not authorize WebSocket admission, trusted-proxy transport, or `PLAY` |
| `gameplay_admission` | No JWT for in-band `PLAY` or the trusted TCP Proxy bridge; the first-party WebSocket edge presents the one-use `gameplay-connect` profile | Select exactly one admission mode and apply its branch-specific membership, grant, entitlement, routing, and binding checks; no transport credential creates membership or replaces Game Session admission checks | Mode-selected and route-specific; the gameplay-connect handshake uses its replay fence rather than the issued-token registry, while `PLAY` uses the bound-session and current-authority contract | Branch-specific membership/generation, grant, entitlement, routing, and binding evidence | Game Session selects `public_production_onboarding`, `returning_membership`, or `grant_backed_private_or_playtest` from current Account-owned evidence. Missing, stale, contradictory, or multiply applicable selector evidence denies admission |
| Pre-tenant discovery | No JWT for in-band commands; otherwise one matching record for the exact route-declared profile | Require authenticated caller; no caller-supplied `tenantId` is trusted yet | If a JWT route is used: issuer + account | None | Used only for authenticated lobby/discovery surfaces such as `WORLDS`; services must derive visible tenants by filtering authoritative membership/entitlement data server-side. Global roles do not widen gameplay discovery. |
| Tenant-scoped (regular) | One matching token record for the exact profile declared by the route | Require a tenant role in `scopedRoles[tenantId]` that authorizes the operation (for example `tenantAdmin`, `designer`, `moderator`, `player`) or an explicitly documented route-level `platformAdmin` allowance | Issuer + account | Tenant-role caller: target tenant + caller-bound membership; `platformAdmin`: target tenant only | `tenantId` must be in `scopedRoles` for tenant-role callers unless a specific route explicitly allows a global-role override. Tenant-role callers must pass live membership and membership-generation checks. An explicitly allowed `platformAdmin` operational request requires a valid server-side `privileged_control` window backed by independent TOTP and exact Account evidence bound to the request, target tenant, token `jti`, and assurance; it does not use a selected UI tenant as authority, fabricate membership, or enter gameplay admission/switching. `billingAdmin` and `support` must be rejected for `tenant_regular`. |
| Billing-safe (tenant-scoped) | One matching token record for the exact profile declared by the route | Require caller-bound tenant membership with `tenantAdmin` for the target tenant | Issuer + account | Account-owned caller-bound membership generation | `tenantId` must be validated against caller tenant scope; services must perform a live caller-bound membership/role check against authoritative account-tenant membership data (for example `GetCallerTenantMembership(tenantId)`) before allowing billing-safe mutations; this route intentionally omits the target-tenant generation only under the closed `billing_safe_tenant` exception and remains reachable when the tenant is `suspended`/`canceled` for gameplay, but fails immediately after membership/role revocation via the membership authority generation or live membership check |
| Cross-tenant (support-safe) | One matching token record for the exact profile declared by the route | Require the live global `support` role or an explicitly allowed `platformAdmin` role | Issuer + account | None | Tenant parameters are allowed only through the current Account-owned global role and global token scope; `support` does not require `privileged_control`, while the `platformAdmin` path does. Responses must be limited to high-level, troubleshooting-safe data (for example derived entitlements and subscription status, not invoices/payment methods); log/audit the target tenant |
| Cross-tenant (billing-safe) | One matching token record for the exact profile declared by the route | Require the live global `billingAdmin` role or an explicitly allowed `platformAdmin` role plus `privileged_control` | Issuer + account | None | Tenant parameters are allowed only through the current Account-owned global billing role and global token scope; cross-tenant use requires the bounded `privileged_control` window with independent TOTP for either role; log/audit the target tenant |
| `internal_workload` | Route-specific: no JWT or one exact private player-delegation profile | Exact mTLS workload identity and method caller allowlist; both constraints must pass | If a private JWT is accepted: issuer + account | Route-specific | Internal routes do not inherit an end-user JWT requirement. `game-session-account-delegation` is accepted only for its named receiver with audience `account-service`. |
| Cross-tenant (data-bearing) | One matching token record | Require `platformAdmin` plus a valid server-side `privileged_control` window backed by independent TOTP | Issuer + account | Target tenant generation when operation targets tenant-scoped data | The caller binds and validates the exact target-tenant generation as a freshness fence; no tenant membership or scoped role is inferred. Log/audit the target tenant. |
| `pending_deletion_scoped` | One Account-issued `pending-deletion-access` credential; no JWT | Dedicated pending-deletion validator and workflow state; no account, tenant, or membership generation | None | None | The credential is bound to the account and deletion workflow and authorizes only status, cancellation, export, or necessary billing settlement. Invalid, expired, revoked, binding-mismatched, or wrong-state credential/workflow evidence returns `AUTH_SESSION_REVOKED`; unavailable credential registry or deletion-workflow authority returns `AUTH_UNAVAILABLE`; normal JWT/session/gameplay authority is never a fallback |

- **Unavailable versus revoked** - An unreachable or timed-out registry, lease, binding, token-identity fence, authority-generation source, or Account evidence dependency returns retryable `AUTH_UNAVAILABLE` / HTTP 503 and does not tell clients to discard authentication. Reachable missing, deleted, expired, malformed, stale, regressed, revoked, or mismatched authority returns `AUTH_SESSION_REVOKED` or the specific invalid-token outcome and requires reauthentication. The exception is an unauthenticated recovery route whose `account_state_disclosure` is prohibited and whose response profile is uniform: state or credential rejection is folded into that uniform response and must not emit `AUTH_SESSION_REVOKED`; a genuine authority outage remains `AUTH_UNAVAILABLE`. During `AUTH_UNAVAILABLE`, the UI may retain in-memory auth state for retry, but no cached JWT role, membership, generation, projection, or allowlist result may authorize the failed operation.

Protected routes that are absent from the route matrix are currently recorded as inventory drift/gap because source-stable OpenAPI/protobuf coverage and comparison validation are incomplete. Runtime middleware must nevertheless reject every protected route whose classification is not deterministically known, immediately and independently of the inventory gate; it must not approximate the route as `tenant_regular` or another route class. Separately, CI and deployment policy checks must fail a validated candidate route that lacks matrix registration. The incomplete matrix must not be converted into generated policy, and its inventory failure must not weaken the runtime rejection.

Billing-safe mutation membership contract (normative):

- Billing-safe tenant mutations must perform an authoritative, live membership/role check via Account Service API (`GetCallerTenantMembership(tenantId)` or protocol-equivalent) before mutation.
- JWT role claims are sufficient for routing and preliminary checks but are not sufficient alone for billing-safe mutations.
- If membership authority is unavailable, billing-safe mutations fail closed with canonical error `AUTH_UNAVAILABLE`; read-only billing-safe surfaces may return a retriable unavailable response using the same code.
- Immediate caller-bound revocation for tenant membership/role changes is enforced by advancing the `{accountId, tenantId}` membership authority generation and projecting `session:auth:generation:membership:<accountId>:<tenantId>` in addition to the live membership check; implementers must not rely on JWT expiry alone.
- Tenant-scoped membership checks use `GetCallerTenantMembership(tenantId)` and must bind the subject to the authenticated caller (`accountId` from token); clients must not provide an arbitrary target `accountId` on this path.
- Global billing roles (`billingAdmin`/`platformAdmin`) must use explicitly cross-tenant billing-safe route variants and must not rely on caller-bound tenant membership endpoints intended for `billing_safe_tenant`.
- Cross-tenant membership checks for billing/reporting use a separate admin API (`GetTenantMembershipForAccount(tenantId, accountId)` or equivalent) restricted to `billingAdmin`/`platformAdmin`.
- Membership responses must include explicit `membershipExists`, `membershipAuthorityGeneration`, `gameplayAdmissionAllowed`, `evaluatedAt`, `membershipVersion`, the complete applicable `authorityTuple`, and the matching `outboxCheckpoints[]` entry for `account:auth-authority:v1:membership/<accountId>/<tenantId>`. Account must produce or authenticate these fields, the caller-bound account/tenant identity, and the checkpoint from one authoritative snapshot/transaction; `evaluatedAt` describes the freshness of that complete response, not a separately read field. Callers must reject a response that is missing any field, has a checkpoint whose `outboxSequence` does not cover the tuple and membership version, combines values from different checkpoints, or cannot prove this atomic freshness.

**5. Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult the internal runtime entitlement surface (`GetTenantEntitlementsForRuntime(tenantId, requestId)` or protocol-equivalent) and enforce its operation-specific flags as well as quotas. `past_due` remains playable under ordinary quotas; `grace` preserves connected sessions and same-session resume but denies first-time public join, first/new gameplay bindings, new instances, scale-out, and quota growth; `suspended`/`canceled` denies public join and gameplay. A fresh authoritative entitlement denial, including a grace/public-join denial or suspended/canceled state, returns `TENANT_BILLING_BLOCKED`; billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.

**6. Entitlement freshness and continuity SLA** – A snapshot is fresh for 15 seconds from its authoritative `evaluatedAt`. Explicit public join, first/new gameplay binding, new instance/scale, quota increase, paid-feature activation, and capacity-creating cutover require a fresh snapshot; a fresh authoritative denial returns `TENANT_BILLING_BLOCKED`, while inability to establish fresh entitlement authority returns `ENTITLEMENT_UNAVAILABLE`. Reconnect of the same resumable session and non-expanding restart/rollback/recovery may use a previously authoritative positive snapshot for at most five minutes when entitlement refresh is unavailable, subject to the bounded same-binding fallback below.

- Entitlement snapshots must carry operation flags for public join, new gameplay binding, and new instance/scale authority plus `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
- Last-known-good continuity is forbidden after observed `suspended`/`canceled`, revocation, explicit denial, a newer billing sequence, a sequence gap, or when no prior positive snapshot exists. Five minutes is a platform hard maximum; operators may only shorten or disable it.
- Last-known-good entitlement continuity does not relax revocation-authority freshness. If the separate batched revocation reconciliation lease cannot be renewed, active authority terminates at its stricter 60-second bound.
- On detected sequence gaps, services must reconcile by calling `GetTenantEntitlementsForRuntime(tenantId, requestId)` with a stable high-entropy identity for that reconciliation attempt before retrying admission.
- Existing uninterrupted sessions do not re-read entitlement state per action. Observed hard billing states still revoke them through sequenced events and tenant authority generations, with batched reconciliation bounding missed-event exposure to 60 seconds as defined in Session Behavior.

Support-safe routes are an explicit allowlist and must not be inferred broadly from role names. The current support-safe allowlist is:

- `GetTenantEntitlementsCrossTenantSupportSafe(tenantId)` returning high-level entitlement status only
- `GetSubscriptionCrossTenantSupportSafe(tenantId)` returning high-level status and plan metadata only
- `ListSubscriptionsCrossTenantSupportSafe` returning high-level status and plan metadata only

Support-safe endpoints must exclude invoice line items, payment method details, and subscription mutation APIs.

All route classifications represented in a validated source inventory must also be registered in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md) with machine-readable entries in `system-architecture-authz-route-matrix.yaml`. Until source-stable OpenAPI/protobuf coverage and comparison validation complete the inventory gate, a discovered protected route missing from the incomplete matrix is recorded as authorization drift/gap rather than fed into generated default-deny policy.

---

## Login and Session Flow

The canonical player-facing flow is intentionally simple:

```text
   WORLDS_PUBLIC
LOGIN <email> [secret]
[LOGIN <email> <code>]  # required after one-argument LOGIN requests a code
[JOIN <world>]  # required only for a first-time public-production account
PLAY <world> [realm] [character]
```

`<world>` is either an index from the caller's exact `WORLDS` browse snapshot or the stable `tenantSlug/worldSlug` selector carried by that response. A bare `tenantSlug` is accepted only when the tenant exposes exactly one visible authored world; a bare tenant-scoped `worldSlug` is never resolved globally. `[realm]` is a `realmSlug` under the resolved world or an index from the corresponding `REALMS` snapshot. Menu indices are response-local conveniences and are never stored or forwarded as durable identity.

Before login, only `WORLDS_PUBLIC` is available, and it exposes bounded public-production catalog/availability metadata. `REALMS` and `CHARS` are authenticated post-login discovery commands; they must not be exposed as anonymous pre-login discovery surfaces. After login, authenticated `WORLDS`, `REALMS`, and `CHARS` may provide caller-bound membership/grant-aware discovery.

`WORLDS` deliberately has two canonical modes rather than one replacing the other:

- Before `LOGIN`, `WORLDS_PUBLIC` is public browse-only discovery. It may expose only the bounded public-production catalog and availability metadata; it has no account identity, membership filtering, hidden-tenant disclosure, or gameplay authority.
- After `LOGIN`, `WORLDS_AUTHENTICATED` is authenticated pre-tenant discovery. Game Session derives the account from its authenticated gameplay context and combines current Account-owned membership/grant visibility with public-production visibility and entitlement filtering before any single tenant is selected. It may return more than the public catalog for that account, but it does not itself bind a tenant, create membership, mint a connect token, or authorize `PLAY`.

These modes are complementary: public browse remains available before authentication, while authenticated discovery remains membership-aware after authentication.

`REALMS <world>` has one route classification: `public_production_onboarding`. The classification is deterministic across the two points at which the command may run; the resolved world/tenant is an input to the checks, not a second route class:

- Before membership exists, the world selector must resolve to exactly one caller-visible `{tenantId, worldSlug}`. The response may include the tenant's one catalog-designated `publicProduction=true` realm when the current catalog/pointer pair is valid and the realm is publicly visible; no membership or realm grant is required for that public-production discovery, and global roles do not widen it.
- After the selector resolves to a canonical tenant, the same class performs exact tenant-scoped checks against that tenant's current catalog/pointer pair. The public-production realm still permits discovery without membership when its public visibility and entitlement checks pass. Every non-public realm requires both an existing caller-bound membership with exact `membershipLifecycleState=ACTIVE` for that tenant and the current Account-owned realm-access grant for `{accountId, tenantId, worldSlug, realmSlug}`; a grant never substitutes for membership. Hidden or unauthorized realms are omitted rather than disclosed.
- Both stages require the server-resolved tenant/world identity, current realm visibility, runtime entitlement evaluation, and the shared catalog/pointer reference. A missing, malformed, ambiguous, stale, or unavailable pointer is `ADMISSION_POINTER_UNAVAILABLE`; a complete `CLOSED` pointer may be shown as unavailable and is `REALM_UNAVAILABLE` for admission. `REALMS` never creates membership, grants gameplay authority, or binds a runtime target.

Normative semantic split:

- `LOGIN` proves or restores account identity.
- For a first-time public-production account, `JOIN <world>` explicitly creates membership after `LOGIN`; a returning member omits `JOIN` and continues to `PLAY`. First-party browser/mobile clients use the equivalent Account bootstrap join endpoint.
- The direct `LOGIN` plus `PLAY` compatibility shortcut is limited to credential-bearing text clients with existing active membership and a selected character. First-party/browser clients always use authenticated bootstrap, discovery, and lobby gates, including for returning members.
- `PLAY` binds the gameplay session to `{tenantId, gameInstanceId, characterId}`.

### In-Band `PLAY` Admission Boundary

In-band `PLAY` on a Telnet or gameplay WebSocket command stream is not an ordinary HTTP/gRPC JWT-middleware route. `LOGIN` establishes the authenticated account and Game Session-owned socket/session context; `PLAY` validates that bound gameplay session context and does not run `AuthTokenInterceptor` or infer authority from a browser JWT.

- **Pre-admission checks:** Game Session server-resolves the target and validates the bound session, exact `{tenantId, gameInstanceId, characterId}` identity, current Account membership response (`membershipExists=true`, exact `membershipLifecycleState=ACTIVE`, `membershipAuthorityGeneration`, `membershipVersion`, `gameplayAdmissionAllowed`, complete `authorityTuple`, matching `outboxCheckpoints[]`, and atomic freshness), entitlements, realm/access grant, routing pointer, coordination ownership, and the Account-owned exact-binding admission lease. For connect-token-backed admission, the current state, generation, and version must exactly match the immutable issuance baseline; a boolean `membershipExists` or a token-carried selector alone is not sufficient. The Game Session binding CAS includes the unchanged baseline, lease fence, authority tuple, and checkpoint and publishes no admissible binding until the ordered finalization protocol is complete.
- **Post-admission checks:** Each gameplay command uses the Game Session-owned admitted binding, admission/coordination leases, and typed `PlayerExecutionContext` under the concrete workload identity. Routine commands do not repeat ordinary JWT middleware or Account reads. Revocation events, bounded reconciliation, and the ADR 0037 token-authority-only outage exception govern whether the bound session remains usable; cutoff, gap, index, or complete-coordination failures remain denied/revoked.

Transport state, connect-token state, and any future hidden Telnet smart-client metadata are inputs to this flow; they are not peers to the authoritative gameplay binding.

All clients — whether connecting via Telnet or WebSocket — authenticate using the `LOGIN` command.

Target protocol behavior:

- **Target only:** `LOGIN` → Starts a multi-line prompt exchange (email → secret)
- **Current supported challenge flow:** `LOGIN <email>` → Requests a verified-email login code; then `LOGIN <email> <code>` → Submits that code for authentication
- **Current supported immediate-auth flow:** `LOGIN <email> <secret>` → Attempts immediate password-or-code login
- `LOGON` → Alias for `LOGIN`

Current implementation note:

- Multi-line prompt-based `LOGIN` remains target behavior for Telnet and other non-WebSocket text clients. Current clients use `LOGIN <email>` followed by `LOGIN <email> <code>` for the email-code challenge, or use `LOGIN <email> <secret>` for immediate authentication; bare `LOGIN` receives `PROMPT_LOGIN_UNSUPPORTED`.
- First-party browser/mobile `/ws/game/**` uses the bootstrap-backed path: once Gateway has validated a connect token and attached a signed connect context, bare `LOGIN` is canonical and must not prompt for or replay credentials. The current implementation includes dedicated `POST /auth/player-bootstrap` and `POST /auth/connect-token` endpoints, gateway-side handshake rejection for missing, expired, replayed, or scope-mismatched connect tokens, and Game Session validation of the signed connect context before admitting bare `LOGIN`. Public non-browser issuance and the dedicated handshake header remain target-only and unavailable.

Telnet-specific smart-client attach hints, if they return later, should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line. Those hints remain advisory transport metadata only, are not authentication material, and never bypass the canonical `LOGIN` + `PLAY` authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

Any future hidden attach hints may include a target `{gameInstanceId, tenantId}` for advanced clients, but the canonical source of gameplay target selection remains the authenticated lobby/admission flow. Clients must not rely on unauthenticated transport hints to bypass membership, entitlement, or world-visibility checks.

Admission-routing convergence rule:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, `POST /auth/connect-token`, and reconnect validation must all consume the same authoritative realm-catalog and `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract).
- Those surfaces may expose different projections of the same routing truth, but they must not maintain separate interpretation rules for which realm maps to which admissible `gameInstanceId`.
- If pointer state is missing, ambiguous, or no longer matches the selected realm target, the flow fails closed with admission-routing errors such as `ADMISSION_POINTER_UNAVAILABLE` or `CONNECT_SCOPE_MISMATCH` rather than silently rebinding the player to a different runtime target.

### WebSocket Connect Token Contract (`/ws/game/**`)

Target-only contract: once each public non-proxy WebSocket client class has a fully registered issuance route, the control plane issues a short-lived connect token carrying the verified identity, realm, runtime-target, pointer, and replay-fence claims from which Gateway creates the signed Gateway-to-Game Session connect context. The same token also supports handshake-time edge policy such as tenant-aware rate limiting before `LOGIN` completes. Public non-browser issuance remains unavailable until that registration and its focused proof are complete.

FireMUD standardizes a dedicated **player bootstrap** contract for first-party gameplay web/mobile clients:

- The first-party player UI authenticates directly against a dedicated bootstrap endpoint (for example `POST /auth/player-bootstrap`) using the same primary account-secret and abuse policy as gameplay login.
- `POST /auth/player-bootstrap` is the canonical first-party browser/mobile player-login endpoint. It is not derived from an existing admin/creator control UI session and must not require or return a `control-ui` JWT.
- `POST /auth/player-bootstrap` is tenant-free: its target authority boundary is global account authentication with account-level token-generation checks; it selects no gameplay tenant and does not validate tenant membership, membership authority generation, realm grants, entitlements, or gameplay admission.
- On success, the endpoint returns one short-lived, memory-only **player bootstrap token** plus expiry metadata.
- This bootstrap token is not a control-plane `control-ui` JWT and must not be accepted on admin/creator APIs.
- It is still an Account Service-issued JWT profile and must carry at least `iss`, `sub`, `accountId`, `aud=player-bootstrap`, `jti`, `iat`, `nbf`, `exp`, positive monotonic `tokenGeneration`, complete unscoped `authorityTuple`, and positive `issuanceFence`, backed by one `session:auth:token:<tokenHash>` record so account-level revocation and logout semantics apply.
- Audience/scope is limited to gameplay bootstrap functions: caller-bound discovery, bootstrap-authenticated character discovery and character creation, `POST /auth/bootstrap/join`, and `POST /auth/connect-token`. It does not authorize gameplay commands, admin/creator APIs, or arbitrary tenant mutation.
- Lifetime is intentionally short (target <= 5 minutes), stored in memory only, and cleared on tab reload/logout.
- `POST /auth/connect-token` must derive caller identity from this bootstrap token; clients must not supply an arbitrary `accountId`.
- The subsequent gameplay `LOGIN` remains mandatory but, for first-party `/ws/game/**` clients, it must complete using the already-verified bootstrap/connect context rather than requiring the browser to re-submit account credentials. In other words, first-party bare `LOGIN` on `/ws/game/**` is an identity-consumption/binding step, not a second credential-entry step. A mismatch between the verified bootstrap identity and the gameplay login result is a hard failure and the connect context must not be honored.

- Bootstrap issuance API: Account Service endpoint (for example `POST /auth/player-bootstrap`) that authenticates the player account for first-party gameplay bootstrap only and returns one short-lived bootstrap token plus expiry metadata.
- Issuer: Account/authentication control-plane only, after direct player-account authentication. Tenant membership and entitlement checks do not occur here because no gameplay tenant has been selected yet; those tenant-scoped checks belong to connect-token admission.
- First-party bootstrap ownership: Account Service owns `POST /auth/player-bootstrap`, bootstrap discovery, bootstrap-authenticated character discovery and character creation, explicit `/auth/bootstrap/join`, `POST /auth/connect-token`, and membership lifecycle. Game Session exposes the equivalent text `JOIN` command and owns in-socket `LOGIN`/`PLAY`, but delegates membership mutation to Account and never creates it during `PLAY`.
- `POST /auth/bootstrap/join` and the delegated `JoinPublicProductionMembership` operation accept the verified discovery `connectScopeId` plus `requestId`, not an independently authoritative tenant/world/realm tuple. Account resolves the selector for the caller, binds the resolved target and `pointerVersion` into the request/operation digest, and rechecks that selector and digest at the membership commit gate.
- Bootstrap-discovery and mutation APIs: authenticated first-party HTTP endpoints (for example `GET /auth/bootstrap/worlds`, `GET /auth/bootstrap/worlds/{world}/realms`, `GET /auth/bootstrap/worlds/{world}/realms/{realm}/characters`, `POST /auth/bootstrap/join`, and bootstrap-authenticated `POST /auth/bootstrap/worlds/{world}/realms/{realm}/characters`) that accept only the `player-bootstrap` token profile and return or mutate the canonical lobby data used to choose a target before socket open. Character creation is allowed only after the explicit join where public-production membership is required.
  - These endpoints are the canonical pre-socket discovery path for first-party clients.
  - They must apply the same caller-bound membership, realm visibility, and entitlement filtering rules as in-band `WORLDS` / `REALMS` / `CHARS`.
  - Hidden or unauthorized tenants, realms, and characters must not be inferable by probing these endpoints.
  - Discovery responses must return a canonical connect-token selector for each admissible realm target. FireMUD standardizes this as an opaque `connectScopeId` plus resolved routing metadata.
  - `connectScopeId` is the only client-supplied selector accepted by `POST /auth/connect-token`; first-party clients must not invent or derive `tenantId` / `gameInstanceId` pairs locally from slugs.
  - Minimum selector fields returned by discovery for an admissible realm target: `connectScopeId`, `tenantId`, `realmSlug`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `evaluatedAt`, and `connectScopeExpiresAt`.
  - `connectScopeId` is an opaque server-issued selector for one caller-visible realm target, not a durable public identifier. Clients may cache it only as a short-lived convenience token for reconnect/bootstrap flows and must be prepared to discard it when discovery, pointer version, or visibility state changes.
  - Discovery responses are snapshot proofs, not durable reservations. `evaluatedAt` and `connectScopeExpiresAt` describe the freshness window for the selector that was returned; they do not promise the realm remains admissible until gameplay starts.
  - `connectScopeId` must not outlive the routing truth it was derived from. If the realm's `pointerVersion`, visibility, or entitlement posture changes such that the previously discovered target is no longer admissible, `POST /auth/connect-token` must reject the stale selector rather than silently translating it to a newer target.
  - For non-public realms such as playtest forks, visibility is controlled by an explicit realm-access grant. The target minimum grant record is `{tenantId, worldSlug, realmSlug, accountId, grantedByAccountId, grantedAt, expiresAt?}`. The current first implementation centralizes Account Service grant authority and runtime reads, but expiry and tenant-admin management UX remain product/control-plane follow-through.
  - Realm-access grants are owned by Account Service. Account Service is the sole writer and read authority for grant visibility decisions; Game Session and frontend callers consume grant-filtered results and must not maintain independent grant stores.
  - `tenantAdmin` is the routine owner of creating and revoking realm-access grants for that tenant through Account Service-owned admin surfaces. `platformAdmin` may do the same only as break-glass support.
  - Required internal read contract: Account Service must expose a caller-bound runtime lookup for realm visibility/admission, for example `GetRealmAccessGrant(accountId, tenantId, worldSlug, realmSlug)` or a batch/list equivalent consumed by bootstrap discovery, in-band `REALMS`, `POST /auth/connect-token`, and `PLAY`.
  - Required semantics for realm-access-grant reads/writes:
    - idempotent create/revoke by `{accountId, tenantId, worldSlug, realmSlug}`
    - expired grants are treated as non-existent for visibility and admission
    - successful create/revoke must be immediately visible to subsequent discovery/admission reads
    - if grant authority is unavailable, non-public realm discovery and admission fail closed rather than falling back to stale local cache state
- Connect-token issuance API: control-plane endpoint (for example `POST /auth/connect-token`) that produces one durable request outcome per request digest, and at most one short-lived token for a committed success. Its `gameplay-connect` issuance/replay record remains separate from the ordinary `session:auth:token:<tokenHash>` registry/session, and logs `accountId`, `tenantId`, `gameInstanceId`, `jti`, and issuance timestamp when a token exists.
  - Minimum request fields: `connectScopeId`, `requestId`.
  - Response fields are non-secret metadata only: `expiresAt`, `accountId`, `tenantId`, `gameInstanceId`, `realmSlug`, `jti`, and `issuedAt`. The connect token is set only as the `Firemud-Connect-Token` HttpOnly cookie. First-party native-mobile and other first-party non-browser clients use a protected cookie jar; this endpoint never returns a raw `connectToken` response-body field. Explicitly classified non-first-party/public WebSocket clients have a separate target-only issuance route, not this endpoint. That route is not yet named, registered, or implemented and remains unavailable; before use it must have a dedicated authorization-matrix entry plus complete issuance, active-registry/generation, response, and carrier proof. The target route issues the same `aud=gameplay-connect` single-use token, returns it exactly once in a protected response body to the classified client for OS secure storage, and permits carriage only through `X-Firemud-Connect-Token`. No browser or first-party client may use it.
  - Before issuance, Account Service must resolve `connectScopeId` to the canonical `{tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion, catalogRevision}` tuple, perform a live membership/public-admission check for `{accountId, tenantId, worldSlug, realmSlug}`, validate the exact current membership lifecycle state and caller-bound membership authority generation for that tenant, perform a live runtime entitlement check for `tenantId`, and perform a live realm-routing read for the selected realm target via the Game Session control-plane API. For an existing-member admission, the lifecycle state must be exactly `ACTIVE`, and the issuance baseline must persist that state together with the exact `membershipAuthorityGeneration` and independent `membershipVersion` from the same Account snapshot. A missing public-production membership returns `JOIN_REQUIRED` and commits no membership row or token, but it still commits the terminal request outcome described below. A private or playtest target additionally requires the current Account-owned realm-access grant and an existing active membership; a grant never substitutes for membership. This is the membership-, grant-, lifecycle-, generation-, and version-validated admission boundary; a valid tenant-free `player-bootstrap` token alone is insufficient, and connect-token issuance must not create membership.
  - Account must also read the shared replay-readiness record as `OPEN` and bind its exact `replayAdmissionFence` into the signed token. Missing, unreadable, `QUARANTINED`, or changing replay readiness fails issuance with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`; a token racing a later fence advance is rejected by Gateway.
  - The resolved tuple used for issuance must be treated as immutable for that request. Issuance may succeed only if `connectScopeId`, current catalog/policy revision, realm visibility/grant state, membership lifecycle/generation/version, and current admission-pointer state still converge on the same target at evaluation time.
  - `requestId` is the idempotency key and `requestDigest` is the immutable digest for connect-token issuance. The digest covers the canonical operation, caller account, `connectScopeId`, and every request field and resolved target field that participates in the decision. Account durably stores one request outcome keyed by the caller-bound request identity and digest before returning a terminal result. `COMMITTED` stores the one token payload and issuance baseline; deterministic failures such as `JOIN_REQUIRED`, `REALM_UNAVAILABLE`, `CONNECT_SCOPE_MISMATCH`, `TENANT_BILLING_BLOCKED`, and other reachable validation denials store the stable error and evidence without a token. Retrying the exact `(accountId, requestId, requestDigest)` returns that stored success or failure and never re-evaluates a terminal failure or mints a replacement token. A changed digest is an idempotency conflict; a caller intentionally starting a new attempt after rediscovery must use a new `requestId`.
  - Account commits the immutable digest, authority snapshot, issuance baseline, and terminal request outcome with the dedicated `gameplay-connect` issuance/replay record when successful, before returning success or setting the browser cookie. This record is separate from the ordinary `session:auth:token:<tokenHash>` registry/session. `JOIN_REQUIRED` and every other deterministic application failure are durable terminal outcomes even though no membership row or token is created. A response lost after commit is recovered by read-only reconciliation of the same request identity; it never mints a second token. If Gateway has consumed the committed token, the client must rediscover and use a new request ID for a new token rather than replaying the prior issuance result.
  - `AUTH_UNAVAILABLE` is limited to a non-routing Account authority, membership/generation, lease, registry, or coordination dependency that cannot be read or times out. Replay-readiness failure remains `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`. The request remains non-terminal `PENDING`/indeterminate until the same digest is reconciled; a timeout or lost response never permits a second issuance attempt. If reconciliation proves no commit, Account records a terminal non-executed outcome; if it proves a commit, it returns the stored result. No cached authority may authorize the operation.
  - Routing and pointer failures are not `AUTH_UNAVAILABLE`: a missing, malformed, ambiguous, stale, or unreachable/timed-out catalog/pointer dependency returns `ADMISSION_POINTER_UNAVAILABLE`; a complete `CLOSED` pointer returns `REALM_UNAVAILABLE`; a valid pointer whose resolved target conflicts with the verified selector/context returns `CONNECT_SCOPE_MISMATCH`. `ADMISSION_POINTER_UNAVAILABLE` remains non-terminal `PENDING`/indeterminate and is retried or reconciled only under the same request identity and digest; `REALM_UNAVAILABLE` and `CONNECT_SCOPE_MISMATCH` are deterministic terminal outcomes. None is converted to authentication-service outage messaging.
  - If `connectScopeId` no longer resolves to the current admissible target for the selected realm, connect-token issuance fails closed with `CONNECT_SCOPE_MISMATCH`; it must not mint a token for a stale or non-admissible target and rely on `PLAY` to correct it later.
  - First-party clients may request connect tokens only for realm targets returned by the canonical bootstrap-discovery contract for that caller; hidden or unauthorized realms must not be inferable by probing connect-token issuance directly.
  - If the realm was only caller-visible through an explicit non-public access grant, connect-token issuance must re-check that grant at issuance time rather than trusting earlier discovery alone.
  - Missing required request/response fields are contract violations and must fail closed rather than being defaulted by callers.
  - A server receiving an expired `Firemud-Connect-Token` cookie rejects it as `CONNECT_TOKEN_EXPIRED`, never falls back to a query parameter or unapproved header, and clears the cookie when the response surface permits. The client must obtain a fresh token from a still-valid bootstrap identity; an expired or revoked bootstrap identity requires a fresh bootstrap flow.
- Transport: connect-token carriage on `/ws/game/**` handshake.
  - Carrier classification: browser and mobile-browser clients use the HttpOnly cookie; first-party native-mobile clients using a cookie jar remain cookie-only; explicitly classified non-first-party/public native-mobile or other non-browser clients have no current supported carrier and may use secure storage plus `X-Firemud-Connect-Token` only under the fully registered target route.
  - First-party browser clients use the cookie `Firemud-Connect-Token` set by `POST /auth/connect-token` with `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/ws/game`, and `Max-Age` no longer than the connect-token TTL. The cookie value is the connect token; browser JavaScript must not read or persist it.
  - First-party native-mobile and other first-party non-browser clients that use a cookie jar use the same `Firemud-Connect-Token` cookie; first-party status does not create a header fallback.
  - Target-only: after a dedicated non-first-party/public native-mobile, server-side, or other non-browser WebSocket route is fully registered and proven, that route may use the dedicated `X-Firemud-Connect-Token` handshake header and OS secure storage where applicable. No current public non-browser token issuance or header support is advertised. This does not apply to Telnet credential-login traffic.
  - Gateway must accept exactly one non-empty, single-valued supported carrier for non-proxy gameplay handshakes. Duplicate header values, duplicate cookie values, a malformed carrier, or simultaneous header and cookie carriers are rejected as `CONNECT_TOKEN_REJECTED`; Gateway never chooses precedence.
  - Query-string carriage is not a supported connect-token carrier in player-facing environments.
- Required claims: `iss`, `aud`, `accountId`, `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`, `catalogRevision`, `connectScopeId`, `requestId`, `iat`, `exp`, `jti`, `replayAdmissionFence`.
- `iss` is required and must exactly match the deployment's configured Account Service issuer identifier used by the Account JWKS trust configuration; callers cannot select or override it.
- `aud` is required and must be exactly `gameplay-connect`; Gateway rejects a missing, multi-valued, or different audience before consuming `jti`.
- Lifetime: a platform hard maximum of 30 seconds from signed `iat` to `exp`; issuers may shorten but not widen it, and Gateway independently rejects missing/future-skewed `iat`, invalid ordering, and lifetimes above the maximum.
- Signing and verification: token is signed by the Account/authentication control-plane key set and verified only at Gateway for `/ws/game/**` policy decisions.
- `gameplay-connect` registry boundary: Account does not issue or require an ordinary `session:auth:token:<tokenHash>` registry/session record for this one-use edge credential. Its dedicated `gameplay-connect` issuance/replay record and Gateway's `gateway:connect-token:jti:<jti>` consume marker are separate from ordinary issued-token authority. Gateway validates the signed token and replay evidence; absence from the ordinary issued-token registry is expected and is not a rejection reason.
- Replay defense: gateway validates `jti` against a bounded replay cache and rejects replays until token expiry.
  - Before and atomically during consumption, Gateway requires replay readiness to be `OPEN`, the signed `replayAdmissionFence` to equal the current shared fence, and the Gateway-owned browser deny marker `gateway:connect-token:deny:jti:<jti>` to be absent. The deny-marker check and `gateway:connect-token:jti:<jti>` creation are one Coordination Redis operation; a revocation that linearizes first denies admission, while a consume already committed is spent.
  - Replay cache owner: Gateway.
  - Replay key format: `gateway:connect-token:jti:<jti>`.
  - Browser-revocation deny-marker owner and key format: Gateway, `gateway:connect-token:deny:jti:<jti>`.
  - Replay TTL: through `exp + bounded_skew`, covering the token's complete acceptance window.
  - Capacity policy: bounded cardinality with expired-marker cleanup and overload metrics. Gateway must never evict an unexpired marker; when capacity cannot be reclaimed without doing so, new connect admission fails closed with `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` until capacity recovers.
- Enforcement:
  - `/ws/game/**` is the only gameplay WebSocket route.
  - Non-proxy gameplay clients must present a valid connect token; missing, invalid, expired, replayed, scope-mismatched, or replay-protection-unavailable token state is rejected with HTTP `403` and the bounded handshake classes defined in [Reconnection Strategy](./system-architecture-reconnection.md#http-handshake-failures-on-ws-game).
  - TCP Proxy bridge traffic is admitted without a connect token only when the gateway authenticates the proxy identity over the internal mTLS listener and header-trust checks pass.
- Error mapping: connect-token admission failures map to HTTP `403` at handshake, with specific `CONNECT_*` classes when the gateway can classify the failure.

The connect token carries a short-lived, immutable snapshot of the selected gameplay target for edge admission. It is not a gameplay command authority, is not a gameplay authorization grant, and does not replace the canonical `LOGIN` + `PLAY` flow; it is an edge-admission artifact bound to a prior first-party bootstrap identity, not a substitute for gameplay authentication or gameplay binding.

#### Gateway-to-Game Session connect context (normative)

Gateway verification of a supported connect-token carrier must not be translated into trust of raw forwarded headers. Gateway must validate and consume the token, strip every external carrier before the upgrade completes, and attach a short-lived signed connect context that Game Session verifies before applying connect-token scope checks.

- Gateway-issued context fields (minimum): `accountId`, `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`, `catalogRevision`, `connectScopeId`, `connectTokenJti`, `verifiedAt`, `expiresAt`, `gatewayRequestId`.
- Transport: single signed compact payload header (for example `X-Firemud-Connect-Context`) plus `kid` metadata if not embedded in payload.
- Signature: asymmetric gateway signing key set; Game Session validates signature and `kid` against Gateway verification keys.
- TTL: <= 30 seconds from `verifiedAt`; Game Session rejects stale/expired contexts.
- Replay guard: Gateway owns replay protection for `connectTokenJti` at handshake time using the shared replay cache. Game Session does not implement a second replay authority for that token identifier; it treats `connectTokenJti` inside the signed context as auditable scope metadata only.
- Failure mode: if signed context is missing/invalid for a first-party handshake that required connect-token verification, Game Session must fail admission with `CONNECT_CONTEXT_INVALID` before `PLAY`.
- Key-management operational contract:
  - Gateway is the sole signer for connect-context payloads and must expose a verification-key set with stable issuer identity and bounded-key cardinality.
  - Rotation must support overlap: old and new `kid` values remain verifiable for a bounded overlap window so rolling deploys do not break in-flight reconnects.
  - Game Session maintains a bounded TTL cache of Gateway verification keys and must refresh on unknown `kid`; if no valid verification keys are available, fail closed with `CONNECT_CONTEXT_INVALID`.
  - Observability must expose bounded failure reasons (`unknown_kid`, `signature_invalid`, `context_expired`, `verification_keys_unavailable`) so operators can distinguish key-rollout issues from client misuse.

`CONNECT_SCOPE_MISMATCH` must be computed from this verified context, not from raw `X-Tenant-Id`/`X-Game-Instance-Id` headers.

#### First-party WebSocket admission sequence (normative)

`public_production_onboarding` and `gameplay_admission` are separate route classifications. The former covers public discovery, explicit `JOIN`, caller-bound bootstrap reads/writes, and connect-token cookie revocation. The latter covers the authenticated gameplay admission decision: the `/ws/game/**` transport handshake and in-band `PLAY`, including the mode-specific membership, grant, entitlement, routing, and binding checks. A trusted TCP Proxy transport is gameplay admission, not public onboarding.

To remove ambiguity between connect-token admission and `LOGIN`, first-party web/mobile gameplay clients must follow this sequence:

1. Call the dedicated first-party player bootstrap endpoint (for example `POST /auth/player-bootstrap`) and establish a short-lived player bootstrap identity.
2. Use bootstrap-authenticated discovery endpoints to select a caller-visible world and realm target.
3. If the public-production target is visible but the account is not already a member, explicitly call `POST /auth/bootstrap/join`. Character discovery and creation require the resulting membership; a returning member skips this step.
4. Select or create a caller-visible character, then request a short-lived gameplay connect token for the target selected by `connectScopeId`. This call performs live membership, current membership authority-generation, applicable realm-grant, and runtime entitlement checks.
   - The issuance path must also validate the target against the authoritative realm-routing record. If the target is no longer admissible for the selected realm, the request fails before socket open rather than issuing a stale token.
5. Open gameplay WebSocket on `/ws/game/**` with the `Firemud-Connect-Token` HttpOnly cookie set by `POST /auth/connect-token`; first-party native-mobile clients using a cookie jar remain cookie-only. The target-only non-first-party/public non-browser route may use secure storage plus the dedicated header only after that route is fully registered and proven; it is unavailable in the current runtime.
6. Complete gameplay authentication in-band using `LOGIN` (or `LOGON`) and then lobby binding with `PLAY`.

Normative constraints:

- First-party clients must not treat successful handshake as gameplay authentication; gameplay remains unauthenticated until `LOGIN` succeeds.
- `/ws/game/**` requires a valid connect token for non-proxy clients and rejects missing tokens with `403`.
- Connect-token-backed admission has an independent downstream authorization gate: Game Session must re-read existing caller-bound membership, require explicit prior join for public production, and confirm public-production admission or applicable realm visibility/grant and current realm routing. A valid connect token alone cannot admit gameplay or create membership.
- For first-party `/ws/game/**` clients, bare `LOGIN` (or `LOGON`) must complete gameplay authentication by consuming the verified connect context plus the bootstrap identity already bound to that context. Browsers must not be required to replay credentials after bootstrap.
- Telnet and other non-WebSocket text transports use `LOGIN <email>` followed by `LOGIN <email> <code>` for an email-code challenge, or `LOGIN <email> <secret>` for immediate authentication. Any richer multi-line prompt flow is target-only and is not part of the current client capability contract. Public non-browser WebSocket clients have no current supported issuance or header path; the classified bootstrap/connect-token header path is target-only and unavailable until its dedicated route is registered and proven.
- Game Session must bind the verified connect context to the authenticated gameplay login: if bootstrap-backed `LOGIN` resolves to an `accountId` different from the connect-context `accountId`, the session fails closed with canonical error `ACCOUNT_MISMATCH` and no gameplay scope is bound.
- For first-party clients on `/ws/game/**`, `PLAY` accepts stable world/realm/character selection only. Game Session resolves the current admissible `{tenantId, gameInstanceId}` server-side and requires that resolved scope to match the connect-token context. Scope mismatch is rejected with canonical error `CONNECT_SCOPE_MISMATCH`; clients must request a fresh connect token for the intended stable realm target and reconnect.
- Because `/auth/connect-token` validates against the authoritative realm-routing state for the caller, `CONNECT_SCOPE_MISMATCH` at `PLAY` is treated as drift between issuance and admission (for example route movement during reconnect), not as normal stale-client correction.
- The bootstrap-discovery contract, connect-token contract, and lobby `PLAY` contract together form the canonical player-selected stable world/realm/character path. `connectScopeId` binds that selection to a server-resolved concrete runtime target; first-party clients carry the opaque scope forward and never select or invent `tenantId` / `gameInstanceId` routing authority.

Canonical returning-member first-party browser sequence (example):

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [{
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
     gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
     connectScopeId: "cs_demo_production_v17"
   }]

GET /auth/bootstrap/worlds/demo/realms/production/characters?connectScopeId=cs_demo_production_v17
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-123" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
WORLDS
REALMS demo
CHARS demo production
PLAY demo production Mara
OK PLAY Entered Demo World / Live Realm as Mara
```

Canonical first-public-join sequence (example):

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "emberfall", displayName: "Emberfall" }]

GET /auth/bootstrap/worlds/emberfall/realms
Authorization: Bearer <bootstrapToken>
-> [{
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40",
     gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce",
     connectScopeId: "cs_emberfall_production_v1"
   }]

POST /auth/bootstrap/join
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-join-1" }
-> { tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", membershipLifecycleState: "ACTIVE", membershipAuthorityGeneration: 8, membershipVersion: 1, joined: true }

GET /auth/bootstrap/worlds/emberfall/realms/production/characters?connectScopeId=cs_emberfall_production_v1
Authorization: Bearer <bootstrapToken>
-> []

POST /auth/bootstrap/worlds/emberfall/realms/production/characters
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", name: "Mara", template: "human-fighter" }
-> { characterName: "Mara", characterId: "c7f4b18b-6eb5-4fd8-a906-c9606d17d4dc" }

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-connect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", realmSlug: "production", gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
PLAY emberfall production Mara
OK PLAY Entered Emberfall / Live Realm as Mara
```

Required postconditions for the explicit public-production join:

- the account now has canonical `player` membership for the tenant;
- future `WORLDS` results for that account may rely on membership rather than public discovery alone; and
- later character, token, socket, or `PLAY` failure does not remove the intentionally joined membership.

Canonical character-creation contract for this flow:

- The player-facing control-plane surface is Account-owned `POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters`, using the current bootstrap-authenticated account identity and server-issued opaque discovery `connectScopeId`; the route must match that resolved selector.
- Account validates the admission prerequisites and delegates the authorized internal write to Entity Management, which owns `CreateCharacter` semantics and persistence. Entity Management remains internal-only and exposes no direct player REST route.
- The Account facade is allowed only after the caller has explicitly joined the public production game or already has the required membership/grant, and before `POST /auth/connect-token` / gameplay `PLAY` succeed for that new character.
- The route must reject requests for realms that are not currently visible/admissible to the bootstrap-authenticated account.
- The current realm-scoped backend creation substrate carries `{tenantId, accountId, name, gameInstanceId, playableStateScope}` into Entity Management. The richer player-facing descriptor for template/race/class/options is still a required product contract before first-party clients can render nontrivial character creation without game-specific assumptions.

Example first-party browser sequence for a playtest fork:

```text
POST /auth/player-bootstrap
-> { bootstrapToken, expiresAt }

GET /auth/bootstrap/worlds
Authorization: Bearer <bootstrapToken>
-> [{ worldSlug: "demo", displayName: "Demo World" }]

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [
     {
       realmSlug: "production",
       displayName: "Live Realm",
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
       connectScopeId: "cs_demo_production_v17"
     },
     {
       realmSlug: "playtest-docks",
       displayName: "Playtest Fork",
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d",
       connectScopeId: "cs_demo_playtest_docks_v4"
     }
   ]

GET /auth/bootstrap/worlds/demo/realms/playtest-docks/characters?connectScopeId=cs_demo_playtest_docks_v4
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_playtest_docks_v4", requestId: "req-456" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "playtest-docks", gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d", expiresAt, jti, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response

LOGIN
OK LOGIN Logged in
CHARS demo playtest-docks
PLAY demo playtest-docks Mara
OK PLAY Entered Demo World / Playtest Fork as Mara
```

### Mapping to the Account Service

#### Plain-text `LOGIN`/`LOGON` command mapping

1. The client emits one of the canonical gameplay-login forms:
   - `LOGIN <email>` (or `LOGON ...`) to request a verified-email code, followed by `LOGIN <email> <code>` (or `LOGON <email> <code>`) to authenticate, or `LOGIN <email> <secret>` to authenticate immediately, for Telnet and other non-WebSocket text transports.
   - bare `LOGIN` / `LOGON` on any public non-proxy `/ws/game/**` connection after Gateway has validated a connect token and attached a signed connect context. First-party browsers carry the token in the protected cookie. A fully registered target-only non-browser WebSocket route may carry it in `X-Firemud-Connect-Token`; that route is unavailable in the current runtime. In either supported target case, the client is completing gameplay auth from the previously established bootstrap identity rather than sending credentials a second time.
2. For credential-bearing login, the Game Session Service parses the line, normalizes the email, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with `email`, one supplied `secret`, a typed `CredentialSourceContext`, and a stable high-entropy `requestId` for that one login attempt. Account binds the request ID to an immutable, server-keyed and versioned digest of the normalized authentication operation, credential presentation, source context, and applicable scope. The dedicated Account-owned digest-key version remains available for at least the response-envelope lifetime; neither the raw secret nor an unkeyed reusable credential hash is persisted or logged. Account's durable operation state contains only token identity, authority snapshot, request digest, lifecycle state, and reconciliation metadata. When response-loss recovery must return the credential itself, Account retains the exact JWT/result only in a separate bounded Account-encrypted response envelope bound to that operation; retry with the same request ID and matching digest may read that envelope only after the issued-token registry record and current authority are reconciled. The raw compact JWT is not durable operation evidence. Reuse with a different digest or scope is rejected as an idempotency conflict and cannot issue another token. The context carries the server-derived canonical client address and transport class from the trusted Gateway or authenticated TCP Proxy chain; public input cannot populate or override it. Account rejects a missing, unknown, or untrusted source context in player-facing environments. Account Service interprets that secret against the account's enabled `PASSWORD` and `EMAIL_OTP` modes. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs. A one-argument `LOGIN <email>` instead invokes the Account-owned neutral email-code challenge and does not authenticate the session by itself.
3. For bootstrap-backed WebSocket login, Game Session validates the signed connect context, binds it to the bootstrap-authenticated account identity established before `/auth/connect-token`, and obtains/refreshes the backend token material needed for subsequent internal calls. This path must not prompt for or require replay of account credentials from the WebSocket client.
4. The Account Service-backed credential path validates the supplied secret according to the enabled account modes and returns account metadata plus the private `game-session-account-delegation` JWT profile with audience `account-service`, or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED`, or `AUTH_UNAVAILABLE`. This exact receiver-specific profile is the only Account token Game Session accepts from credential authentication; a generic backend JWT or another audience is invalid. The Game Session Service translates Account error codes into the text-protocol equivalents so WebSocket and Telnet clients always see the same response format regardless of upstream wording.
5. Success responses cause the Game Session Service to create or refresh the Redis-backed gameplay session binding. Account creates the one issued-token registry record for the returned `game-session-account-delegation` token, and Game Session uses that token only for Account-bound backend calls under its exact profile and audience. If the Game Session binding CAS fails after token issuance, Game Session must call Account's idempotent retire/abort operation for that exact request and token identity; Account removes or revokes the orphan registry record, and an orphaned token is never accepted merely because it was cryptographically valid. Game Session binds the socket to an authenticated account context and emits `OK LOGIN Logged in` (or equivalent account-confirming text) on the wire only after the binding succeeds. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by both the authentication handshake (`LOGIN`) and the lobby selection step (`PLAY`). Any text command received before login should be rejected with stage-aware guidance such as `ERROR LOGIN_REQUIRED ...`, and any gameplay command received before `PLAY` should be rejected with stage-aware guidance such as `ERROR PLAY_REQUIRED ...`. Except in explicitly documented development/test bypass modes that grant temporary access, these commands are not processed for anonymous or unscoped sessions, keeping the gameplay queue free of unauthenticated traffic.

Credential-bearing login commands carry an account email and one secret. A one-argument login carries only the email and requests the neutral email-code challenge. Bootstrap-backed first-party `LOGIN` carries no credentials because it consumes the already verified bootstrap/connect context. Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model).

### Tenant Selection for Gameplay (Lobby Selection)

FireMUD uses a **single shared entrypoint** for many worlds (tenants). After `LOGIN`, clients complete a lobby selection step that binds the authenticated connection to a specific world (`tenantId`), gameplay-admissible instance (`gameInstanceId`), and gameplay identity (`characterId`) before gameplay commands are accepted.

Players must never be asked to type platform-scope identifiers such as `tenantId`, `gameInstanceId`, or `characterId` during lobby selection. Lobby flows accept human-friendly world slugs, menu indices, and character names or indices and resolve them server-side. Gameplay may separately expose stable numeric runtime-entity IDs when useful for distinguishing visible live instances; those IDs remain scoped selectors rather than authorization.

After `LOGIN` succeeds, the Game Session Service requires an explicit lobby selection flow using these canonical commands:

- `WORLDS` – list worlds the authenticated account can enter (a numbered menu plus stable
  `tenantSlug` and `worldSlug` values for each entry).
- `REALMS <world>` – list the visible realms for the selected world (`<world>` is a response-local
  world index or the stable tenant-qualified selector returned by `WORLDS`). Responses include the
  default production realm plus any explicitly authorized additional realms such as playtest
  forks.
- `JOIN <world>` – treat `<world>` as an adapter-local selector and obtain only the Account-bound `{connectScopeId, requestId}` for the authenticated operation. `JoinPublicProductionMembership` receives those two inputs plus the trusted caller context; Account resolves the target and explicitly creates the caller's durable `player` membership only when it is missing, or returns the existing membership. Raw client or adapter tenant, route, world, realm, or scope values are never operation authority. First-party clients expose the equivalent `Join & Play` action through Account bootstrap.
- `CHARS <world> [realm]` – list characters for the selected world and optional realm.
- `PLAY <world> [realm] [character]` – enter gameplay by selecting a world, an optional realm, and an optional character.

`public_production_onboarding` is the lobby route class for discovery and first-join work in the default public production realm. Brand-new authenticated accounts may see that realm before membership exists, but must explicitly use `Join & Play` or `JOIN <world>` before character creation or connect-token issuance. The resulting membership is the intended durable account-to-game relationship used by later discovery and return flows. `PLAY` and the gameplay transport are classified as `gameplay_admission`.

Realm discovery and routing contract:

- A tenant may expose multiple player-addressable realms. Each realm-routing record is explicitly `OPEN` on exactly one admissible `gameInstanceId` or `CLOSED` with none and is owned by Game Session; visibility remains separately revisioned catalog/policy state.
- One realm may be designated as the default public production realm. In v1, this production realm is the only realm that may be publicly discoverable without an existing tenant membership row, and `public_production_onboarding` governs the first-join path through that realm.
- Additional realms are access-controlled in v1. Unauthorized or hidden realms must not appear in discovery, and non-production realms such as playtest forks require explicit access grants.
- Explicit access grants for non-public realms are sourced from Account Service runtime grant authority, not from Game Session-local configuration or frontend-cached state.
- Connect-token issuance, `REALMS`, `CHARS`, and `PLAY` must all consume the same realm-routing state so clients never infer realm identity from transport-side hints alone.
- Realm-routing state is split into the visible realm catalog plus the current admission pointer for one `{tenantId, worldSlug, realmSlug}` target. The realm catalog answers "is this realm visible and selectable for this caller?" while the admission pointer answers "which exact `gameInstanceId` is currently admissible for that realm?".
- Clients may cache visible realm choices for presentation, but admission-critical flows must re-read current pointer truth before binding or minting connect scope.

Lobby discovery source-of-truth contract:

- `WORLDS` must be sourced from Account Service tenant-membership, public-production discovery, and entitlement state (not from opportunistic local caches alone) so world visibility and billing state cannot drift across services.
- If entitlement refresh is unavailable, `WORLDS` may present a last-known visible game with an explicit availability-unknown state. Discovery is not authority: it cannot create membership, mint a connect token, bind gameplay, or start capacity, and the applicable strict or continuity entitlement check still runs before those operations.
- `REALMS <world>` must distinguish between public-production visibility and explicit realm grants. Only the default production realm may be visible through public discovery in v1.
- Bootstrap discovery, `REALMS`, `POST /auth/connect-token`, and `PLAY` must all consume the same Account Service-owned realm-access-grant authority for non-public realms so visibility and admission cannot drift by surface.
- Losing realm visibility or realm-grant authority must fail admission before gameplay binding; clients must not remain eligible for a non-public realm only because they still hold a stale discovery response.
- `CHARS <world> [realm]` resolves the selected realm through the current catalog and admission pointer, then performs the matrix-declared `membership`, `membership_generation`, `realm_visibility`, `conditional_realm_access_grant`, `runtime_entitlements`, and `admission_pointer` checks before reading the authoritative character store for the resolved `{tenantId, gameInstanceId}` target. Missing or ambiguous routing and stale, closed, or hidden realms fail closed before character data is read. `CHARS` is still a character-list operation rather than gameplay admission: it does not create a gameplay binding or perform `PLAY`'s final binding CAS. Shared-state realms may surface the tenant's normal live characters, while isolated realms may surface copied, seeded, or otherwise instance-local character state for the same account.
- `WORLDS` and `CHARS` responses must not leak inaccessible tenants or characters; unresolved selectors return canonical errors (`WORLD_NOT_FOUND`, `WORLD_ACCESS_DENIED`, `CHARACTER_NOT_FOUND`, `CHARACTER_ACCESS_DENIED`) without exposing whether a hidden tenant exists.

Lobby command classification contract:

- `WORLDS_PUBLIC` is the public browse-only mode before authentication. `WORLDS_AUTHENTICATED` is the authenticated **pre-tenant discovery** mode, not a normal tenant-scoped route; it runs after account authentication but before a single `tenantId` has been selected.
- `REALMS <world>` is always classified as `public_production_onboarding`, including both public-production discovery before membership and tenant-scoped evaluation after `<world>` resolves. After server-side world/tenant resolution but before membership exists, it may return only the exactly resolved, publicly visible default public-production realm with current entitlement authority and no membership/grant requirement. For an existing relationship, public production still permits discovery when public visibility and entitlement pass, while every non-public realm requires membership with exact `membershipLifecycleState=ACTIVE` plus the Account-owned grant for the exact `{accountId, tenantId, worldSlug, realmSlug}`; a grant never substitutes for membership and `REALMS` never creates membership or gameplay authority.
- `REALMS <world>` resolves the current catalog/pointer pair before returning a realm. A missing, malformed, ambiguous, stale, or unavailable pair is `ADMISSION_POINTER_UNAVAILABLE`; a complete `CLOSED` pointer is an unavailable realm rather than an incomplete pointer. It remains discovery before binding to a concrete `gameInstanceId`, but it is not reclassified as `pre_tenant_discovery` or `tenant_regular` at either stage.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` become tenant/realm-scoped only after `<world>` and optional `[realm]` are resolved server-side to canonical `{tenantId, gameInstanceId}`.
- Shared auth middleware and route-matrix entries must not model all lobby commands as one undifferentiated tenant-scoped surface.

The `PLAY` flow:

- Resolves `<world>` to a canonical `tenantId` and validates it exists.
- Resolves optional `[realm]` to a canonical realm for that tenant. If no realm is supplied, the tenant's default production realm is selected.
- Verifies that the account is authorized to play in that `tenantId` using caller-bound gameplay membership and any required realm grant. Global roles and public discoverability alone must not satisfy gameplay admission.
- If the public realm is visible but the account's durable membership is missing, returns `JOIN_REQUIRED` with `JOIN <world>`/`Join & Play` recovery guidance and does not create membership or other admission state. Explicit public-production `JOIN` may create that membership only when missing; private/playtest admission requires existing membership plus its current grant and never invokes this writer.
- Membership creation writer authority remains Account Service. The accepted canonical API is `JoinPublicProductionMembership`; the differently named current proto seam `EnsurePublicProductionPlayerMembership` is implementation drift, not a retained compatibility operation. It must be replaced after the Account proto/service, authenticated caller, Gateway/auth routing and allowlists, configuration, tests, and generated references converge on the canonical name. The text-protocol adapter passes only the Account-bound `{connectScopeId, requestId}` plus trusted caller context to `JoinPublicProductionMembership`; world and realm remain adapter-local selectors, and the adapter never passes client- or adapter-supplied `tenantId`, route, `gameInstanceId`, or route authority. Account resolves and revalidates the tenant, world, realm, game instance, and pointer version from `connectScopeId` at the commit gate rather than accepting those fields as independently authoritative player inputs. The operation is explicit `JOIN`/`Join & Play`; current Game Session, connect-token issuance, character creation, and `PLAY` must not create membership implicitly.
- Performs an authoritative internal membership read for `{accountId, tenantId}`, requires `membershipExists=true` and exact `membershipLifecycleState=ACTIVE`, and obtains both `membershipVersion` and `membershipAuthorityGeneration`. For connect-token-backed admission, these three values must exactly equal the issuance baseline captured from one Account snapshot; the baseline is carried unchanged through the Account lease, Game Session binding CAS, and Account finalization. The membership response must also assert `gameplayAdmissionAllowed=true`; gameplay admission must not source membership state, either membership field, or gameplay authority from JWT claims or local caches. Before the binding can become admissible, Account must issue an exact-binding admission lease containing a unique lease ID, monotonic lease fence, immutable authority tuple/cutoff checkpoint, the unchanged membership lifecycle/generation/version baseline, and a bounded absolute expiry no longer than the admission SLA; the lease cannot be renewed to extend an admission attempt. Game Session's binding CAS includes that fence and may publish only a provisional record. Account finalization, abort, and reconciliation are idempotent by lease ID and request ID and must reject any recomputed or changed baseline. A binding CAS failure, lease expiry, authority cutoff, membership lifecycle/generation/version change, or ambiguous outcome invokes the idempotent Account abort/reconcile path; Account may retire any orphan token, but emits durable orphan binding evidence and a cleanup request, Gateway performs fenced edge cleanup, and Game Session performs fenced binding/index cleanup. A provisional record is never admissible, resumable, or takeover-eligible; only a durably finalized `COMMITTED` lease can admit gameplay.
- Consults the runtime entitlement contract `GetTenantEntitlementsForRuntime(tenantId, requestId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated).
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId, gameInstanceId}` according to the selected realm's character policy.
  - Explicit character creation and selection are part of the v1 contract. If the selected realm has no visible character for the caller, the client must complete the canonical character-creation flow before `PLAY` can succeed.
  - `PLAY` may omit `[character]` only when exactly one visible character exists for the resolved realm. Otherwise admission fails with `CHARACTER_REQUIRED`.
- Resolves the selected realm's gameplay-admissible instance and records that `gameInstanceId` in the gameplay binding.
  - First-party `/ws/game/**` contract: if a validated connect token is present, resolved `tenantId` and `gameInstanceId` must match token claims. On mismatch, reject admission with `CONNECT_SCOPE_MISMATCH` and do not bind session scope.
  - Runtime control-plane and admission flows use the realm-routing contract from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) as the source of truth for which concrete `gameInstanceId` is admissible for the selected realm.
- **Current live admission boundary:** `PLAY` is authoritative at the selected `{tenantId, gameInstanceId}` runtime target and binds the gameplay identity under `{tenantId, gameInstanceId, characterId}`. The current first slice resolves the admissible `gameInstanceId` from realm routing but does not claim that `PLAY` already performs authoritative `regionId`, `regionEpoch`, or lease-fence resolution.
- **Target authoritative admission:** before committing gameplay binding, resolve the current `regionId` and lease owner/fence for the selected `{tenantId, gameInstanceId}` runtime target from the Game Session control plane. The binding CAS must also carry the Account-owned exact-binding admission lease/fence and applicable authority cutoff checkpoint; a membership, grant, or billing authority advance that fences that lease before Account finalization makes the local record non-admissible. The binding and any forwarded request preserve the selected `playableStateScope`, but that scope does not create a separate lease owner.
  - Reachable but missing or ambiguous region ownership fails closed with `OWNERSHIP_UNAVAILABLE`; an unreachable or timed-out ownership dependency returns `AUTH_UNAVAILABLE`.
  - A stale or mismatched region, `regionEpoch`, lease fence, or verified routing target fails closed with `STALE_TIMELINE` or the applicable `CONNECT_SCOPE_MISMATCH`; `PLAY` must not bind from cached ownership, raw transport headers, or a stale discovery result.
- On successful admission, runtime must return the resolved realm bundle identity at minimum as `versionId`, optional `scriptPatchVersion`, and manifest location/hash (or a stable bundle token that resolves to those fields) so clients can apply realm-specific branding and assets.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Target-state admission must ensure the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, gameInstanceId, regionId>`. Per `design/architecture/decisions/adr-0007-edge-sharding-and-close-taxonomy.md`, `/ws/game/**` is routed to a stable Game Session service endpoint and the edge does not implement a lease-aware shard routing plane.

### PLAY Current and Target Failure Boundaries

The current shipped `PLAY` path resolves the selected realm to its admissible `{tenantId, gameInstanceId}`, validates caller-bound gameplay access and entitlement state, resolves the character, and binds `{tenantId, gameInstanceId, characterId}`. It does not claim that the live path already resolves or commits the target region, region epoch, lease owner, or lease fence. Those ownership and timeline checks are target-state responsibilities described above and must not be reported as current behavior merely because their failure codes are already reserved.

`PLAY` has an explicit pre-admission versus post-admission failure boundary. Before Account finalizes the exact-binding admission lease and Game Session publishes an admissible binding, an unreachable or timed-out non-routing lease, binding/CAS fence, applicable authority or projection, or required coordination-health dependency returns retryable `AUTH_UNAVAILABLE` / HTTP 503. No provisional binding becomes admissible, no cached authority or local binding substitutes for the dependency, and the client retains authentication state for bounded retry. Routing/pointer dependency failure is excluded from `AUTH_UNAVAILABLE`: missing, malformed, stale, ambiguous, unreachable, or timed-out catalog/pointer authority returns `ADMISSION_POINTER_UNAVAILABLE`, while a complete `CLOSED` pointer returns `REALM_UNAVAILABLE`. A reachable missing, malformed, stale, regressed, expired, mismatched, or revoked non-routing value remains the applicable denial or `AUTH_SESSION_REVOKED`; it is not reclassified as an outage.

After a binding is admitted, the token-authority-only continuation exception from [ADR 0037](./decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md) applies only when a live current-generation coordination-health check and the prior-positive ADR 0030 authority-freshness lease both remain valid. An authority cutoff or reachable revoked result is `auth-revoked`: Game Session closes the socket, removes admission state, and ends the current resume episode; after disconnect, restoration requires fresh `LOGIN` plus `PLAY` and current authoritative checks. A same-stream outbox gap or conflict, stale/regressed projection, or unavailable/ambiguous active-binding index stops affected admission and reconnect/resume; unreachable reconciliation or index authority remains retryable `AUTH_UNAVAILABLE` while the operation stays denied, and reachable contradictory or revoked evidence is `AUTH_SESSION_REVOKED` or the specific denial. Affected active bindings are terminated through the bounded `<=60-second` cleanup path, and unaccounted bindings remain blocked until reconciliation proves coverage. Complete Coordination Redis failure is not a token-authority-only outage: correctness-sensitive gameplay mutations halt, local state cannot authorize gameplay, and reconnect/resume or fresh admission remains closed until the existing bounded close/recovery path re-establishes authority; a binding terminated by that path requires fresh `LOGIN` plus `PLAY`.

`CONNECT_SCOPE_MISMATCH` and `STALE_TIMELINE` are intentionally disjoint:

- `CONNECT_SCOPE_MISMATCH` means the verified first-party connect context or connect-token scope does not match the `{tenantId, gameInstanceId}` selected by `PLAY`. It is an admission-scope/issuance drift and requires fresh bootstrap, token issuance, and connection establishment.
- `STALE_TIMELINE` means the selected runtime target was valid, but its authoritative region, epoch, lease fence, or equivalent runtime timeline no longer matches at the ownership check. It requires rediscovery and explicit retry; it must never be repaired by silently rebinding to a different target.

The target ownership checks may produce `OWNERSHIP_UNAVAILABLE` for a reachable but incomplete or ambiguous ownership response; an ownership or coordination dependency that cannot be read or times out returns `AUTH_UNAVAILABLE`. Routing/pointer failures remain `ADMISSION_POINTER_UNAVAILABLE` or `REALM_UNAVAILABLE` as defined above. They must not relabel a verified connect-scope mismatch as a stale timeline or relabel a stale runtime fence as a connect-scope mismatch.

The following is the required cross-surface `PLAY` outcome inventory; it is exhaustive for the routing, scope, and tenant-billing outcomes shared by text and first-party flows, but not for every character or protocol-stage error listed afterward:

- `WORLD_NOT_FOUND` – the supplied world selection cannot be resolved to a tenant.
- `WORLD_ACCESS_DENIED` – the authenticated account is not authorized for gameplay admission in the tenant under caller-bound membership authority. Global roles alone must not satisfy this check.
- `JOIN_REQUIRED` – the current public-production onboarding predicate has not established the required durable membership. Preserve authenticated state and require explicit `JOIN`/`Join & Play`; `PLAY`, character creation, and connect-token issuance must not create membership. If fresh entitlement authority needed to evaluate joining is unavailable, use `ENTITLEMENT_UNAVAILABLE`; if it authoritatively denies the join, use `TENANT_BILLING_BLOCKED` instead.
- `AUTH_UNAVAILABLE` – a required current non-routing authority dependency is unreachable or timed out. Preserve authenticated state, create no gameplay binding, and retry the affected admission with bounded backoff; never authorize from cached authority. Routing/pointer failures use `ADMISSION_POINTER_UNAVAILABLE` instead.
- `ENTITLEMENT_UNAVAILABLE` – fresh entitlement authority cannot be established for the admission or join decision. Preserve authenticated state, create no gameplay binding or new membership, and retry with bounded backoff; do not use stale entitlement state except for the exact bounded same-binding continuity contract.
- `PUBLIC_PRODUCTION_ADMISSION_DENIED` – the caller does not satisfy the public-production admission policy for the selected default production realm, or the realm is no longer eligible for public first admission.
- `TENANT_BILLING_BLOCKED` – the authoritative entitlement state denies the requested action, including `grace` for public join or another new commitment and `suspended`/`canceled` for gameplay admission.
- `TENANT_QUOTA_EXCEEDED` – entitlements allow gameplay but quota caps (for example maximum active sessions) would be exceeded.
- `CONNECT_CONTEXT_INVALID` – first-party `/ws/game/**` admission is missing or has invalid/expired gateway-signed connect context for the handshake that required connect-token verification.
- `CONNECT_SCOPE_MISMATCH` – first-party `/ws/game/**` reconnect/admission attempted `PLAY` scope that does not match the connect-token `{tenantId, gameInstanceId}`.
- `ACCOUNT_MISMATCH` – bootstrap-backed `LOGIN` resolved to an account different from the validated connect-context subject, so no gameplay scope may be bound.
- `ADMISSION_POINTER_UNAVAILABLE` – the catalog/pointer authority for the selected realm is missing, malformed, ambiguous, stale, unreachable, or timed out; preserve authenticated state, create no binding, rerun discovery/reconciliation, and retry only with fresh routing evidence. It is never converted to `AUTH_UNAVAILABLE`.
- `REALM_UNAVAILABLE` – the selected realm has a complete authoritative `CLOSED` pointer and no current admissible gameplay target; preserve authenticated state and create no binding, but do not retry until fresh discovery shows the realm open.
- `OWNERSHIP_UNAVAILABLE` – a reachable runtime ownership response is incomplete or ambiguous; no gameplay binding is created. An unreachable or timed-out ownership, lease, or coordination dependency returns `AUTH_UNAVAILABLE`.
- `STALE_TIMELINE` – the selected region, epoch, or lease fence no longer matches current runtime authority; the client must rediscover/retry rather than being rebound implicitly.
- `PLAY_REQUIRED` – a gameplay command requiring admitted gameplay scope was issued before `PLAY` completed successfully.
- `CHARACTER_REQUIRED` – the selected realm requires an explicit character choice because zero or multiple visible characters exist for the caller.
- `CHARACTER_CREATION_NOT_ALLOWED` – the selected realm has no visible character for the caller and current realm or fork policy forbids creating a new one; clients must surface this as a hard deny rather than as a generic selection prompt.
- `CHARACTER_NOT_FOUND` / `CHARACTER_ACCESS_DENIED` – character selection is requested but the character cannot be found or is not owned by the account.
- Any subsequent attempt to switch tenants or characters for a socket must go through the same tenant-selection flow so that role checks and entitlements are re-evaluated; there is no implicit cross-tenant switching based solely on the initial `LOGIN`.

First-party gameplay admission and reconnect clients should treat the following errors as canonical:

| Surface | Canonical code | Trigger condition | Required client reaction |
| --- | --- | --- | --- |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_MISSING` | Connect token is absent where required | Obtain a fresh connect token and open a new socket with bounded retry/backoff. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_EXPIRED` | Connect token expired before gateway validation completed | Obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Obtain a fresh connect token and open a new socket with bounded retry/backoff; repeated replay failures should not fast-loop. |
| `/ws/game/**` handshake (`403`) | `CONNECT_SCOPE_MISMATCH` | Handshake-carried scope does not match the verified connect-token scope | Rerun bootstrap discovery for the intended realm target, obtain a fresh connect token, and open a new socket. |
| `/ws/game/**` handshake (`403`) | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded slower backoff and surface temporary edge-auth-unavailable context rather than backend-outage messaging. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REJECTED` | Connect token is malformed, signature-invalid, missing required claims, wrong-audience, or otherwise rejected outside the narrower classes above | Obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `POLICY_DENY` | Edge policy rejects the handshake for a non-token reason (for example proxy trust/config mismatch) | Treat as non-retriable until operator/client configuration is corrected. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_CONTEXT_INVALID` | Required gateway-signed connect context is missing, expired, unverifiable, or otherwise invalid | Refresh connect token, reconnect, then re-`LOGIN`; do not retry `PLAY` on the current socket. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_SCOPE_MISMATCH` | The server-resolved runtime target for the requested stable world/realm selector does not match the validated connect-token scope | Re-select the intended world/realm, obtain a fresh connect token for that target, reconnect, and retry `PLAY`. |
| `LOGIN` on first-party `/ws/game/**` | `ACCOUNT_MISMATCH` | Bootstrap-backed login resolved to an account different from the validated connect-context subject | Treat as a hard auth failure for the current socket; clear the gameplay bootstrap/connect flow and require a fresh authenticated bootstrap. |
| `PLAY` | `WORLD_ACCESS_DENIED` | Caller-bound membership authority does not allow gameplay admission for the resolved tenant | Keep auth state, surface an authorization error, and do not infer hidden-tenant existence beyond the canonical code. |
| `PLAY` | `JOIN_REQUIRED` | Current public-production membership is missing and explicit join has not succeeded | Preserve auth state, present `JOIN` / `Join & Play`, and block character creation, connect-token issuance, and `PLAY` retry until join succeeds. Never create membership implicitly; private/playtest targets still require existing membership plus their current grant. |
| `PLAY` | `REALM_UNAVAILABLE` | The current complete pointer says the selected realm is `CLOSED` and has no admissible target | Preserve authenticated lobby state, create no gameplay binding, and wait for fresh discovery after the realm reopens; do not fast-loop retries. |
| `PLAY` | `ADMISSION_POINTER_UNAVAILABLE` | The catalog/pointer pair is missing, malformed, ambiguous, stale, unreachable, or timed out | Preserve authenticated lobby state, create no gameplay binding, rerun discovery/reconciliation, and retry only with fresh pointer evidence; never substitute a cached target and never relabel this as `AUTH_UNAVAILABLE`. |
| `PLAY` | `CONNECT_SCOPE_MISMATCH` | The verified connect context does not match the server-resolved `{tenantId, gameInstanceId}` for the selected stable world/realm | Preserve account authentication but discard the complete discovery snapshot and all derived token metadata, including `catalogRevision`; rerun discovery, issue a fresh token, open a new socket, and retry `LOGIN`/`PLAY`. |
| `PLAY` | `TENANT_BILLING_BLOCKED` | Entitlement state authoritatively denies gameplay, including `suspended`/`canceled` or a `grace` state that denies the requested new commitment | Preserve auth state, create no gameplay binding, surface the tenant billing block, and disable the denied admission until Account reports an allowed state; billing-safe operations remain available unless independently denied. |
| `PLAY`, new admission, restart/rollback, another new commitment, or ineligible continuity operation | `ENTITLEMENT_UNAVAILABLE` | Fresh entitlement authority is unavailable and no operation-eligible last-known-good snapshot exists; strict new commitments require a snapshot fresh enough for the 15-second admission SLA | Keep auth state, retry with bounded backoff, never admit a strict commitment from stale entitlement state, and never use grace after hard denial, revocation, or sequence uncertainty. |
| `PLAY` before admission commits | `AUTH_UNAVAILABLE` | Required non-routing Account lease, binding/CAS fence, applicable authority/projection, reconciliation, or coordination-health dependency is unreachable or times out | Keep auth state, create no admissible binding, and retry with bounded backoff. A reachable invalid/revoked value uses the applicable denial or `AUTH_SESSION_REVOKED`; routing/pointer failures use `ADMISSION_POINTER_UNAVAILABLE`. |
| `PLAY` | `OWNERSHIP_UNAVAILABLE` | A reachable runtime ownership response is incomplete or ambiguous for the selected target | Keep auth state, create no gameplay binding, rediscover runtime ownership with bounded backoff, and retry admission only after fresh authority is available. Unreachable or timed-out ownership uses `AUTH_UNAVAILABLE`. |
| `PLAY` | `STALE_TIMELINE` | The selected region, epoch, or lease fence no longer matches current runtime authority | Keep auth state, create no gameplay binding, rediscover the realm and runtime timeline, and retry admission explicitly; never accept an implicit rebind. |
| Gameplay command before `PLAY` | `PLAY_REQUIRED` | Client issued a world-scoped gameplay command before lobby admission completed | Keep auth state and route the client back through `PLAY`, `REALMS`, or `CHARS` as appropriate. |

Clients re-authenticate **only after disconnecting** (TCP or WebSocket loss) or when server-side auth state has expired or been revoked. Direct text/Telnet reconnect resends the applicable credential-bearing `LOGIN` flow, takes the conditional `JOIN` step when required, and completes `PLAY`; it has no connect-token reconnect snapshot, so `connectScopeId`, `catalogRevision`, and `CONNECT_SCOPE_MISMATCH` do not apply to that reconnect mode. Token-backed `/ws/game/**` reconnect obtains a fresh connect token, opens a new socket, issues bare `LOGIN`, and completes lobby selection again (`PLAY <world> [realm] [character]`). Only that token-backed reconnect uses the snapshot bundle containing the selected world/realm/character, `connectScopeId`, `tenantId`, `gameInstanceId`, `pointerVersion`, `catalogRevision`, `evaluatedAt`, and `connectScopeExpiresAt`; a `CONNECT_SCOPE_MISMATCH` discards the entire bundle and all derived connect-token metadata. If a resumable gameplay session exists for the selected `{tenantId, gameInstanceId, characterId}`, the Game Session Service resumes it; otherwise it creates a fresh gameplay session binding.

Gameplay identity is canonicalized on `characterId` within a tenant. All Redis key formats and Game Session Service APIs must treat `characterId` as the abstract character identifier so sessions bind sockets to characters rather than raw accounts. Canonical takeover and resume identity is `{tenantId, gameInstanceId, characterId}`.

Gameplay identity is single-mode and canonical: uniqueness key `{tenantId, gameInstanceId, characterId}`.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Logout Ordering

The canonical per-token and logout-all ordering, retry, token-fence, Gateway deny-marker, and reconciliation contract is defined once in [Session Behavior](./system-architecture-session-behavior.md#control-plane-logout). Authentication surfaces must follow that contract rather than restating or weakening it here.

---

## Related Token and Session Contracts

The detailed token and lifecycle contracts now live in focused sibling docs:

- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) defines JWT claim requirements, token profiles, issued-token registry records, authority generations, and Redis-outage behavior for token validation.
- [Session Behavior](./system-architecture-session-behavior.md) defines gameplay takeover, session rebinding, mid-session role refresh, membership-version handling, and control-plane logout behavior.

---

## Role-Based Authorization

Access to services is governed by roles from the JWT:

| Context | Description |
| --- | --- |
| `globalRoles` | Platform-wide access (e.g., moderation, admin dashboards) |
| `scopedRoles` | Per-game access (e.g., designer tools, admin features for a game) |

### JWT Usage Scope

- ✅ **Meta/control services** (e.g. Game Design, Admin, Account) validate JWTs to authorize access
- 🚫 **Gameplay services** (e.g. Game Logic, Entity, World) do **not** validate JWTs — they rely on the Game Session Service to enforce access

Because gameplay services do not validate end-user JWTs, they must still enforce a strict internal trust boundary:

- All gameplay gRPC endpoints must require mTLS and must validate the caller’s service identity (for example via SPIFFE/SAN allowlists) so only authorized internal callers (typically the Game Session Service) can invoke gameplay APIs.
- Gameplay services must treat tenant/session/player identifiers in requests as scoped data that requires validation. Client-supplied headers cannot create trusted context, and an authenticated workload may invoke only explicitly allowlisted methods.

### Gameplay Player Execution Context Contract (Normative)

When one trusted gameplay workload calls another on behalf of a player, the request carries a typed protobuf `PlayerExecutionContext` with the required subset of:

- `accountId`
- `tenantId`
- `gameInstanceId`
- `characterId`
- `sessionId`
- applicable room, region, lease/epoch, admitted-bundle, realm, pointer, or playable-state scope
- stable request, command, or effect identity where the operation requires it

`PlayerExecutionContext` is unsigned structured scope data, not a credential. Consumers authenticate the immediate caller through its concrete mTLS certificate identity, check the RPC's caller allowlist, validate context/request equality, and enforce the complete tenant/game/resource and domain-ownership scope in existing reads and writes. These checks must not add a fresh Account, Redis, or database lookup solely to authorize every routine action.

Gameplay mutations use their command/effect/request idempotency contract. Reads do not use a generic replay store. FireMUD deliberately accepts that a compromised allowlisted intermediary can fabricate player context for methods it is permitted to call; [ADR 0024](./decisions/adr-0024-trusted-gameplay-workload-delegation.md) records that trust boundary and the separate protections for operator and financial actions.

All meta services use a shared `AuthTokenInterceptor` that extracts claims from the `Authorization` header and stores them in a thread-local `SessionContext`. Service methods read roles from this context via the `@RequireAdminRole` annotation (or similar). Gameplay services never read or propagate these claims.

### Mandatory Auth Middleware

All meta/control services that depend on JWT claims must install the shared security configuration that wires `AuthTokenInterceptor` into both HTTP and gRPC stacks. Account-owned `pending_deletion_scoped` routes use the dedicated pending-deletion credential validator and workflow registry instead of `AuthTokenInterceptor`; they use no JWT issuer/account/tenant/membership generations and must not fall back to normal JWT or session authority. Protected control-plane and JWT-bearing bootstrap/admission operations must pass through the strict registry and applicable-generation checks for their route class. Fresh gameplay admission, in-band `PLAY`, reconnect, and resume must pass their bound-session admission checks and only the current-authority checks required by those contracts; they do not add JWT registry/generation lookups to routine gameplay commands. Once a gameplay binding is admitted, routine gameplay commands use its validated bound context and bounded reconciliation rather than repeating registry or generation lookups on every command. No controller or gRPC service that relies on authorization may be reachable without passing through its exact route auth path. New routes that require authentication must opt into this configuration from the outset; adding endpoints that bypass it is considered an architectural violation and must be corrected before promotion to shared environments.

---

## Trust Boundaries and Token Validation

The Gateway sits at the edge of the platform and is deliberately **not** an authorization authority:

- Spring Cloud Gateway enforces the presence of an `Authorization` header for protected routes but does not validate or interpret JWT contents.
- All meta/control services that receive requests from the Gateway must validate JWTs using the Account Service JWKS and the shared `AuthTokenInterceptor`. No route that depends on JWT claims may bypass this middleware.
- Gameplay services never accept or validate browser- or client-supplied JWTs directly. They authenticate the immediate workload through concrete mTLS identity and exact method allowlists, and consume only validated typed `PlayerExecutionContext` from approved gameplay callers. Receiver-specific private delegation JWTs remain limited to explicit Account/control-plane calls and are not the gameplay-service trust boundary.

When adding a new public HTTP/gRPC route:

- Classify it using the shared classes from [Authorization Route Matrix](./system-architecture-authz-route-matrix.md): `public`, `account_scoped`, `caller_membership_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `public_production_onboarding`, `gameplay_admission`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, `internal_workload`, or `pending_deletion_scoped`.
- For non-public routes, require the route-matrix-defined authentication contract and the Tenant Authorization Contract described above. JWT-bearing route classes install `AuthTokenInterceptor`; `internal_workload` routes use their exact authenticated mTLS identity, method allowlist, and typed context contract instead of a blanket JWT requirement.
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, or tenant-scoped data export), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`). Full account export remains `account_scoped` and must not be used as the suspended-tenant recovery export.
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.
- Register the route and its classification in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md). Runtime middleware rejects an unclassified protected route immediately; independently, CI and deployment policy checks fail a validated candidate route with missing registration. Neither check generates runtime policy from the incomplete matrix.

## Session Lifecycle and Rebinding

Gameplay takeover, reconnect, token refresh, membership-version handling, and control-plane logout behavior are defined in [Session Behavior](./system-architecture-session-behavior.md). This parent doc keeps the admission and authorization model while the sibling doc carries the long-form lifecycle rules.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | `LOGIN` (or `LOGON`) — prompt exchange is target-only; current clients use argument forms, with `LOGIN <email>` then `LOGIN <email> <code>` for the email-code challenge |
| JWT Usage | Raw Telnet gameplay command streams do not carry JWTs; browser/mobile gameplay uses a short-lived `player-bootstrap` JWT for HTTPS bootstrap and an HttpOnly-cookie connect token for `/ws/game/**`; public non-browser issuance and the dedicated connect-token handshake header remain target-only/unavailable until fully registered and proven; admin/creator UIs and backend services use their exact permitted profiles |
| Claims | Profile-dependent: shared JWT claims include `iss`, `sub`, `jti`, `aud`, `iat`, `nbf`, and `exp`; applicable profiles additionally carry `accountId`, `tokenGeneration`, `globalRoles[]`, or `scopedRoles{}` according to the token contract |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Gameplay Continuity TTL | Separate `session_expiration_ms` policy with an independent effective maximum of five minutes; the configurable JWT cleanup margin applies only to issued-token registry retention |
| Issued-Token Registry TTL | Each token record is retained through its actual JWT `exp` plus `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS`; activity does not extend it |
| Gameplay Reauthentication | Only after disconnect, expiry, or revocation; client re-issues `LOGIN`, and Game Session resumes via Redis if the underlying gameplay and auth session state are still valid. No active-gameplay reauthentication or elevation |
| Role Enforcement | Meta/control services validate JWTs directly; gameplay services enforce concrete mTLS workload identity, method caller allowlists, and validated `PlayerExecutionContext` scope |
| Role Updates | Target: Account-owned role/token refresh is intended to be invisible in-session; current role-refresh regeneration and end-to-end proof remain a documented implementation gap |
| Multi-Client Behavior | One session per character; new login replaces old session |
| Login Modes | `PASSWORD` and verified-email `EMAIL_OTP` are the current account-level modes; authenticator-app factors remain future work |

---

## Related Documentation

- [Authorization Route Matrix](./system-architecture-authz-route-matrix.md)
- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Session Behavior](./system-architecture-session-behavior.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](./user-journeys-players.md#1-sign-up)
