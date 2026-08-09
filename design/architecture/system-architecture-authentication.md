# FireMUD System Architecture: Authentication & Authorization

This document defines the target-state authentication and authorization contract for FireMUD: Account-owned identity and authority, exact JWT profiles, gameplay session binding, and role-based access across services.

## Normative Target Contract

The canonical target is Account-owned authentication and authority, exact JWT profiles, explicit membership before gameplay admission, caller-bound tenant authorization, and concrete mTLS identity for internal workload calls. Global roles authorize only the route classes declared by the shared matrix; they do not create tenant membership or gameplay authority. A protected route, admission-affecting bootstrap or mutation operation, or connect-token issuance fails closed when its exact authority, scope, replay, or freshness evidence is unavailable or ambiguous. Read-only discovery, including `WORLDS`, may return an explicit availability-unknown result when entitlement refresh is unavailable; it cannot authorize admission, create membership, mint a token, bind gameplay, or start capacity.

### Canonical Authority Tuple

The canonical `authorityTuple` schema, exact nested field names, profile requirements, and registry mapping are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#canonical-authority-tuple). This document does not duplicate that schema. Authentication copies the tuple unchanged only into profiles, artifacts, and operations that declare it applicable, including applicable JWT claims, issued-token records, revocation events, Account leases, gameplay bindings, refresh requests, rebind proofs, and installation acknowledgements. Profiles and artifacts that omit the tuple must not fabricate one or inherit unrelated scope; a missing applicable field, extra scope, malformed value, or mismatch fails closed.

Authentication applies the canonical `authorityTuple` unchanged to admission and authorization where the selected profile or artifact requires it. Account-owned generation/projection rules, route-class exceptions, and token-registry semantics remain canonical in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) and [Authorization Route Matrix](./system-architecture-authz-route-matrix.md); `membershipVersion` is separate membership-version evidence and never substitutes for the `membershipAuthorityGeneration` member of `authorityTuple`.

## Implementation Status

- **Current gameplay authentication and transport boundary:** Current gameplay authentication supports `LOGIN <email>` / `LOGON <email>` to request a verified-email login code and `LOGIN <email> <secret>` / `LOGON <email> <secret>` for an immediate password-or-code attempt on raw Telnet and other non-WebSocket text clients. Bare raw-text `LOGIN`/`LOGON` prompt exchange is target-only and currently returns `PROMPT_LOGIN_UNSUPPORTED`. Control-plane clients use `/auth/login`. First-party gameplay uses `POST /auth/player-bootstrap` to establish the `player-bootstrap` identity, then uses `/auth/bootstrap/*` discovery/action routes and `/auth/connect-token` before the gameplay socket. Clients are stateless; current Account-owned legacy authentication session records persist `tokenHash` and session metadata under the legacy `session:auth:account:<accountId>:<tokenHash>` key and, for tenant-scoped sessions, the companion `session:auth:tenant:<tenantId>:<tokenHash>` key, while gameplay bindings remain in Redis. These records never persist the raw delegation JWT; their runtime/data ownership is defined in [Account Runtime and Data](./microservices/account-service/runtime-and-data.md#session-and-token-model). Target issued-token registry issuance, storage, and consumer enforcement in Coordination Redis are unimplemented. The Game Session Service restores gameplay session state from Redis, while the Account Service validates the supplied login secret and issues the exact `control-ui`, `player-bootstrap`, or receiver-specific private player-delegation JWT profile required by the destination. Raw Telnet gameplay command streams never carry JWT authorization. Target browser and mobile-browser gameplay uses a `player-bootstrap` JWT for HTTPS bootstrap calls and a cookie-carried one-use connect token for the `/ws/game/**` handshake; target first-party native-mobile clients that use a cookie jar remain cookie-only. Public non-browser issuance and the dedicated handshake-header carrier are target-only and unavailable until a dedicated route is fully registered and its issuance, registry, profile, and carrier proof is complete; the target design uses protected secure storage plus `X-Firemud-Connect-Token`. First-party admin/creator UIs and backend services use their own permitted token profiles. Provider-specific HTTPS sign-in remains unavailable until the provider-specific verification, collision, recovery, and end-to-end proof required by [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md) is complete.
- **Current first-party endpoint and runtime boundary:** The current first-party browser/mobile runtime includes dedicated `POST /auth/player-bootstrap` and `POST /auth/connect-token` surfaces, Gateway rejection for missing, expired, replayed, or scope-mismatched connect tokens, and Game Session validation of the signed connect context before bare `LOGIN`, but these remain partial implementation surfaces rather than proof of the complete target flow. Current `PLAY` resolves the admissible `gameInstanceId` from realm routing and binds `{tenantId, gameInstanceId, characterId}`; it does not claim authoritative `regionId`, `regionEpoch`, lease-owner, or lease-fence resolution. Public non-browser issuance and the dedicated handshake-header carrier remain unavailable.
- **Current provider-specific sign-in boundary:** Optional provider-specific HTTPS sign-in is permitted only after complete provider proof; password and verified-email code remain the Account-owned baseline and fallback, and Telnet never carries provider credentials. See [ADR 0049](./decisions/adr-0049-optional-provider-specific-external-identity-linking.md).
- **Current raw-text challenge semantics:** The current raw-text email-code challenge is two commands: `LOGIN <email>` (or `LOGON <email>`) requests an Account-owned, server-side verified-email challenge bound to the normalized account/email and its configured expiry without authenticating the session, then `LOGIN <email> <code>` (or `LOGON <email> <code>`) submits that code. The separate immediate-auth form is `LOGIN <email> <secret>` (or `LOGON <email> <secret>`) with one secret. Account selects the `EMAIL_OTP` validator first when the supplied secret matches the live challenge; otherwise it verifies `PASSWORD` when that account mode is enabled. The client does not select the validator, and a code is not valid outside its server-side challenge and expiry.
- **Target only/currently unavailable:** The independently enrolled TOTP-backed `privileged_control` elevation window for `platformAdmin` and cross-tenant `billingAdmin` is not implemented; current runtime must not claim those elevated paths are available.
- **Current fail-closed boundary:** Any route branch that requires the unavailable `privileged_control` window must reject that `platformAdmin` or cross-tenant `billingAdmin` authorization path. A separately declared tenant-role or unelevated `support` branch may remain available only when all of its own live checks pass.
- Character selection and gameplay takeover semantics are canonicalized on `{tenantId, gameInstanceId, characterId}`.
- **Membership behavior (target):** Only an explicit public-production `JOIN` / `Join & Play` through `JoinPublicProductionMembership` may change the durable caller-bound `player` membership. The lifecycle is exact: missing -> `JOIN` creates `ACTIVE` and advances `membershipVersion`; `INACTIVE` -> `JOIN` restores `ACTIVE` and advances `membershipVersion`; `membershipAuthorityGeneration` advances independently only when the transition records `callerBoundAuthorityInvalidated=true`; `ACTIVE` -> `JOIN` returns the exact idempotent current snapshot; every other lifecycle state rejects. `JOIN_REQUIRED` covers missing or `INACTIVE` membership before a successful join. Game Session admission, connect-token issuance, character creation, and `PLAY` require existing `ACTIVE` membership and must never create or restore membership.
- **Membership behavior (current versus target):** The explicit join action and the required `membershipAuthorityGeneration` reread at connect-token issuance remain unimplemented/proof gaps. Current connect-token issuance and text `PLAY` require a fresh current Account membership adapter response identified by `accountId` and `tenantId`, with these four admission-evidence fields: `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`; the first two must be true. Text `PLAY` separately reads the current realm grant for private/playtest targets. The adapter response has no `membershipLifecycleState` field: `membershipExists=false` represents missing membership, while `membershipExists=true` and `gameplayAdmissionAllowed=false` represents existing but non-admitting membership without distinguishing `INACTIVE` from any other existing non-admitting state. An eligible public-production request with `membershipExists=false` may return `JOIN_REQUIRED`; current non-public text `PLAY` maps missing or non-admitting membership to `WORLD_ACCESS_DENIED`. Current connect-token issuance retains its Account-owned rejection mapping and its private/playtest grant validation remains incomplete. Both paths return `AUTH_UNAVAILABLE` for unavailable or unsafe membership authority. This current `JOIN_REQUIRED` result is a fail-closed, non-actionable denial while explicit `JOIN`/`Join & Play` is unimplemented; it must not be presented as proof that the target repair flow is available. The obsolete writer seam has been removed, while the complete first-party browser/mobile sequence remains target-only and individual bootstrap/connect-token endpoints remain partial implementation surfaces. Returning members may proceed when current runtime membership and character checks pass, but current behavior does not prove the target exact generation/version reread at connect-token issuance; the target requires lifecycle state, authority generation, and independent membership version from one fresh membership snapshot. The target character-creation membership precondition and required issuance proof remain implementation/proof gaps. Every target gameplay admission requires the applicable fresh tenant runtime entitlement. Public-production `JOIN` additionally consumes its public-enrollment `allowPublicJoin` policy; private/playtest admission never consumes that public-enrollment predicate and instead requires caller-bound `ACTIVE` membership plus the exact current Account-owned grant. Both paths require exact routing and the fresh membership snapshot. Target missing or non-`ACTIVE` private/playtest membership is `NON_PUBLIC_ENROLLMENT_REQUIRED`, target missing or invalid grant is `REALM_ACCESS_DENIED`, and unavailable authority is `AUTH_UNAVAILABLE`.
- **Target-only/currently unavailable public non-browser issuance:** Public non-browser connect-token issuance and `X-Firemud-Connect-Token` carriage are not current supported capabilities. They may be exposed only after a dedicated route is fully registered in the authorization matrix and its `gameplay-connect` issuance/replay record, `replayAdmissionFence`, quarantine/deny state, signed-context, response, and carrier proofs are complete. This path is exempt from the ordinary active issued-token registry, `tokenGeneration`, `issuanceFence`, and `session:auth:token:<tokenHash>` record; its bounded selected-target `authorityTuple` and separate `membershipVersion` are immutable issuance evidence passed into the later exact-binding admission checks, not a registry lookup or standalone gameplay authority. Current clients must not be told that header support exists.
- First-party browser and mobile-browser gameplay use the short-lived `player-bootstrap` JWT for HTTPS bootstrap calls and carry the resulting gameplay-connect token only in the `Firemud-Connect-Token` HttpOnly cookie. First-party native-mobile and other first-party non-browser clients using a cookie jar remain cookie-only. Telnet and other non-WebSocket text transports use credential-bearing `LOGIN` and do not carry public JWTs or connect tokens.
- `/sessions/{sessionId}/refresh-roles` exists as an operational hook, but current role-refresh token regeneration and periodic active-session `game-session-account-delegation` rotation remain implementation gaps; the placeholder response is not proof of refresh.
- The current Account `Authenticate` proto path still lacks the target `requestId`/immutable-digest replay envelope and orphan-token retirement contract described below; those fields and recovery semantics remain implementation/proof gaps rather than implied current behavior.
- **Current reauthentication boundary:** After a gameplay binding is terminated by an authority cutoff or the bounded cleanup path, current restoration requires fresh `LOGIN` plus `PLAY` and current authoritative checks. Target disconnect, expiry, revocation, refresh, and resume sequencing is owned by [Session Behavior](./system-architecture-session-behavior.md); fresh `LOGIN` then `PLAY` remains the client-visible reauthentication path when required.
- Account's JWKS endpoint and conditional secret watcher are implemented, but asymmetric-profile validation, non-exportable signer delegation for asymmetric signing, rotation/convergence, issued-token registry issuance/storage/consumer enforcement, and Account-owned authority generations remain target-state and unimplemented. No authority-generation issuance, advancement, propagation, or validation proof is currently claimed.

## Contract Decisions (Normative)

The following contract decisions are mandatory and resolve cross-document ambiguity:

- **Authority-generation writer** – The Account Service owns durable issuer, account, tenant, and `{accountId, tenantId}` membership authority generations and is the sole writer of every applicable canonical `authorityTuple` field, cutoff, and `issuanceFence`, plus their canonical `session:auth:generation:*` projections. Account commits the durable tuple/fence, applicable account-security or tenant-billing cutoff, and monotonic outbox event together; the projection is an asynchronous outbox output, not part of that atomic durable transaction. Consumers fail closed while the projection or its freshness/source evidence is missing, stale, malformed, regressed, or ambiguous. Other services must publish billing/security events and must not write canonical authority-generation state or projection keys; Game Session may write only its distinct derived issuer projection under the ADR 0022 schema.
- **Issuer projection checkpoint** – The Redis issuer-generation projections use `lastAppliedSourceOutboxSequence`, `lastAppliedSourceEventId`, and `lastAppliedSourceEventDigest` for the exact Account authority outbox stream. Sequence `0` is permitted only when that stream has no committed event; at that baseline, `lastAppliedSourceEventId` and `lastAppliedSourceEventDigest` are omitted from the projection, never serialized as `null`, an empty string, a zero digest, or another sentinel. For every positive sequence, both source-event fields are required non-null values in their canonical formats, and the projection advances only on the next contiguous sequence after exact checkpoint validation. These are projection-local replay/checkpoint fields, not JWT claims; JWT claims carry only the applicable authority tuple, including `issuerAuthGeneration`.
- **Authority outbox stream** – Account emits each revocation payload on exactly `account:auth-authority:v1:<scopeId>`, where `scopeId` is `issuer/<issuerId>`, `account/<accountId>`, `tenant/<tenantId>`, `membership/<accountId>/<tenantId>`, or `grant/<accountId>/<tenantId>/<worldSlug>/<realmSlug>`. `outboxSequence` starts at `1` independently for each exact stream key; `tenantBillingSequence` remains the separate tenant billing sequence. Consumers keep watermarks per exact stream key: duplicates with matching evidence at or below the watermark are no-ops, the next sequence is contiguous, a higher sequence is a gap only within that same key, and an unrelated scope never creates a gap. Conflicting duplicate evidence or a same-key gap stops affected validation/admission until Account reconciliation proves the exact checkpoint.
- **Authority replay evidence** – Consumers retain only a finite, deployment-configured replay-evidence window per exact `outboxStreamKey`, with a maximum sequence count and retention TTL, keyed by `outboxSequence`; each retained entry includes `eventId`, the canonical event digest, `sourceScope`, the complete applicable `authorityTuple`/`issuanceFence`, and the relevant event payload evidence. When a duplicate at or below the watermark falls outside that window, the consumer must use an Account-owned authoritative lookup for the exact `(outboxStreamKey, outboxSequence)` and compare the supplied evidence for exact equality. Matching evidence is accepted as a no-op; unavailable lookup is retryable `AUTH_UNAVAILABLE` and keeps the affected scope fail closed; reachable missing or conflicting evidence is quarantined for reconciliation and fails closed. Advancing a watermark never makes an unverified older duplicate acceptable.
- **Membership freshness evidence** – Any authoritative Account membership response/evidence used for billing-safe checks, gameplay admission, reconnect, or revocation must carry or authenticate the complete applicable `authorityTuple`, exact `membershipLifecycleState`, separate `membershipVersion`, `evaluatedAt`, and the matching `outboxCheckpoints` entry from one authoritative Account snapshot/transaction or equivalent Account-owned fence. The target response fields are `accountId`, `tenantId`, `membershipExists`, `membershipLifecycleState`, `roles[]`, `gameplayAdmissionAllowed`, `membershipVersion`, `membershipAuthorityGeneration`, `authorityTuple`, `evaluatedAt`, and `outboxCheckpoints[]`; `membershipAuthorityGeneration` is the tuple's membership member, and a separately carried copy must authenticate the same value. The matching checkpoint is `{outboxStreamKey: account:auth-authority:v1:membership/<accountId>/<tenantId>, outboxSequence: <committed sequence>}`. `evaluatedAt` describes that complete snapshot and is not restamped from a projection or cache. Missing fields, a checkpoint that does not cover the tuple, lifecycle state, or membership version, or values assembled from different checkpoints are invalid and fail closed.
- **Membership event continuity** – Membership changes use only `account:auth-authority:v1:membership/<accountId>/<tenantId>`. Each event names `outboxStreamKey`, `sourceScope` (`membership/<accountId>/<tenantId>`), `outboxSequence`, `eventId`, the complete applicable `authorityTuple`/`issuanceFence`, and the Account snapshot's `accountId`, `tenantId`, `membershipVersion`, membership payload, admission flag, and `callerBoundAuthorityInvalidated` result. `sourceScope` must decode to `outboxStreamKey`; each consumer stores a `watermark` and event identity/digest per exact stream and marks delivery `contiguous` only when the next sequence is `watermark + 1` (with first-stream initialization from the Account checkpoint). A duplicate at or below the watermark is valid only when all evidence matches; a same-sequence conflict quarantines the affected scope. A higher sequence is a gap only within that same stream; unrelated streams do not advance or gap this watermark. Conflicts, non-contiguous delivery, missing or mismatched tuple/checkpoint evidence, or an unresolved membership-version/generation pair stop affected admission and reconnect/validation until exact Account reconciliation proves coverage; unavailable reconciliation returns retryable `AUTH_UNAVAILABLE`, while reachable contradictory or revoked evidence is denied/revoked.
- **Authority validation outcomes** – A registry, lease, gameplay binding, token-identity fence, Account authority source, or authority-projection freshness fence that cannot be reached or times out is a retryable `AUTH_UNAVAILABLE` / HTTP 503 condition; this includes runtime membership-authority reads. It does not revoke the client's authentication and no cached authority may authorize the failed operation. Reachable missing, malformed, expired, revoked, stale, regressed, or mismatched evidence is an authentication failure (`AUTH_SESSION_REVOKED` or the specific invalid-token outcome) and requires reauthentication. Registry presence or absence never overrides this classification; membership-authority failures use these same availability-versus-denial rules and do not introduce a second canonical error.
- **Tenant authority-generation scope** – `session:auth:generation:tenant:<tenantId>` applies by default to tenant-scoped regular and gameplay-affecting operations. Only the closed route-class allowlist in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md#explicit-route-class-generation-allowlist) may omit the target tenant generation. The `billing_safe_tenant` class intentionally omits that target-tenant generation only under this closed exception; it still requires issuer/account and caller-bound membership generations plus a live `tenantAdmin` check. `cross_tenant_support_safe` requires issuer/account generations, a live `support` role without elevation or an explicitly allowed `platformAdmin` role with `privileged_control` backed by independent TOTP, and global token scope; and `cross_tenant_billing_safe` requires issuer/account generations, a live `billingAdmin` or explicitly allowed `platformAdmin` role, global token scope, and `privileged_control`. None of these routes may use cached authorization, and no newly named route class inherits the allowlist.
- **Membership authority-generation scope** – `session:auth:generation:membership:<accountId>:<tenantId>` applies to caller-bound tenant authorization for one account in one tenant and advances when membership or tenant roles change without triggering a tenant-wide billing cutoff.
- **Gameplay session identity key** – Session uniqueness and takeover scope are keyed by `{tenantId, gameInstanceId, characterId}`.
- **JWT claim contract** – Services must validate a strict JWT claim profile (required claims and audience per token profile), not only signature plus ad-hoc fields. Registry-backed profiles carry the complete `authorityTuple` and positive `issuanceFence` with the exact nested names above. A field marked `Absent` by its profile is omitted, not encoded as an empty string, null, map, or list; an empty map/list is valid only when that profile declares the field present and its value is empty. `control-ui` must include `scopedRoles` as `{}` when empty; omission is rejected. Its `tenantAuthorityGeneration` keys equal the non-empty scoped-role tenant keys whose allowed routes require target-tenant authority, while its independent `membershipAuthorityGeneration` and `membershipVersion` keys equal the caller-membership tenants needed by the token's allowed route classifications, including a `billing_safe_tenant` key that deliberately has no target-tenant authority entry. Explicitly unscoped profiles use empty maps/lists only where their profile declares those fields present, never absent or fabricated scope values.
- **Authority tuple comparison** – Every applicable `issuerAuthGeneration`, `accountAuthorityGeneration`, exact tenant and membership map entry, private-realm `grantVersion`, `accountSecurityCutoff`, and `tenantBillingCutoff` is compared as part of one tuple. `membershipVersion` is a separate current/stored membership projection check and is never substituted for `membershipAuthorityGeneration`; a tuple, cutoff, fence, or membership mismatch fails closed.
- **Internal gameplay delegation boundary** – Gameplay services authenticate the concrete mTLS workload identity, enforce an exact method-level caller allowlist, and validate a typed `PlayerExecutionContext` against request and domain scope.
- **No universal player attestation** – Routine gameplay delegation does not use signed per-action player attestations or a replay cache. Mutation replay is controlled by the owning command/effect/request idempotency contract.
- **Route classification governance** – Protected routes must be classified in the shared route matrix document and enforced through middleware annotations/interceptors; behavior must not rely on per-service ad-hoc interpretation.
- **Gameplay session ownership** – Game Session owns gameplay binding records, the binding CAS, and bounded secondary indexes that map gameplay bindings by uniqueness key, account/tenant scope, and tenant scope so takeover, reconnect, and revocation do not require scans. Account owns the admission decision and exact-binding lease only; Account finalization commits lease/decision evidence and never creates, deletes, or mutates Game Session bindings or indexes. Orphan evidence from Account reconciliation becomes a durable cleanup request that Gateway and Game Session consume under their respective edge and gameplay fences.
- **Gameplay admission semantics** – `LOGIN` authenticates account identity, while `PLAY` binds gameplay identity and gameplay scope. These must remain distinct concepts even when a client UX makes them feel nearly back-to-back.
- **Ordinary gameplay authentication** – Each gameplay `LOGIN` uses one account-selected mode, either `PASSWORD` or verified `EMAIL_OTP`. Account configuration may expose both choices separately, but no combined password-plus-email-OTP request is part of the wire contract. Gameplay authentication does not perform active-gameplay reauthentication or elevate the gameplay session into account/control-plane authority; HTTPS step-up remains separate.
- **Ingress identity validation** – Public and cross-service readers validate the declared shape of UUID-governed identifiers before authorization or lookup, then treat the values as opaque. Identifier contents never confer authority or determine tenant scope.

## Responsibility Split

- **Account Service** – Verifies login secrets according to account-selected password/email-code modes and owns Account-side authentication authority. JWT profiles, registry, generation, signer, and JWKS rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md).
- **Game Session Service** – Fronts the `LOGIN` command, stores local gameplay session context in Redis, and applies the `LOGIN`/`JOIN`/`PLAY` binding flow defined below. Session continuity and reconnect lifecycle are canonical in [Session Behavior](./system-architecture-session-behavior.md).
- **Spring Cloud Gateway** – Passes gameplay login and control-plane requests through without validating ordinary control-plane JWTs. `/ws/game/**` edge admission, connect-token replay, route lifecycle, and signed context carriage are owned by [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake).

[ADR 0022](./decisions/adr-0022-account-authority-and-gameplay-session-ownership.md) is the authority for this ownership split. Current implementation gaps in authority-generation enforcement, monotonic membership versions, or gameplay token storage are consolidated in [Implementation Status](#implementation-status) and do not transfer authority to another service.

### Client Classes and Token Carriage

- Telnet and other non-WebSocket text clients use the current credential-bearing forms recorded in [Implementation Status](#implementation-status). They do not receive or transmit `control-ui`, `player-bootstrap`, private delegation, or gameplay-connect JWTs. Any richer multi-line prompt flow is target-only and is not part of the current client capability contract.
- First-party browser and mobile-browser clients use `/auth/player-bootstrap` for bootstrap/discovery HTTP calls and receive the gameplay-connect credential only as the HttpOnly `Firemud-Connect-Token` cookie. First-party native-mobile and other first-party non-browser clients using a cookie jar remain cookie-only. The supported `first_party_web` route is cookie-only. Public non-browser issuance and the dedicated `non_first_party_public` header remain target-only; current availability is recorded in [Implementation Status](#implementation-status). A header-only request on an unsupported or unregistered route is `CONNECT_TOKEN_REJECTED`, not `CONNECT_TOKEN_MISSING`. Carrier and replay details are owned by [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake).
- After Gateway validates and consumes the gameplay-connect credential, public non-proxy WebSocket clients use bare `LOGIN` followed by `PLAY`; no transport sends an end-user JWT as gameplay command authorization.

Current login modes and TOTP availability are recorded in [Implementation Status](#implementation-status). The REST and gRPC authentication contracts do not carry a separate `otp` field. Public player-facing text clients use Telnet-over-TLS, while plaintext Telnet is limited to local, test, and explicitly private-network compatibility. TOTP is not a transport gate or a substitute for channel protection; [ADR 0033](./decisions/adr-0033-public-player-facing-telnet-requires-tls.md) owns that boundary.

### Ordinary Login and Sensitive-Action Step-Up

- Ordinary Telnet, gameplay bootstrap, and account/control login use one account-selected mode per attempt: `PASSWORD` or verified `EMAIL_OTP`. Account configuration may expose both choices separately, but the wire contract does not combine the password and email code in one request. Gameplay never solicits TOTP or repeats account authentication per command, and a gameplay session cannot become elevated control-plane authority.
- Routine gameplay and ordinary tenant-scoped creator or moderation work rely on their existing authenticated session, tenant capabilities, route policy, and audit. They do not trigger an unexpected factor prompt.
- Account email/password/factor changes, external-identity changes, account deletion, new real-money charges, payment-instrument management, billing-owner transfer, and global administration complete only through the HTTPS account/control plane. The client may be web, native, or CLI; raw Telnet cannot complete them.
- Every HTTPS-sensitive action listed above, including global administration, requires recent ordinary reauthentication. **Target-only privileged elevation:** entering a bounded `platformAdmin` or cross-tenant `billingAdmin` elevated window additionally requires an independently enrolled TOTP. Account records the resulting role-scoped elevation as bounded server-side state tied to the current `control-ui` token and account authority generation; it is not a reusable JWT profile or gameplay authority. That factor is supplied once per elevated window rather than once per action and never appears in gameplay. Current availability of this elevation window is recorded in [Implementation Status](#implementation-status).
- Gameplay may explicitly initiate a sensitive commercial or account action and receive a short-lived, single-use opaque HTTPS handoff URL. The handle grants no authority by itself and resolves to server-side intent bound to account, gameplay session, tenant where applicable, exact action, product and immutable amount/currency where applicable, and `requestId`. The HTTPS client independently authenticates, performs required step-up/provider work, and reports a verified idempotent outcome that gameplay may observe asynchronously.
- Spending an existing non-withdrawable premium balance remains gameplay. It requires exact purchase confirmation, idempotent identity, audit, and applicable caps, but no general account reauthentication. Withdrawal, cash redemption, or cash-equivalent transfer requires a new decision.

[ADR 0045](./decisions/adr-0045-ordinary-login-factors-and-https-sensitive-action-step-up.md) records this factor and protocol boundary.

Issued JWTs, registry records, authority generations, and token-profile validation rules are defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). This document defines their application to `LOGIN`, `JOIN`, `PLAY`, and the receiving gameplay context.

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
  - `platformAdmin` – Global role eligible only for the cross-tenant and platform-control route classes declared by the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md); each route applies its own target scope, assurance, generation, and `privileged_control` checks. It does not create membership or grant gameplay authority.
  - `support` – Limited cross-tenant support tools, subject to audit. Support roles may view high-level subscription state and entitlements for troubleshooting (for example, whether a tenant is `active` or `suspended` and what quotas apply), but cannot view detailed billing artifacts such as invoices or payment methods and cannot modify subscriptions.
  - `billingAdmin` – Global role eligible only for the cross-tenant billing-safe route classes declared by the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md); each route applies its own scope, generation, assurance, and required `privileged_control` checks. It does not create membership or grant gameplay or design authority.
- **Tenant roles (`scopedRoles[tenantId]`)**
  - `player` – Can join gameplay for the tenant subject to entitlements and quotas; no design, admin, or billing capabilities.
  - `designer` – Can edit design-time content for the tenant via Game Design tools; cannot control runtime instances or billing.
  - `tenantAdmin` – Owns the tenant in day-to-day operations: can manage game instances, configure runtime settings, and manage subscriptions and billing for that tenant.
  - `moderator` – Performs tenant-scoped moderation actions (for example, muting or banning players) but cannot alter billing or platform-wide configuration.

Services must not introduce ad-hoc roles without updating this model and the Tenant Authorization Contract. Where finer-grained behavior is required, services should prefer additional capabilities/flags derived from these core roles rather than inventing new top-level roles.

The tenant-role model is an accepted target decision, and tenantless control login with navigation-only tenant selection and reauthorization on each selected tenant is an accepted target decision under [ADR 0040](./decisions/adr-0040-account-global-control-login-and-explicit-tenant-selection.md). These decisions do not claim complete implementation or proof: tenant-scoped role persistence and `scopedRoles` issuance, switching controls, and the associated end-to-end evidence remain tracked gaps in the [Player Access and Session implementation tracker](../project-management/implementation-tracking/player-access-and-session.md#capability-status), with focused evidence and missing proof listed under [Validation and Proof](../project-management/implementation-tracking/player-access-and-session.md#validation-and-proof).

### Global Role to Route-Class Matrix (Normative)

The canonical global-role-to-route-class mapping is [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#classification-rules). Global roles must not be interpreted as broad tenant or gameplay shortcuts.

Authentication-local gameplay consequence: global roles never grant gameplay admission or switching. Character access, character creation, connect-token issuance, and `PLAY` require caller-bound `ACTIVE` membership plus any applicable realm grant; only explicit public-production `JOIN`/`Join & Play` may create or restore membership. Current implementation drift is recorded in Implementation Status.

Role assurance is route-specific and follows the [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#privileged-role-assurance); a role name alone never supplies elevation.

Global roles also confer no authority after ordinary gameplay admission. Gameplay presence, commands, actor capabilities, and `PlayerExecutionContext` must ignore `globalRoles`; only explicit tenant-scoped gameplay grants may produce moderator, administrator, game-master, or equivalent in-world capabilities. A `platformAdmin`, `support`, or `billingAdmin` account that joins a public game without such a tenant-scoped grant appears and acts as an ordinary `PLAYER`. Break-glass platform operations remain separate audited control-plane actions and must not create a player actor or gameplay session.

The current target has no support impersonation, live-session attachment, or hidden-observer mode. Support uses minimized support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations. Adding any impersonation or observation product requires a new human-reviewed privacy, tenant-consent, notification, audit, and capability decision; implementations must not preserve speculative bypass hooks.

`account_scoped` routes are authorized by authenticated account context and explicit subject-binding rules. A `platformAdmin` override is not inherited from the route class: it is valid only when the exact route-matrix entry declares `platform_admin_override: platformAdmin_only`, and it additionally requires a valid server-side `privileged_control` window backed by independent TOTP.

### Tenant Authorization Contract

All meta/control services (Account, Game Design, Logging & Admin, and similar HTTP/gRPC APIs) must enforce a consistent tenant-authorization contract:

- Each incoming request uses the exact auth path for its route: normal account-bearing requests authenticate a single `accountId` with a JWT validated against the Account Service JWKS, while `pending_deletion_scoped` routes use only an opaque Account-owned pending-deletion credential validated against its server-side workflow registry.
- The effective tenant set for the request is derived from the token:
  - For tenant-scoped operations, the service computes the set of `tenantId` values from `scopedRoles` plus explicit global-role allowances from the route-class matrix above. Gameplay lobby/admission routes are stricter: they must derive authority from caller-bound tenant membership and `gameplayAdmissionAllowed`, not from global-role shortcuts. Billing-related global access must use explicitly cross-tenant billing-safe route variants.
  - For cross-tenant operations, the service must explicitly check the caller's `globalRole` against the exact route-matrix entry for the specific API category, including any route-specific scope, assurance, generation, and `privileged_control` requirements (for example, only `platformAdmin` for gameplay- or data-bearing operations, `billingAdmin` or `platformAdmin` for billing-safe control-plane operations, and `support` or `platformAdmin` only for explicitly designated support-safe troubleshooting surfaces). Tenant-scoped roles must never implicitly grant cross-tenant privileges.
- For account-scoped operations, authorization must bind to authenticated `accountId` and route-level subject-binding rules, without deriving or requiring tenant scope.
- If an API accepts a `tenantId` (path, query parameter, or body field), the service must validate that:
  - `tenantId` is in the effective tenant set for tenant-scoped calls, or
  - The caller holds a cross-tenant `globalRole` that explicitly allows operating on the requested tenant.
- Services must apply the `tenantId` filter to all read and write queries, even when the client does not explicitly supply a `tenantId` (for example, when inferring tenant from a game instance).

A shared library helper (for example, a `TenantAccessGuard` used by `AuthTokenInterceptor`) should be used by all meta/control services so this contract is implemented in one place and kept in sync with future role/tenant model changes.

### Authentication Operation Paths (Normative)

Authentication is partitioned into four explicit paths; no path may substitute another path's authority:

- **JWT issued-token registry path** – JWT-presenting control-plane and bootstrap operations use the exact profile, registry, generation, and outage rules in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Authentication applies the resulting decision to bootstrap and admission routes.
- **Pending-deletion workflow registry path** – `pending_deletion_scoped` routes use only the Account-issued opaque `pending-deletion-access` credential and its separate workflow registry. The validator binds the credential to the account and deletion workflow and checks live workflow state; it uses no JWT issuer/account/tenant/membership generations and never falls back to normal JWT or gameplay authority.
- **In-band gameplay bound-session path** – Non-JWT `LOGIN` establishes the authenticated Game Session socket/session context, and non-JWT `PLAY`, fresh admission, reconnect, resume, and rebind use the exact bound-session identity, binding fence, current Account membership/lifecycle/revocation authority, admission/resume lease, routing/ownership evidence, and CAS contract required by the gameplay path. The trusted TCP Proxy bridge is a separate positive exact-mTLS branch of this gameplay path and does not acquire a JWT or connect-token registry check. Routine commands use the admitted binding, typed workload context, and bounded reconciliation rather than JWT registry middleware.
- **Gameplay-connect replay-fence path** – The one-use edge credential, carrier, replay fence, quarantine, deny marker, atomic consume, and signed context are owned by [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake). It is separate from the ordinary JWT registry; Authentication consumes the verified context during the `LOGIN`/`PLAY` flow.

### Auth Middleware Application (Normative)

Any HTTP/gRPC route that depends on identity, roles, or tenant scoping must use the shared authentication path declared for its route class. External admin/creator APIs use the shared JWT middleware where their registered profile requires it (for example, `AuthTokenInterceptor` plus a `TenantAccessGuard`). Internal infrastructure-management Gateway gRPC is an explicit exception: it uses the separate mTLS management-plane contract in [Gateway Network Surfaces](./system-architecture-gateway.md#gateway-network-surfaces) and must not be forced through external JWT middleware. The route matrix, JWT registry/generation rules, internal workload trust predicates, and Gateway replay path are canonical in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md), [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md), [Security](./system-architecture-security.md#gameplay-workload-trust), and [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake). This document retains the dispatch consequence: registry-backed JWT routes use the JWT path; public, infrastructure-management, trusted TCP Proxy, non-JWT `LOGIN`/`PLAY`, routine gameplay, reconnect/resume, and `gameplay-connect` use their declared paths and must not be forced through a synthetic JWT registry contract.

1. **Validate the declared credential** – Registry-backed JWT routes use the exact profile and JWKS/registry predicates in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). `gameplay-connect` uses Gateway's replay-fence path; public, trusted TCP Proxy, and bound-session gameplay paths use their declared non-JWT checks.
2. **Apply the registry path where declared** – A registry-backed JWT route uses the matching Account-owned record and current authority evidence. Missing or reachable invalid evidence is the owner-defined revoked/invalid result; unavailable evidence is `AUTH_UNAVAILABLE`. `LOGIN`/`PLAY`, trusted TCP Proxy, routine gameplay, and `gameplay-connect` do not consult the ordinary registry unless their declared contract says so.
3. **Apply current authority for the declared path** – Registry-backed JWT routes use the applicable Account-owned tuple and route-class predicates. `LOGIN`, `JOIN`, `PLAY`, reconnect, and resume use the bound-session admission checks below; routine gameplay uses the admitted typed context and bounded reconciliation rather than a per-command JWT registry lookup.
   - Exact tuple fields, generations, route-class exceptions, and their unavailable-versus-revoked outcomes are owned by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) and [Authorization Route Matrix](./system-architecture-authz-route-matrix.md).
4. **Apply route classification** – Every protected route uses the exact registered class, authentication path, and role predicates in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#classification-rules). Unclassified protected routes fail closed; Authentication only applies the resulting route decision to its local API or gameplay admission surface.

- **Unavailable versus revoked** – The canonical availability-versus-denial classification is defined by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Authentication preserves the local user-visible consequence: `AUTH_UNAVAILABLE` is retryable without discarding in-memory auth state, while reachable invalid or revoked evidence requires the applicable reauthentication or denial response; cached authority never authorizes the failed operation.

Protected-route inventory and default-deny behavior are owned by [Authorization Route Matrix](./system-architecture-authz-route-matrix.md#governance-required). Authentication must reject a protected route whose class is not deterministically known and must not approximate it as `tenant_regular` or another executable class.

Billing-safe mutation membership contract (normative):

- Billing-safe tenant mutations must perform an authoritative, live membership/role check via Account Service API (`GetCallerTenantMembership(tenantId)` or protocol-equivalent) before mutation.
- JWT role claims are sufficient for routing and preliminary checks but are not sufficient alone for billing-safe mutations.
- If membership authority is unavailable, billing-safe mutations fail closed with canonical error `AUTH_UNAVAILABLE`; read-only billing-safe surfaces may return a retriable unavailable response using the same code.
- Immediate caller-bound revocation for tenant membership/role changes is enforced by advancing the `{accountId, tenantId}` membership authority generation and projecting `session:auth:generation:membership:<accountId>:<tenantId>` in addition to the live membership check; implementers must not rely on JWT expiry alone.
- Tenant-scoped membership checks use `GetCallerTenantMembership(tenantId)` and must bind the subject to the authenticated caller (`accountId` from token); clients must not provide an arbitrary target `accountId` on this path.
- Global billing roles (`billingAdmin`/`platformAdmin`) must use explicitly cross-tenant billing-safe route variants and must not rely on caller-bound tenant membership endpoints intended for `billing_safe_tenant`.
- Cross-tenant membership checks for billing/reporting use a separate admin API (`GetTenantMembershipForAccount(tenantId, accountId)` or equivalent) restricted to `billingAdmin`/`platformAdmin`.
- Membership responses must include explicit `membershipExists`, `membershipAuthorityGeneration`, `gameplayAdmissionAllowed`, `evaluatedAt`, `membershipVersion`, the complete applicable `authorityTuple`, and the matching `outboxCheckpoints[]` entry for `account:auth-authority:v1:membership/<accountId>/<tenantId>`. Account must produce or authenticate these fields, the caller-bound account/tenant identity, and the checkpoint from one authoritative snapshot/transaction; `evaluatedAt` describes the freshness of that complete response, not a separately read field. Callers must reject a response that is missing any field, has a checkpoint whose `outboxSequence` does not cover the tuple and membership version, combines values from different checkpoints, or cannot prove this atomic freshness.

**5. Entitlement gating** – For gameplay admission and non-billing-safe operational control-plane routes (instance start/stop, gameplay-affecting changes), services must consult the internal runtime entitlement surface (`GetTenantEntitlementsForRuntime(tenantId, requestId)` or protocol-equivalent) and enforce its operation-specific flags as well as quotas. Entitlement reads assess eligibility only; they never reserve or commit capacity. Capacity-changing operations must use Account's `CommitTenantCapacityAdmission` when implemented and fail closed until that commitment route exists. Every fresh gameplay admission requires applicable entitlement authority: public production requires a fresh positive entitlement result, while private/playtest additionally requires existing caller-bound membership and the exact current Account-owned realm grant and never consumes the public-production enrollment predicate. `TENANT_BILLING_BLOCKED` applies only to the denied admission, capacity, or mutation operation; it is not a blanket tenant gameplay shutdown. `past_due` remains playable under ordinary quotas; `grace` preserves connected sessions and eligible same-session resume but denies first-time public join, first/new gameplay bindings, new instances, scale-out, and quota growth; `suspended`/`canceled` denies new public join and gameplay admission, with any active-session revocation governed by the sequenced authority contract. Billing-safe and support-safe routes must not be blocked solely due to tenant unavailability for gameplay.

**6. Entitlement freshness and continuity SLA** – A snapshot is fresh for 15 seconds from its authoritative `evaluatedAt`. Explicit public join, first/new gameplay binding, new instance/scale, quota increase, paid-feature activation, and capacity-creating cutover require a fresh snapshot; a fresh authoritative denial for that requested operation returns `TENANT_BILLING_BLOCKED`, while inability to establish fresh entitlement authority returns `ENTITLEMENT_UNAVAILABLE`. The only last-known-good exception is unchanged public-production binding continuity during an entitlement-only outage, after current membership, routing, public-policy, and other resume predicates pass; it may not authorize fresh admission, private/playtest admission, `JOIN`, membership creation/restoration, or an expanded binding. `ENTITLEMENT_UNAVAILABLE` takes precedence when entitlement authority cannot be established; `AUTH_UNAVAILABLE` remains the non-entitlement authority-outage result.

- Entitlement snapshots must carry operation flags for public join, new gameplay binding, and new instance/scale authority plus `evaluatedAt`, `entitlementVersion`, and `tenantBillingSequence`.
- The unchanged public-production continuity exception is forbidden after observed `suspended`/`canceled`, revocation, explicit denial, a newer billing sequence, a sequence gap, or when no prior positive snapshot exists. Five minutes is a platform hard maximum; operators may only shorten or disable it. It does not relax revocation-authority freshness: if the separate batched revocation reconciliation lease cannot be renewed, active authority terminates at its stricter 60-second bound.
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

The target direct-text player-facing flow is intentionally simple. Public discovery precedes credential entry; authenticated discovery then supplies the target used by the conditional membership and character gates:

```text
   WORLDS
LOGIN <email> [secret]
[LOGIN <email> <code>]  # required after one-argument LOGIN requests a code
REALMS <world>
[JOIN <world>]  # only for missing or INACTIVE public-production membership
[CHARS <world> [realm]]  # after membership is ACTIVE, when a valid selected character is not already resolved
[character creation]  # only when allowed and no valid character exists
PLAY <world> [realm] [character]
```

The sequence above is the target flow. Current-versus-target deviations are recorded once in [Implementation Status](#implementation-status); target-flow sections do not redefine current runtime behavior.

`<world>` is either an index from the caller's exact `WORLDS` browse snapshot or the stable `tenantSlug/worldSlug` selector carried by that response. A bare `tenantSlug` is accepted only when the tenant exposes exactly one visible authored world; a bare tenant-scoped `worldSlug` is never resolved globally. `[realm]` is a `realmSlug` under the resolved world or an index from the corresponding `REALMS` snapshot. Menu indices are response-local conveniences and are never stored or forwarded as durable identity. `REALMS` is authenticated discovery after `LOGIN`; `JOIN` is conditional on the selected public-production membership state; `CHARS` or allowed character creation follows only after membership is `ACTIVE`.

Before login, `HELP` is available only as non-discovery help, while `WORLDS` is the sole anonymous discovery wire command, internally classified as `WORLDS_PUBLIC`, and exposes bounded public-production catalog/availability metadata. `REALMS` and `CHARS` are authenticated post-login discovery commands; they must not be exposed as anonymous pre-login discovery surfaces. After login, authenticated `WORLDS`, `REALMS`, and `CHARS` may provide caller-bound membership/grant-aware discovery.

`WORLDS` deliberately has two internal route classifications rather than one replacing the other:

- Before `LOGIN`, the `WORLDS` wire command uses the `WORLDS_PUBLIC` public browse-only route classification. It may expose only the bounded public-production catalog and availability metadata; it has no account identity, membership filtering, hidden-tenant disclosure, or gameplay authority.
- After `LOGIN`, `WORLDS_AUTHENTICATED` is authenticated pre-tenant discovery. Game Session derives the account from its authenticated gameplay context and combines current Account-owned membership/grant visibility with public-production visibility and entitlement filtering before any single tenant is selected. It may return more than the public catalog for that account, but it does not itself bind a tenant, create membership, mint a connect token, or authorize `PLAY`.

These modes are complementary: public browse remains available before authentication, while authenticated discovery remains membership-aware after authentication.

`REALMS <world>` selects one mutually exclusive policy branch. Only the public-production branch uses `public_production_onboarding`; private/playtest discovery is `caller_membership_scoped` and is governed by the membership/grant checks below, not by public onboarding. The resolved world/tenant is an input to the checks, not a second route class:

- Before membership exists, the world selector must resolve to exactly one caller-visible `{tenantId, worldSlug}`. The response may include the tenant's one catalog-designated `publicProduction=true` realm when the current catalog/pointer pair is valid and the realm is publicly visible; no membership or realm grant is required for that public-production discovery, and global roles do not widen it.
- After the selector resolves to a canonical tenant, the same class performs exact tenant-scoped checks against that tenant's current catalog/pointer pair. The public-production realm still permits discovery without membership when its public visibility and entitlement checks pass. Every non-public realm requires both an existing caller-bound membership with exact `membershipLifecycleState=ACTIVE` for that tenant and the current Account-owned realm-access grant for `{accountId, tenantId, worldSlug, realmSlug}`; a grant never substitutes for membership. Hidden or unauthorized realms are omitted rather than disclosed.
- Both stages require the server-resolved tenant/world identity, current realm visibility, runtime entitlement evaluation, and the shared catalog/pointer reference. For authenticated text routing, an unreachable or timed-out Account or routing authority is `AUTH_UNAVAILABLE`: preserve the authenticated state, create no binding, and do not use cached pointer/selector data as a fallback. Reachable missing, malformed, ambiguous, stale, or contract-invalid pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`; a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair is `CONNECT_SCOPE_MISMATCH`; a complete `CLOSED` pointer may be shown as unavailable and is `REALM_UNAVAILABLE` for admission. `REALMS` never creates membership, grants gameplay authority, or binds a runtime target.

### Direct-text REALMS-to-JOIN scope (normative)

- During authenticated `REALMS` resolution, Account Service issues an opaque, short-lived `connectScopeId` for the selected realm. It is bound to the authenticated caller and the exact server-resolved `{tenantId, worldSlug, realmSlug, gameInstanceId, catalogRevision, pointerVersion}` snapshot, including its expiry; it is not a client-selected target or a durable realm handle. The same snapshot is the only source from which Game Session derives `playableStateScope` for `CHARS`; that policy projection is never supplied as join input.
- Game Session obtains that scope and retains it as transport-local session state alongside the response-local `REALMS` selector or index. The text client receives ordinary selectors and display data only; it never receives or supplies authority IDs, a scope, a tenant, a runtime target, a catalog revision, or a pointer version.
- When the client selects `JOIN`, Game Session calls Account's `JoinPublicProductionMembership` with only trusted caller context, the retained `connectScopeId`, and a server-generated high-entropy `requestId`. Game Session does not accept a client-supplied request ID, `playableStateScope`, storage key, or pass selector/target fields as authority. The `requestId` identifies the logical join attempt and is reused only for its retries; a changed attempt uses a new value.
- Account validates the caller binding, scope validity, expiry, and exact bound routing snapshot before applying the join operation. A missing, expired, or mismatched retained scope fails closed and requires fresh authenticated `REALMS` discovery. An unavailable authority dependency returns `AUTH_UNAVAILABLE` and does not permit selector fallback; reachable invalid or contradictory scope evidence returns the applicable scope failure. The server must not re-resolve a stale selector or accept client-supplied target fields as fallback.

Normative semantic split:

- `LOGIN` proves or restores account identity.
- `LOGIN` establishes the authenticated socket/session account context only; it does not select a tenant or character and does not create a gameplay binding. Gameplay admission, binding, and finalization belong to `PLAY`.
- When the selected public-production tenant has missing or `INACTIVE` caller-bound membership, `JOIN <world>` explicitly establishes `ACTIVE` membership after authenticated `REALMS` discovery and before `CHARS` or character creation. Either create or restore advances `membershipVersion`; `membershipAuthorityGeneration` advances independently only when `callerBoundAuthorityInvalidated=true`. An `ACTIVE` membership returns the exact idempotent current snapshot. Any other lifecycle state rejects. A caller with existing `ACTIVE` membership omits `JOIN` and continues to character selection and `PLAY`. First-party browser/mobile clients use the equivalent Account bootstrap join endpoint.
- The direct-text `LOGIN` plus `PLAY` compatibility shortcut is limited to an existing `membershipLifecycleState=ACTIVE` member for the same target. It may omit `JOIN` only because membership is already active, and it may omit a separate `CHARS` round-trip only when fresh transport-local resolution supplies one valid current character; it never reuses a first-party WebSocket discovery snapshot. First-party/browser clients always use authenticated bootstrap, discovery, and lobby gates, including for returning members.
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

Current implementation and target-only endpoint gaps are consolidated in [Implementation Status](#implementation-status). The local consequence is that multi-line prompt exchange is not part of the current text-client capability, while first-party browser/mobile `LOGIN` consumes the validated bootstrap/connect context without credential replay.

Telnet-specific smart-client attach hints, if they return later, should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line. Those hints remain advisory transport metadata only, are not authentication material, and never bypass the canonical `LOGIN` + `PLAY` authorization and entitlement checks. The TCP Proxy Service and Spring Cloud Gateway docs describe only their **transport responsibilities** and defer to this section for `LOGIN`/`LOGON` semantics and example transcripts.

Any future hidden attach hints may include a target `{gameInstanceId, tenantId}` for advanced clients, but the canonical source of gameplay target selection remains the authenticated lobby/admission flow. Clients must not rely on unauthenticated transport hints to bypass membership, entitlement, or world-visibility checks.

Admission-routing convergence rule:

- `REALMS`, `CHARS`, `PLAY`, bootstrap discovery, `POST /auth/connect-token`, and reconnect validation must all consume the same authoritative realm-catalog and `GetAdmissionPointer(tenantId, worldSlug, realmSlug)` contract described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract).
- Those surfaces may expose different projections of the same routing truth, but they must not maintain separate interpretation rules for which realm maps to which admissible `gameInstanceId`.
- If reachable pointer evidence is missing, malformed, ambiguous, stale, or contract-invalid, the flow fails closed with `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out authority is `AUTH_UNAVAILABLE`; a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair is `CONNECT_SCOPE_MISMATCH`; and a complete `CLOSED` pointer is `REALM_UNAVAILABLE` rather than an incomplete pointer. The flow must never silently rebind the player to a different runtime target.

### WebSocket Connect Token Contract (`/ws/game/**`)

The canonical `gameplay-connect` profile, edge carrier, replay fence, handshake consumption, and Gateway-to-Game Session context are owned by [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake). JWT profile, registry, generation, outage, and Account-signing rules are owned by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). The post-handshake Gateway context is a separate non-JWT signed envelope with a dedicated Gateway key namespace and verification-key publication contract; it must not reuse or imply Account JWT signing authority.

Authentication-local consequences are limited to bootstrap and admission sequencing:

- Account exposes `POST /auth/player-bootstrap` as the first-party gameplay login/issuance surface. The resulting tenant-free `player-bootstrap` token is then accepted by `/auth/bootstrap/*` discovery and action facades, including join and character operations; `/auth/connect-token` is the separate one-use gameplay-token issuance surface. Connect-token issuance derives the caller from that bootstrap identity and accepts the server-issued `connectScopeId`, not arbitrary client-supplied tenant or runtime identifiers.
- Connect-token issuance requires one fresh caller-bound Account membership snapshot, fresh entitlement, the applicable fresh realm-grant read for private/playtest targets, and admission-pointer checks for the selected target. It never creates or restores membership; explicit public-production `JOIN`/Join & Play remains the only membership-writing path. A realm grant never substitutes for the membership snapshot.
- First-party browser, mobile-browser, and first-party native-mobile clients using a protected cookie jar use the `Firemud-Connect-Token` cookie through the supported `first_party_web` route. The dedicated `non_first_party_public` header remains target-only; its current availability is recorded in [Implementation Status](#implementation-status). A header-only request on an unsupported or unregistered route is `CONNECT_TOKEN_REJECTED`, not `CONNECT_TOKEN_MISSING`, and it is not a fallback for the cookie route. Carrier validation, replay, and close/error mapping remain Gateway-owned.
- Game Session verifies the signed context before bare WebSocket `LOGIN`, binds its account and selected scope to `PLAY`, and rejects a mismatch before gameplay binding. The context has explicit `audience: game-session` and `recipient: game-session-service` bindings for the registered Game Session receiver, using the exact receiver/audience predicates owned by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md); it must not use a broad or generic internal audience.

#### Gateway-to-Game Session connect context (normative)

Gateway strips the external carrier and attaches one signed, short-lived non-JWT context. Gateway owns issuance, replay, its dedicated asymmetric signing-key namespace, verification-key publication and rotation, and carrier policy as defined by [Gateway architecture](./system-architecture-gateway.md#tenant-aware-edge-connect-token-gameplay-handshake). Game Session owns receiving-service validation, including explicit audience/recipient binding, before applying gameplay admission. A JWS serialization does not turn this envelope into an Account-issued JWT profile or permit reuse of Account signing keys.

Minimum context fields are `accountId`, `tenantId`, `gameInstanceId`, `worldSlug`, `realmSlug`, `pointerVersion`, `catalogRevision`, `connectScopeId`, `requestId`, `audience: game-session`, `recipient: game-session-service`, the exact bounded selected-target `authorityTuple`, separate exact `membershipVersion`, `connectTokenJti`, `replayAdmissionFence`, `verifiedAt`, `expiresAt`, and `gatewayRequestId`.

- Game Session validates the asymmetric signature and `kid` against Gateway verification keys, validates the exact audience/recipient binding, enforces the bounded expiry, and rejects missing, invalid, wrong-recipient, or expired context with `CONNECT_CONTEXT_INVALID` before `PLAY`.
- Game Session does not implement a second replay authority for `connectTokenJti`; it treats that value as access-controlled correlation and must not emit it as a metric label, ordinary log field, or trace attribute.
- `CONNECT_SCOPE_MISMATCH` is computed from this verified context and server-resolved scope, never from raw `X-Tenant-Id` or `X-Game-Instance-Id` headers.
- Verification-key cache refresh and bounded failure reasons remain the Gateway/Game Session operational contract; unknown or unavailable keys fail closed.

#### First-party WebSocket admission sequence (normative)

`public_production_onboarding` and `gameplay_admission` are separate route classifications. `public_production_onboarding` covers discovery of the default public-production realm and explicit `JOIN`/`Join & Play`; caller-bound bootstrap reads/writes and connect-token cookie revocation retain their existing route-matrix classifications and do not broaden public onboarding. `gameplay_admission` covers the authenticated gameplay admission decision: the `/ws/game/**` transport handshake and in-band `PLAY`, including the mode-specific membership, grant, entitlement, routing, and binding checks. A trusted TCP Proxy transport is gameplay admission, not public onboarding.

To remove ambiguity between connect-token admission and `LOGIN`, first-party web/mobile gameplay clients must follow this sequence:

1. Call the dedicated first-party player bootstrap endpoint (for example `POST /auth/player-bootstrap`) and establish a short-lived player bootstrap identity.
2. Use bootstrap-authenticated discovery endpoints to select a caller-visible world and realm target.
3. If the public-production target is visible but the account's membership is missing or `INACTIVE`, explicitly call `POST /auth/bootstrap/join`. Character discovery and creation require the resulting `ACTIVE` membership; a returning `ACTIVE` member skips this step.
4. Select or create a caller-visible character, then request a short-lived gameplay connect token for the target selected by `connectScopeId`. This call performs one fresh caller-bound membership snapshot, including independent current `membershipAuthorityGeneration` and `membershipVersion`, plus the applicable fresh realm-grant, runtime-entitlement, and binding checks.
   - The issuance path must also validate the target against the authoritative realm-routing record. If the target is no longer admissible for the selected realm, the request fails before socket open rather than issuing a stale token.
5. Open gameplay WebSocket on `/ws/game/**` with the `Firemud-Connect-Token` HttpOnly cookie set by `POST /auth/connect-token`; first-party native-mobile clients using a cookie jar remain cookie-only. The target-only non-first-party/public non-browser route may use secure storage plus the dedicated header only after that route is fully registered and proven; its current availability is recorded in [Implementation Status](#implementation-status).
6. Complete gameplay authentication in-band using `LOGIN` (or `LOGON`) and then lobby binding with `PLAY`.

Normative constraints:

- First-party clients must not treat successful handshake as gameplay authentication; gameplay remains unauthenticated until `LOGIN` succeeds.
- `/ws/game/**` requires a valid connect token for non-proxy clients and rejects missing tokens with `403`.
- Connect-token-backed admission has an independent downstream authorization gate: Game Session must re-read existing caller-bound membership, require explicit prior join for public production, and confirm public-production admission or applicable realm visibility/grant and current realm routing. A valid connect token alone cannot admit gameplay or create membership.
- For first-party `/ws/game/**` clients, bare `LOGIN` (or `LOGON`) must complete gameplay authentication by consuming the verified connect context plus the bootstrap identity already bound to that context. Browsers must not be required to replay credentials after bootstrap.
- Telnet and other non-WebSocket text transports use the credential-bearing login forms recorded in [Implementation Status](#implementation-status). Any richer multi-line prompt flow is target-only and is not part of the current client capability contract. Public non-browser WebSocket issuance and header availability are also recorded in [Implementation Status](#implementation-status); the classified bootstrap/connect-token header path remains target-only until its dedicated route is registered and proven.
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
-> [{ characterName: "Mara" }]  # abbreviated character list; the realm snapshot above carries the admission fields

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_production_v17", requestId: "req-123" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "production", gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01", expiresAt, issuedAt }

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
-> [{ worldSlug: "emberfall", displayName: "Emberfall" }]  # abbreviated world list; full realm snapshot follows

GET /auth/bootstrap/worlds/emberfall/realms
Authorization: Bearer <bootstrapToken>
-> [{
     worldSlug: "emberfall",
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40",
     gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce",
     connectScopeId: "cs_emberfall_production_v1",
     pointerVersion: 3,
     catalogRevision: 12,
     evaluatedAt: "2026-08-02T00:00:00Z",
     connectScopeExpiresAt: "2026-08-02T00:00:30Z"
   }]

POST /auth/bootstrap/join
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v1", requestId: "req-join-1" }
-> { tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", membershipExists: true, membershipLifecycleState: "ACTIVE", roles: ["player"], gameplayAdmissionAllowed: true, membershipAuthorityGeneration: 8, membershipVersion: 1, authorityTuple: { issuerAuthGeneration: 7, accountAuthorityGeneration: 12, tenantAuthorityGeneration: { "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40": 4 }, membershipAuthorityGeneration: { "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40": 8 }, privateRealmGrantVersions: [] }, evaluatedAt: "2026-08-02T00:00:00Z", outboxCheckpoints: [{ outboxStreamKey: "account:auth-authority:v1:membership/<accountId>/<tenantId>", outboxSequence: 1 }], joined: true }

GET /auth/bootstrap/worlds/emberfall/realms
Authorization: Bearer <bootstrapToken>
-> [{
     worldSlug: "emberfall",
     realmSlug: "production",
     displayName: "Live Realm",
     tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40",
     gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce",
     connectScopeId: "cs_emberfall_production_v2",
     pointerVersion: 3,
     catalogRevision: 12,  # JOIN changes membership only; it does not mutate the catalog
     evaluatedAt: "2026-08-02T00:00:01Z",
     connectScopeExpiresAt: "2026-08-02T00:00:31Z"
   }]

GET /auth/bootstrap/worlds/emberfall/realms/production/characters?connectScopeId=cs_emberfall_production_v2
Authorization: Bearer <bootstrapToken>
-> []  # abbreviated character list; the post-join realm snapshot above carries the admission fields

POST /auth/bootstrap/worlds/emberfall/realms/production/characters
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v2", name: "Mara", template: "human-fighter" }
-> { characterName: "Mara", characterId: "c7f4b18b-6eb5-4fd8-a906-c9606d17d4dc" }

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_emberfall_production_v2", requestId: "req-connect-1" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "e14f2d0c-8b7a-4f26-9c51-6a3d7e8b2c40", realmSlug: "production", gameInstanceId: "7b63923a-43bd-45ab-8b39-80d95d74e2ce", expiresAt, issuedAt }

GET /ws/game/** with the Firemud-Connect-Token cookie set by the previous response
LOGIN
OK LOGIN Logged in
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
-> [{ worldSlug: "demo", displayName: "Demo World" }]  # abbreviated world list; full realm snapshot follows

GET /auth/bootstrap/worlds/demo/realms
Authorization: Bearer <bootstrapToken>
-> [
     {
       realmSlug: "production",
       displayName: "Live Realm",
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "2f1c7ad0-8d5a-4a61-9d4b-6c93f11a2e01",
       connectScopeId: "cs_demo_production_v17",
       pointerVersion: 17,
       catalogRevision: 42,
       evaluatedAt: "2026-08-02T00:00:00Z",
       connectScopeExpiresAt: "2026-08-02T00:00:30Z"
     },
     {
       realmSlug: "playtest-docks",
       displayName: "Playtest Fork",
       tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120",
       gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d",
       connectScopeId: "cs_demo_playtest_docks_v4",
       pointerVersion: 4,
       catalogRevision: 43,
       evaluatedAt: "2026-08-02T00:00:00Z",
       connectScopeExpiresAt: "2026-08-02T00:00:30Z"
     }
   ]

GET /auth/bootstrap/worlds/demo/realms/playtest-docks/characters?connectScopeId=cs_demo_playtest_docks_v4
Authorization: Bearer <bootstrapToken>
-> [{ characterName: "Mara" }]  # abbreviated character list; the realm snapshot above carries the admission fields

POST /auth/connect-token
Authorization: Bearer <bootstrapToken>
{ connectScopeId: "cs_demo_playtest_docks_v4", requestId: "req-456" }
Set-Cookie: Firemud-Connect-Token=<connectToken>; HttpOnly; Secure; SameSite=Strict; Path=/ws/game; Max-Age=30
-> { accountId, tenantId: "7b3b074e-d597-4e9b-b96f-4f5946d26120", realmSlug: "playtest-docks", gameInstanceId: "ad63c32f-b076-48de-9434-87fb16b73c1d", expiresAt, issuedAt }

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
   - `LOGIN <email>` (or `LOGON <email>`) to request a verified-email code, followed by `LOGIN <email> <code>` (or `LOGON <email> <code>`) to authenticate, or `LOGIN <email> <secret>` (or `LOGON <email> <secret>`) to authenticate immediately, for Telnet and other non-WebSocket text transports.
   - bare `LOGIN` / `LOGON` on any public non-proxy `/ws/game/**` connection after Gateway has validated a connect token and attached a signed connect context. The current supported first-party WebSocket path carries the token in the protected `Firemud-Connect-Token` cookie. A fully registered target-only non-browser WebSocket route may conditionally carry it in `X-Firemud-Connect-Token`; its current availability is recorded in [Implementation Status](#implementation-status), and it is not a fallback for the current cookie path. In either supported target case, the client is completing gameplay auth from the previously established bootstrap identity rather than sending credentials a second time.
2. For credential-bearing login, the Game Session Service parses the line, normalizes the email, and issues a synchronous call to the Account Service `Authenticate` gRPC method (internal-only, mTLS-protected) with `email`, one supplied `secret`, and a typed `CredentialSourceContext`. **Target-only Authenticate replay envelope (not current):** the call also carries a stable high-entropy `requestId`; Account binds it to an immutable, server-keyed and versioned digest of the normalized authentication operation, credential presentation, source context, and applicable scope. The dedicated Account-owned digest-key version remains available for at least the response-envelope lifetime; neither the raw secret nor an unkeyed reusable credential hash is persisted or logged. Account's durable operation state contains only token identity, authority snapshot, request digest, lifecycle state, and reconciliation metadata. When response-loss recovery must return the credential itself, Account retains the exact JWT/result only in a separate bounded Account-encrypted response envelope bound to that operation; retry with the same request ID and matching digest may read that envelope only after the issued-token registry record and current authority are reconciled. The raw compact JWT is not durable operation evidence. Reuse with a different digest or scope is rejected as an idempotency conflict and cannot issue another token. The context carries the server-derived canonical client address and transport class from the trusted Gateway or authenticated TCP Proxy chain; public input cannot populate or override it. Account rejects a missing, unknown, or untrusted source context in player-facing environments. Account Service interprets that secret against the account's enabled `PASSWORD` and `EMAIL_OTP` modes. Gameplay `LOGIN` must not call the public `/auth/login` browser endpoint; `/auth/login` is reserved for first-party control-plane UIs. A one-argument `LOGIN <email>` instead invokes the Account-owned neutral email-code challenge and does not authenticate the session by itself; `LOGIN <email> <secret>` is the canonical immediate email-login form, with `LOGON` as its alias.
3. For bootstrap-backed WebSocket login, Game Session validates the signed connect context, binds it to the bootstrap-authenticated account identity established before `/auth/connect-token`, and obtains/refreshes the backend token material needed for subsequent internal calls. This path must not prompt for or require replay of account credentials from the WebSocket client.
4. The Account Service-backed credential path validates the supplied secret according to the enabled account modes and returns account metadata plus the private `game-session-account-delegation` JWT profile with audience `account-service`, or a canonical error code such as `AUTH_INVALID_CREDENTIALS`, `AUTH_ACCOUNT_LOCKED`, or `AUTH_UNAVAILABLE`. This exact receiver-specific profile is the only Account token Game Session accepts from credential authentication; a generic backend JWT or another audience is invalid. The Game Session Service translates Account error codes into the text-protocol equivalents so WebSocket and Telnet clients always see the same response format regardless of upstream wording.
5. Success responses cause the Game Session Service to establish or refresh the authenticated socket/session account context only; they do not select a tenant or character and do not create or refresh the gameplay session binding. Current Account establishes or refreshes the required account-scoped legacy session record under `session:auth:account:<accountId>:<tokenHash>` and, for tenant-scoped sessions, the companion `session:auth:tenant:<tenantId>:<tokenHash>` record; neither record stores the raw `game-session-account-delegation` JWT. Its owning runtime/data contract is defined in [Account Runtime and Data](./microservices/account-service/runtime-and-data.md#session-and-token-model). The target issued-token registry record, registry-backed storage, and consumer enforcement are not current behavior; target Game Session use of that registry path is defined in [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). Token `jti` and connect-token identity evidence remain target internal registry, replay, and audit evidence; login/session responses and protocol carriers never send `jti` to clients. **Target-only orphan-token retirement (not current):** if the Game Session authenticated-context CAS fails after target token issuance, Game Session must call Account's idempotent retire/abort operation for that exact request and token identity; Account removes or revokes the orphan registry record, and an orphaned token is never accepted merely because it was cryptographically valid. Game Session emits `OK LOGIN Logged in` (or equivalent account-confirming text) on the wire only after the authenticated account context binds successfully. `PLAY` performs gameplay admission, binding, and finalization. Error responses are translated to the shared `ERROR <CODE> <message>` format so protocol clients see consistent codes regardless of transport.

Gameplay commands such as `LOOK` and `SAY` are gated by both the authentication handshake (`LOGIN`) and the lobby selection step (`PLAY`). Any text command received before login should be rejected with stage-aware guidance such as `ERROR LOGIN_REQUIRED ...`, and any gameplay command received before `PLAY` should be rejected with stage-aware guidance such as `ERROR PLAY_REQUIRED ...`. Except in explicitly documented development/test bypass modes that grant temporary access, these commands are not processed for anonymous or unscoped sessions, keeping the gameplay queue free of unauthenticated traffic.

Routing errors on the authenticated text-protocol path preserve the Account classification through Game Session rather than being collapsed into a generic login result. Reachable missing, malformed, ambiguous, stale, or otherwise contract-invalid pointer evidence is passed through as `ERROR ADMISSION_POINTER_UNAVAILABLE <bounded message>`; Game Session keeps `LOGIN` state, creates no gameplay binding, and requires fresh `REALMS`/routing reconciliation before retry. An unreachable or timed-out pointer authority is passed through as `ERROR UNAVAILABLE <bounded message>` for retryable `AUTH_UNAVAILABLE`, not as pointer-invalid evidence and not as permission to use a cached selector or target. A representative interaction is:

```text
REALMS docks
ERROR ADMISSION_POINTER_UNAVAILABLE Realm routing evidence is invalid; retry REALMS.
REALMS docks
OK REALMS ...
```

Credential-bearing login commands carry an account email and one secret. A one-argument login carries only the email and requests the neutral email-code challenge. Bootstrap-backed first-party `LOGIN` carries no credentials because it consumes the already verified bootstrap/connect context. Accounts are platform-wide and not tied to a single game or tenant; the same account is used across all worlds as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model).

### Tenant Selection for Gameplay (Lobby Selection)

FireMUD uses a **single shared entrypoint** for many worlds (tenants). After public `WORLDS` discovery and successful `LOGIN`, clients complete a lobby selection step that binds the authenticated connection to a specific world (`tenantId`), gameplay-admissible instance (`gameInstanceId`), and gameplay identity (`characterId`) before gameplay commands are accepted.

Players must never be asked to type platform-scope identifiers such as `tenantId`, `gameInstanceId`, or `characterId` during lobby selection. Lobby flows accept human-friendly world slugs, menu indices, and character names or indices and resolve them server-side. Gameplay may separately expose stable numeric runtime-entity IDs when useful for distinguishing visible live instances; those IDs remain scoped selectors rather than authorization.

After public `WORLDS` discovery and successful `LOGIN`, the Game Session Service requires the authenticated lobby selection flow using these canonical commands:

- `WORLDS` – list worlds the authenticated account can enter (a numbered menu plus stable
  `tenantSlug` and `worldSlug` values for each entry).
- `HELP` – return static command/help content only. It is non-discovery and does not expose worlds, realms, characters, membership, or admission state; `WORLDS` remains the sole anonymous discovery command.
- `REALMS <world>` – list the visible realms for the selected world (`<world>` is a response-local
  world index or the stable tenant-qualified selector returned by `WORLDS`). Responses include the
  default production realm plus any explicitly authorized additional realms such as playtest
  forks.
- **Target `JOIN <world>`** – `<world>` remains an adapter-local selector. The Account-owned `JoinPublicProductionMembership` lifecycle, idempotency, caller binding, and `connectScopeId`/`requestId` contract are defined in [Account Runtime and Data](./microservices/account-service/runtime-and-data.md#membership-and-entitlement-authority); first-party clients expose the equivalent `Join & Play` action through Account bootstrap.
- `CHARS <world> [realm]` – list characters for the selected world and optional realm; it does not require a selected character. Game Session resolves `playableStateScope` from the exact server-side realm snapshot before the character query; callers cannot provide that scope, a storage key, or a join-derived substitute.
- `PLAY <world> [realm] [character]` – enter gameplay by selecting a world, an optional realm, and an optional character.

`public_production_onboarding` is the target lobby route class for discovery and first-join work in the default public production realm. In target behavior, an authenticated caller may see that realm before membership exists for the selected tenant, but must explicitly use `Join & Play` or `JOIN <world>` before character creation or connect-token issuance. The resulting membership is the intended durable account-to-game relationship used by later discovery and return flows. `PLAY` and the gameplay transport are classified as `gameplay_admission`; current implementation drift is recorded in [Implementation Status](#implementation-status) rather than treated as completion.

Realm discovery and routing contract:

The canonical realm catalog and admission-pointer schema, ownership, revisions, and cutover rules are defined in [Multi-Tenancy](./system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract). Authentication retains the player-visible and receiving-service consequences:

- Clients select human-friendly world/realm values; the server resolves them to the current target and never trusts raw `tenantId` or `gameInstanceId` input.
- `REALMS`, `CHARS`, `POST /auth/connect-token`, `PLAY`, and reconnect consume the same catalog/pointer result. Reachable missing, malformed, ambiguous, stale, or contract-invalid pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out authority is `AUTH_UNAVAILABLE`; a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair is `CONNECT_SCOPE_MISMATCH`; a complete `CLOSED` pointer is `REALM_UNAVAILABLE`.
- Public-production discovery may precede membership. Non-public realms require existing caller-bound `ACTIVE` membership and the current Account-owned grant; neither discovery nor transport creates membership.
- A stale selector or routing pair cannot be silently translated to a different runtime target; the flow must rediscover and retry.

Lobby discovery source-of-truth contract:

Authentication consumers use Account-owned membership, entitlement, and grant reads plus the canonical realm catalog/pointer. This section retains local discovery and receiving-service behavior:

- `WORLDS` may show an explicit availability-unknown state when entitlement refresh is unavailable, but discovery never creates membership, mints a connect token, binds gameplay, or starts capacity.
- `REALMS <world>` distinguishes public-production visibility from explicit realm grants and omits hidden or unauthorized realms.
- `CHARS <world> [realm]` resolves the realm through current routing, checks membership/grant/entitlement/pointer predicates, and reads the authoritative character store only after those checks pass. It is a character-list operation, not final gameplay binding.
- `WORLDS` and `CHARS` must not disclose inaccessible tenants, realms, or characters; unresolved selectors use the established `WORLD_NOT_FOUND`, `WORLD_ACCESS_DENIED`, `CHARACTER_NOT_FOUND`, or `CHARACTER_ACCESS_DENIED` outcomes.

Lobby command classification contract:

- `WORLDS_PUBLIC` is the public browse-only mode before authentication. `WORLDS_AUTHENTICATED` is the authenticated **pre-tenant discovery** mode, not a normal tenant-scoped route; it runs after account authentication but before a single `tenantId` has been selected.
- `REALMS <world>` selects exactly one policy branch: only public-production discovery uses `public_production_onboarding`; private/playtest discovery uses caller-bound membership/grant policy and is not public onboarding. After server-side world/tenant resolution but before membership exists, the public branch may return only the exactly resolved, publicly visible default public-production realm with current entitlement authority and no membership/grant requirement. For an existing relationship, public production still permits discovery when public visibility and entitlement pass, while every non-public realm requires membership with exact `membershipLifecycleState=ACTIVE` plus the Account-owned grant for the exact `{accountId, tenantId, worldSlug, realmSlug}`; a grant never substitutes for membership and `REALMS` never creates membership or gameplay authority.
- `REALMS <world>` resolves the current catalog/pointer pair before returning a realm. Reachable missing, malformed, ambiguous, stale, or contract-invalid evidence is `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out authority is `AUTH_UNAVAILABLE`; a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair is `CONNECT_SCOPE_MISMATCH`; a complete `CLOSED` pointer is an unavailable realm rather than an incomplete pointer. It remains discovery before binding to a concrete `gameInstanceId`, but it is not reclassified as `pre_tenant_discovery` or `tenant_regular` at either stage.
- `CHARS <world> [realm]` and `PLAY <world> [realm] [character]` become tenant/realm-scoped only after `<world>` and optional `[realm]` are resolved server-side to canonical `{tenantId, gameInstanceId}`.
- Shared auth middleware and route-matrix entries must not model all lobby commands as one undifferentiated tenant-scoped surface.

The target-state `PLAY` predicates and consequences:

The canonical cross-surface `PLAY` outcome inventory and precedence are defined in [Protocol Bridging](./system-architecture-protocol-bridging.md#canonical-play-error-inventory). The predicates below are authentication-local details, not a competing evaluation order; implementations must apply the canonical routing/pointer and connect-scope gates before entitlement authority or public membership classification.

- Resolves `<world>` to a canonical `tenantId` and validates it exists.
- Resolves optional `[realm]` to a canonical realm for that tenant. If no realm is supplied, the tenant's default production realm is selected.
- Resolves the selected realm's gameplay-admissible instance and records that `gameInstanceId` in the gameplay binding. The selected-target catalog/policy revision and routing pointer remain the authoritative pair; Account entitlement `allowPublicJoin` does not replace either routing input.
  - First-party `/ws/game/**` contract: if a validated connect token is present, resolved `tenantId` and `gameInstanceId` must match token claims. On mismatch, reject admission with `CONNECT_SCOPE_MISMATCH` and do not bind session scope.
  - Runtime control-plane and admission flows use the realm-routing contract from [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) as the source of truth for which concrete `gameInstanceId` is admissible for the selected realm.
- After the canonical routing and connect-scope gates, consults the runtime entitlement contract `GetTenantEntitlementsForRuntime(tenantId, requestId)` to confirm that the tenant is currently available for gameplay (for example, subscription state is not `suspended` or `canceled` and hard quotas are not violated). For player admission, the returned evidence is caller-bound to `accountId`, the verified `connectScopeId`, and the exact resolved target; a tenant-only entitlement bundle cannot authorize a player binding.
- Verifies that the account is authorized to play in that `tenantId` using caller-bound gameplay membership and any required realm grant. Global roles and public discoverability alone must not satisfy gameplay admission.
- If the public realm is visible but the account's durable membership is missing or `INACTIVE`, returns `JOIN_REQUIRED` with `JOIN <world>`/`Join & Play` recovery guidance and does not create membership or other admission state. This classification occurs only after fresh Account entitlement says `allowPublicJoin=true` and fresh selected-target catalog/pointer evidence identifies the public-production realm; entitlement outage returns `ENTITLEMENT_UNAVAILABLE` and a public policy/target denial returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`. Explicit public-production `JOIN` establishes or restores `ACTIVE` membership as defined above; an `ACTIVE` membership is returned idempotently and all other states reject. Private/playtest admission requires existing `ACTIVE` membership plus its current grant and never invokes this writer.
- **Target membership-writer contract:** Membership lifecycle writer authority remains Account Service. The accepted canonical API is `JoinPublicProductionMembership`; the obsolete differently named proto/client/service seam has been removed rather than retained as a compatibility operation. The text-protocol adapter passes only the Account-bound `{connectScopeId, requestId}` plus trusted caller context to `JoinPublicProductionMembership`; world and realm remain adapter-local selectors, and the adapter never passes client- or adapter-supplied `tenantId`, route, `gameInstanceId`, `catalogRevision`, `pointerVersion`, or route authority. Account resolves and revalidates the tenant, world, realm, game instance, and exact catalog/pointer pair from `connectScopeId` at the commit gate rather than accepting those fields as independently authoritative player inputs. The operation is explicit `JOIN`/`Join & Play`; character creation, connect-token issuance, and `PLAY` never create or restore membership. Current writer and issuance gaps are recorded in [Implementation Status](#implementation-status).
- Performs an authoritative internal membership read for `{accountId, tenantId}`, requires `membershipExists=true` and exact `membershipLifecycleState=ACTIVE`, and obtains both `membershipVersion` and `membershipAuthorityGeneration`. For connect-token-backed admission, these three values must exactly equal the issuance baseline captured from one Account snapshot; the baseline is carried unchanged through the Account lease, Game Session binding CAS, and Account finalization. The membership response must also assert `gameplayAdmissionAllowed=true`; gameplay admission must not source membership state, either membership field, or gameplay authority from JWT claims or local caches. Before the binding can become admissible, Account must issue an exact-binding admission lease containing a unique lease ID, monotonic lease fence, immutable authority tuple/cutoff checkpoint, the unchanged membership lifecycle/generation/version baseline, and a bounded absolute expiry no longer than the admission SLA; the lease cannot be renewed to extend an admission attempt. Game Session's binding CAS includes that fence and may publish only a provisional record. Account finalization, abort, and reconciliation are idempotent by lease ID and request ID and must reject any recomputed or changed baseline. A binding CAS failure, lease expiry, authority cutoff, membership lifecycle/generation/version change, or ambiguous outcome invokes the idempotent Account abort/reconcile path; Account may retire any orphan token, but emits durable orphan binding evidence and a cleanup request, Gateway performs fenced edge cleanup, and Game Session performs fenced binding/index cleanup. A provisional record is never admissible, resumable, or takeover-eligible; only a durably finalized `COMMITTED` lease can admit gameplay.
- Resolves `[character]` to a canonical `characterId` scoped to `{accountId, tenantId, gameInstanceId}` according to the selected realm's character policy.
  - Explicit character creation and selection are part of the v1 contract. If the selected realm has no visible character for the caller, the client must complete the canonical character-creation flow before `PLAY` can succeed.
  - `PLAY` may omit `[character]` only when exactly one visible character exists for the resolved realm. Otherwise admission fails with `CHARACTER_REQUIRED`.
- **Current live admission boundary:** Current `PLAY` binding and the missing authoritative region/lease resolution are recorded in [Implementation Status](#implementation-status). The target ownership and timeline checks below are normative requirements and are not implied by the live binding.
- **Target authoritative admission:** before committing gameplay binding, Game Session applies the current region/lease ownership and exact-binding authority contract. Gateway owns edge route lifecycle and connect-token scope; Game Session remains the receiving service for binding and ownership validation. Missing or ambiguous ownership returns `OWNERSHIP_UNAVAILABLE`, an unavailable dependency returns `AUTH_UNAVAILABLE`, stale ownership or timeline returns `STALE_TIMELINE`, and a verified target scope differing from the requested connect scope returns `CONNECT_SCOPE_MISMATCH`. `PLAY` must not bind from cached ownership, raw transport headers, or a stale discovery result.
- On successful admission, runtime must return the resolved realm bundle identity at minimum as `versionId`, optional `scriptPatchVersion`, and manifest location/hash (or a stable bundle token that resolves to those fields) so clients can apply realm-specific branding and assets.
- Binds the socket to a gameplay session key for the chosen world/instance/character identity under `session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>` as described in [Multi-Tenancy](./system-architecture-multi-tenancy.md#identity--tenant-model) and [Redis Architecture](./system-architecture-redis.md#session-keys-and-gameplay-binding).
- Target-state admission must ensure the gameplay session binding is consistent with the tick/lease ownership model for the character’s current `<tenantId, gameInstanceId, regionId>`. Edge route lifecycle, gameplay sharding, and lease-aware selection are owned by [Gateway architecture](./system-architecture-gateway.md#gameplay-sharding-routing-boundary); Game Session validates the receiving-service binding contract.

### PLAY Current and Target Failure Boundaries

Current `PLAY` membership, JOIN, endpoint, and runtime gaps are consolidated in [Implementation Status](#implementation-status). The target failure boundary below must not be read as proof that those current gaps are closed.

`PLAY` has an explicit pre-admission versus post-admission failure boundary. Before Account finalizes the exact-binding admission lease and Game Session publishes an admissible binding, an unreachable or timed-out lease, binding/CAS fence, applicable authority or projection, routing authority, or required coordination-health dependency returns retryable `AUTH_UNAVAILABLE` / HTTP 503. No provisional binding becomes admissible, no cached authority or local binding substitutes for the dependency, and the client retains authentication state for bounded retry. Reachable missing, malformed, ambiguous, stale, or contract-invalid catalog/pointer evidence returns `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out authority is `AUTH_UNAVAILABLE`; a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair is `CONNECT_SCOPE_MISMATCH`; and a complete `CLOSED` pointer returns `REALM_UNAVAILABLE`. A reachable missing, malformed, stale, regressed, expired, mismatched, or revoked non-routing value remains the applicable denial or `AUTH_SESSION_REVOKED`; it is not reclassified as an outage.

After a binding is admitted, the token-authority-only continuation exception from [ADR 0037](./decisions/adr-0037-fail-closed-token-authority-outages-with-bounded-active-gameplay.md) applies only when a live current-generation coordination-health check and the prior-positive ADR 0030 authority-freshness lease both remain valid. An authority cutoff or reachable revoked result is `auth-revoked`: Game Session closes the socket, removes admission state, and ends the current resume episode; after disconnect, restoration requires fresh `LOGIN` plus `PLAY` and current authoritative checks. A same-stream outbox gap or conflict, stale/regressed projection, or unavailable/ambiguous active-binding index stops affected admission and reconnect/resume; unreachable reconciliation or index authority remains retryable `AUTH_UNAVAILABLE` while the operation stays denied, and reachable contradictory or revoked evidence is `AUTH_SESSION_REVOKED` or the specific denial. Affected active bindings are terminated through the bounded `<=60-second` cleanup path, and unaccounted bindings remain blocked until reconciliation proves coverage. Complete Coordination Redis failure is not a token-authority-only outage: correctness-sensitive gameplay mutations halt, local state cannot authorize gameplay, and reconnect/resume or fresh admission remains closed until the existing bounded close/recovery path re-establishes authority; a binding terminated by that path requires fresh `LOGIN` plus `PLAY`.

`CONNECT_SCOPE_MISMATCH` and `STALE_TIMELINE` are intentionally disjoint:

- `CONNECT_SCOPE_MISMATCH` means the verified target scope or its exact `catalogRevision`/`pointerVersion` pair differs from the requested connect scope: the server-resolved `{tenantId, gameInstanceId}` for the requested stable world/realm does not match the verified first-party connect context or connect-token scope, or a valid scope changed its exact revision pair. It is an admission-scope/issuance drift and requires fresh bootstrap, token issuance, and connection establishment.
- `STALE_TIMELINE` means the selected runtime target was valid, but its authoritative ownership region, epoch, lease fence, or equivalent runtime timeline no longer matches at the ownership check. It requires rediscovery and explicit retry; it must never be repaired by silently rebinding to a different target.

The target ownership checks may produce `OWNERSHIP_UNAVAILABLE` for a reachable but incomplete or ambiguous ownership response; an ownership or coordination dependency that cannot be read or times out returns `AUTH_UNAVAILABLE`. Pointer outcomes use the exact classification above, including `CONNECT_SCOPE_MISMATCH` for a valid scope with a changed exact `catalogRevision`/`pointerVersion` pair and `REALM_UNAVAILABLE` for a complete `CLOSED` pointer. They must not relabel a verified connect-scope mismatch as a stale timeline or relabel a stale runtime fence as a connect-scope mismatch.

The canonical inventory also owns the shared `PLAY` codes and their precedence. Authentication-local consequences are to preserve authenticated state and create no gameplay binding for pre-admission failure; retry `ENTITLEMENT_UNAVAILABLE` with bounded backoff; surface `TENANT_BILLING_BLOCKED` or `PUBLIC_PRODUCTION_ADMISSION_DENIED` without treating either as an outage; present `JOIN` / `Join & Play` for `JOIN_REQUIRED`; keep `NON_PUBLIC_ENROLLMENT_REQUIRED` distinct from `REALM_ACCESS_DENIED`; and use `AUTH_UNAVAILABLE` when an applicable authority, including routing/pointer authority, is unreachable or times out. Reachable invalid routing evidence uses `ADMISSION_POINTER_UNAVAILABLE`, `CONNECT_SCOPE_MISMATCH`, or `REALM_UNAVAILABLE` instead. `CONNECT_SCOPE_MISMATCH` discards the complete discovery snapshot and derived token metadata before fresh discovery and token issuance. Any later tenant or character switch must repeat the same canonical selection flow so role checks and entitlements are re-evaluated.

First-party gameplay admission and reconnect clients should treat the following target-state errors as canonical. The rows are grouped by surface and local reaction, not ordered by `PLAY` precedence; the canonical ordering remains in [Protocol Bridging](./system-architecture-protocol-bridging.md#canonical-play-error-inventory). Current membership and JOIN behavior is recorded in [Implementation Status](#implementation-status); the rows below define target reactions and must not be reported as proof that the explicit join path is implemented:

| Surface | Canonical code | Trigger condition | Required client reaction |
| --- | --- | --- | --- |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_MISSING` | No connect-token carrier is supplied at all. For the supported `first_party_web` route, this means no `Firemud-Connect-Token` cookie; a header-only request on an unsupported or unregistered `non_first_party_public` route, or a duplicate/ambiguous carrier, is `CONNECT_TOKEN_REJECTED` instead. | Obtain a fresh connect token and open a new socket with bounded retry/backoff. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_EXPIRED` | Connect token expired before gateway validation completed | Obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Obtain a fresh connect token and open a new socket with bounded retry/backoff; repeated replay failures should not fast-loop. |
| `/ws/game/**` handshake (`403`) | `CONNECT_SCOPE_MISMATCH` | Handshake-carried scope does not match the verified connect-token scope | Rerun bootstrap discovery for the intended realm target, obtain a fresh connect token, and open a new socket. |
| `/ws/game/**` handshake (`403`) | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded slower backoff and surface temporary edge-auth-unavailable context rather than backend-outage messaging. |
| `/ws/game/**` handshake (`403`) | `CONNECT_TOKEN_REJECTED` | `reason=unsupported_carrier_or_route` means the carrier/route is unsupported, unregistered, duplicate, or ambiguous; `reason=invalid_token_content` means a selected supported carrier contains malformed, signature-invalid, missing-claim, wrong-audience, or otherwise invalid token content | For `unsupported_carrier_or_route`, use the supported carrier/route without refreshing the token. For `invalid_token_content`, obtain a fresh connect token and open a new socket with bounded retry/backoff. |
| `/ws/game/**` handshake (`403`) | `POLICY_DENY` | Edge policy rejects the handshake for a non-token reason (for example proxy trust/config mismatch) | Treat as non-retriable until operator/client configuration is corrected. This is a handshake classification, not a post-connect text-protocol `ERROR <CODE>` response. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_CONTEXT_INVALID` | Required gateway-signed connect context is missing, expired, unverifiable, or otherwise invalid | Refresh connect token, reconnect, then re-`LOGIN`; do not retry `PLAY` on the current socket. |
| `PLAY` on first-party `/ws/game/**` | `CONNECT_SCOPE_MISMATCH` | The server-resolved runtime target for the requested stable world/realm selector does not match the validated connect-token scope | Re-select the intended world/realm, obtain a fresh connect token for that target, reconnect, and retry `PLAY`. |
| `LOGIN` on first-party `/ws/game/**` | `ACCOUNT_MISMATCH` | Bootstrap-backed login resolved to an account different from the validated connect-context subject | Treat as a hard auth failure for the current socket; clear the gameplay bootstrap/connect flow and require a fresh authenticated bootstrap. |
| `PLAY` | `WORLD_ACCESS_DENIED` | Reachable authoritative world/tenant policy denies gameplay for the resolved target for a reason other than missing or `INACTIVE` public-production membership | Keep auth state, surface an authorization error, and do not infer hidden-tenant existence beyond the canonical code. This code is mutually exclusive with `JOIN_REQUIRED`; public missing/`INACTIVE` membership uses `JOIN_REQUIRED` after the earlier routing, scope, entitlement, and authority gates pass. |
| `PLAY` | `JOIN_REQUIRED` | After routing, scope, entitlement, public-policy, and authority-availability gates pass, current public-production membership is missing or `INACTIVE` and explicit join has not succeeded | Preserve auth state, present `JOIN` / `Join & Play`, and block character creation, connect-token issuance, and `PLAY` retry until join succeeds. Never create or restore membership implicitly; private/playtest targets still require existing `ACTIVE` membership plus their current grant. This code is mutually exclusive with `WORLD_ACCESS_DENIED`. |
| `PLAY` | `REALM_UNAVAILABLE` | The current complete pointer says the selected realm is `CLOSED` and has no admissible target | Preserve authenticated lobby state, create no gameplay binding, and wait for fresh discovery after the realm reopens; do not fast-loop retries. |
| `PLAY` | `ADMISSION_POINTER_UNAVAILABLE` | A reachable catalog/pointer authority returns missing, malformed, ambiguous, stale, or contract-invalid pointer evidence | Preserve authenticated lobby state, create no gameplay binding, rerun discovery/reconciliation, and retry only with fresh pointer evidence; never substitute a cached target. An unreachable or timed-out Account/routing authority dependency is the separate retryable `AUTH_UNAVAILABLE` class. |
| `PLAY` | `CONNECT_SCOPE_MISMATCH` | The verified connect context is valid but its exact `catalogRevision`/`pointerVersion` pair changed, or its resolved `{tenantId, gameInstanceId}` scope no longer matches the selected stable world/realm | Preserve account authentication but discard the complete discovery snapshot and all derived token metadata, including `catalogRevision`; rerun discovery, issue a fresh token, open a new socket, and retry `LOGIN`/`PLAY`. |
| `PLAY` | `TENANT_BILLING_BLOCKED` | Entitlement state authoritatively denies this gameplay admission, including `suspended`/`canceled` or a `grace` state that denies the requested new commitment | Preserve auth state, create no gameplay binding, surface the tenant billing block, and disable only the denied admission until Account reports an allowed state. Preserve connected sessions, eligible same-session resume, permitted `past_due` gameplay, and billing-safe operations unless independently denied. |
| `PLAY`, new admission, restart/rollback, another new commitment, or ineligible continuity operation | `ENTITLEMENT_UNAVAILABLE` | Fresh entitlement authority is unavailable and no eligible unchanged public-production binding-continuity snapshot exists; strict new commitments require a snapshot fresh enough for the 15-second admission SLA | Keep auth state, retry with bounded backoff, never admit a strict commitment from stale entitlement state, and never use grace after hard denial, revocation, or sequence uncertainty. |
| `PLAY` before admission commits | `AUTH_UNAVAILABLE` | A required Account lease, binding/CAS fence, authority/projection, reconciliation, coordination-health, or routing/pointer dependency is unreachable or times out | Keep auth state, create no admissible binding, and retry with bounded backoff. A reachable invalid/revoked value uses the applicable denial or `AUTH_SESSION_REVOKED`; reachable routing/pointer evidence uses `ADMISSION_POINTER_UNAVAILABLE`, `CONNECT_SCOPE_MISMATCH`, or `REALM_UNAVAILABLE` as applicable. |
| `PLAY` | `OWNERSHIP_UNAVAILABLE` | A reachable runtime ownership response is incomplete or ambiguous for the selected target | Keep auth state, create no gameplay binding, rediscover runtime ownership with bounded backoff, and retry admission only after fresh authority is available. Unreachable or timed-out ownership uses `AUTH_UNAVAILABLE`. |
| `PLAY` | `STALE_TIMELINE` | The selected region, epoch, or lease fence no longer matches current runtime authority | Keep auth state, create no gameplay binding, rediscover the realm and runtime timeline, and retry admission explicitly; never accept an implicit rebind. |
| Gameplay command before `PLAY` | `PLAY_REQUIRED` | Client issued a world-scoped gameplay command before lobby admission completed | Keep auth state and route the client back through `PLAY`, `REALMS`, or `CHARS` as appropriate. |

Reconnect sequencing, snapshot reuse, transport-local scope replacement, and resume/takeover behavior are owned by [Reconnection Strategy](./system-architecture-reconnection.md) and [Session Behavior](./system-architecture-session-behavior.md). Authentication's target-state consequence is that each reconnect re-establishes the exact transport authentication path and then uses fresh routing plus one fresh Account membership snapshot before admission; stale selectors, expired scopes, partial membership evidence, and client-supplied authority never authorize fallback. Current runtime deviations from the target flow are recorded in [Implementation Status](#implementation-status), including the unavailable explicit `JOIN` operation.

Gameplay identity is canonicalized on `characterId` within a tenant. All Redis key formats and Game Session Service APIs must treat `characterId` as the abstract character identifier so sessions bind sockets to characters rather than raw accounts. Canonical takeover and resume identity is `{tenantId, gameInstanceId, characterId}`.

Gameplay identity is single-mode and canonical: uniqueness key `{tenantId, gameInstanceId, characterId}`.

> 🔗 For session resumption and reconnect edge cases, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## Logout Ordering

The canonical per-token and logout-all ordering, token fencing, Gateway deny-marker, and reconciliation contract is defined once in [Session Behavior](./system-architecture-session-behavior.md#control-plane-logout). Authentication surfaces follow that contract; logout is not inferred from registry presence or absence.

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

Because gameplay services do not validate end-user JWTs, they consume the workload trust predicates owned by [Security](./system-architecture-security.md#gameplay-workload-trust). The local receiving consequence is that tenant/session/player identifiers remain scoped data and client-supplied headers cannot create trusted context.

### Gameplay Player Execution Context Contract (Normative)

When one trusted gameplay workload calls another on behalf of a player, the request carries a typed protobuf `PlayerExecutionContext` with the required subset of:

- `accountId`
- `tenantId`
- `gameInstanceId`
- `characterId`
- `sessionId`
- applicable room, region, lease/epoch, admitted-bundle, realm, pointer, or playable-state scope
- stable request, command, or effect identity where the operation requires it

`PlayerExecutionContext` is unsigned structured scope data, not a credential. Consumers apply the concrete mTLS identity and RPC allowlist from [Security](./system-architecture-security.md#gameplay-workload-trust), then validate context/request equality and enforce the complete tenant/game/resource and domain-ownership scope in existing reads and writes. These checks must not add a fresh Account, Redis, or database lookup solely to authorize every routine action.

Gameplay mutations use their command/effect/request idempotency contract. Reads do not use a generic replay store. FireMUD deliberately accepts that a compromised allowlisted intermediary can fabricate player context for methods it is permitted to call; [ADR 0024](./decisions/adr-0024-trusted-gameplay-workload-delegation.md) records that trust boundary and the separate protections for operator and financial actions.

All meta services use a shared `AuthTokenInterceptor` that extracts claims from the `Authorization` header and stores them in a thread-local `SessionContext`. Service methods read roles from this context via the `@RequireAdminRole` annotation (or similar). Gameplay services never read or propagate these claims.

### Mandatory Auth Middleware

Meta/control services that depend on JWT claims must install the shared `AuthTokenInterceptor` configuration. The exact pending-deletion, registry-backed JWT, and gameplay-connect exceptions are owned by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md) and [Authorization Route Matrix](./system-architecture-authz-route-matrix.md); fresh `PLAY`, reconnect, and resume use their bound-session checks and routine gameplay does not repeat registry lookups. No authorized controller or gRPC service may bypass its exact route auth path.

---

## Trust Boundaries and Token Validation

The Gateway sits at the edge of the platform and is deliberately **not** an authorization authority. Its route and carrier behavior is owned by [Gateway architecture](./system-architecture-gateway.md); this section retains receiving-service obligations:

- Spring Cloud Gateway enforces presence of an `Authorization` header where the route requires it but does not validate ordinary JWT contents.
- Meta/control services receiving Gateway requests validate the declared token profile and route predicates through Account JWKS and shared middleware.
- Gameplay services do not accept client JWTs; they consume validated typed `PlayerExecutionContext` from approved workload callers and apply the receiving-service domain checks above.

When adding a new public HTTP/gRPC route:

- Classify it using the shared classes in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md); do not invent a local route class.
- For non-public routes, apply the route-matrix authentication contract. JWT-bearing classes install `AuthTokenInterceptor`; `internal_workload` routes use the Security-owned workload identity, method allowlist, and typed context contract instead of a blanket JWT requirement.
- For tenant-scoped routes that must remain reachable when a tenant is `suspended` or `canceled` for billing (for example, updating payment methods, viewing invoices, or tenant-scoped data export), explicitly mark them as **billing-safe control-plane routes** using a shared mechanism such as an annotation or route metadata flag (for example, `@BillingSafe`). Full account export remains `account_scoped` and must not be used as the suspended-tenant recovery export.
- Log and audit cross-tenant operations, especially when initiated by roles such as `platformAdmin`, so misuse or misconfiguration is observable.
- Register the route and its classification in [Authorization Route Matrix](./system-architecture-authz-route-matrix.md). Runtime middleware rejects an unclassified protected route immediately.

## Session Lifecycle and Rebinding

Gameplay takeover, reconnect, token refresh, membership-version handling, and control-plane logout behavior are defined in [Session Behavior](./system-architecture-session-behavior.md). This parent doc keeps the admission and authorization model while the sibling doc carries the long-form lifecycle rules.

---

## Summary

| Topic | Description |
| --- | --- |
| Auth Command | Current and target `LOGIN`/`LOGON` forms are recorded in [Implementation Status](#implementation-status); prompt exchange remains target-only. |
| JWT Usage | Current transport and endpoint/header availability are recorded in [Implementation Status](#implementation-status). Raw Telnet gameplay command streams do not carry JWTs; target browser/mobile gameplay uses `player-bootstrap` and an HttpOnly-cookie connect token for `/ws/game/**`. Profile and registry authority is [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md). |
| Claims | Profile-dependent: each token profile declares its own required claims; `sub` and `nbf` are present only where that profile requires them, while registry-backed profiles additionally carry `authorityTuple`, applicable `membershipVersion` evidence, and `issuanceFence`, plus applicable `accountId`, `tokenGeneration`, `globalRoles[]`, or `scopedRoles{}` according to the token contract |
| Session State | Stored in Redis; bound to socket by Game Session Service |
| Gameplay Continuity TTL | Separate `session_expiration_ms` policy with an independent effective maximum of five minutes; the configurable JWT cleanup margin applies only to issued-token registry retention |
| Issued-Token Registry TTL | Defined by [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md); the local margin is `FIREMUD_AUTH_SESSION_SAFETY_MARGIN_MS` and activity does not extend it |
| Gameplay Reauthentication | Current and target reauthentication are recorded in [Implementation Status](#implementation-status); [Session Behavior](./system-architecture-session-behavior.md) owns disconnect, expiry, revocation, refresh, and resume, with fresh `LOGIN` then `PLAY` when required |
| Role Enforcement | Meta/control services validate declared JWT profiles; gameplay services use Security-owned mTLS caller trust plus the validated `PlayerExecutionContext` contract |
| Role Updates | Target: Account-owned role/token refresh is intended to be invisible in-session; current refresh gaps are recorded in [Implementation Status](#implementation-status) |
| Multi-Client Behavior | One gameplay session per `{tenantId, gameInstanceId, characterId}`; takeover and rebinding are defined in [Session Behavior](./system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover) |
| Login Modes | Current modes and target factor boundaries are recorded in [Implementation Status](#implementation-status) |

---

## Related Documentation

- [Authorization Route Matrix](./system-architecture-authz-route-matrix.md)
- [JWT and Token Contracts](./system-architecture-jwt-and-token-contracts.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Session Behavior](./system-architecture-session-behavior.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [User Journeys – Sign Up](../product/user-journeys/players.md#1-sign-up)
