# ADR 0036: Monotonic Authority Generations for Bulk Token Revocation

## Status

Accepted

## Implementation Status

The monotonic generation contract is target state and is not fully implemented or proved. Current Account runtime documentation records legacy Account session keys and incomplete issued-token/auth-generation enforcement; durable generation records and projections, transactional issuance/revocation ordering, and cross-scope validation proof remain outstanding.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `SF-2.2`, `AA-1.2`, `AA-1.3`, `AA-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `JWT-02`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `JWT-02`

## Context

FireMUD requires immediate bulk revocation for an Account issuer, one account, one tenant, or one account-to-tenant membership without scanning every issued-token record. The previous contract stored UTC epoch-second cutoff timestamps and compared them with JWT `iat`.

Second-level timestamps cannot order issuance and revocation within the same second. Treating tokens as revoked when `iat < cutoff` can preserve a token issued just before the revocation; using `iat <= cutoff` can reject a token issued just after it. Higher timestamp precision reduces collision frequency but does not establish ordering across Account replicas or remove clock dependence.

The Account domain already requires monotonic versions and durable ordered security, membership, and billing events. Bulk token revocation should use that authority rather than wall-clock ordering.

## Decision

### Generation Authorities

- Account owns durable positive monotonic integer generations for the environment issuer, each account, each tenant, and each account-to-tenant membership.
- Coordination Redis holds Account-owned current-generation projections under the canonical families:
  - `session:auth:generation:issuer:<issuerId>`
  - `session:auth:generation:account:<accountId>`
  - `session:auth:generation:tenant:<tenantId>`
  - `session:auth:generation:membership:<accountId>:<tenantId>`
- Missing, malformed, unavailable, stale, or regressed generation state never means generation zero. For every registry-backed Account JWT control-plane or admission operation, and for every explicit authority check required by a route that uses a registry-backed Account JWT, including token issuance, refresh, logout, or admission, the consumer makes one bounded synchronous Account read of the versioned [`account-auth-evidence-bundle/v1`](./adr-0035-single-record-issued-token-registry.md) at one Account linearization point, except for the lifecycle-only stored-result/tombstone retry branch defined below. This generation/evidence-read contract applies only to the registry-backed `control-ui`, `player-bootstrap`, and receiver-specific private player-delegation profiles. It explicitly excludes the `gameplay-connect` JWT and Gateway-signed connect context: `gameplay-connect` follows ADR 0029's shared `replayAdmissionFence`, quarantine-cutoff, and atomic single-use replay rules, while the signed context follows ADR 0024's trusted workload delegation boundary and ADR 0029's replay-fenced handshake/carriage rules. Neither artifact receives an Account issued-token registry record or Account authority-generation/evidence read. For the included profiles, the bundle returns the complete applicable current issuer, account, tenant, and membership generations together with their durable source versions and projection-freshness evidence; consumers must not assemble those values from separate generation reads, cached Account responses, token claims, event positions, or Redis timestamps. The consumer then requires each Redis projection value and source version to match the corresponding durable value and evidence in that bundle before using the projection. Account unavailability and a Redis projection timeout, connection failure, or other inability to reach the required evidence return `AUTH_UNAVAILABLE`. When Account and Redis are reachable, missing, malformed, regressed, stale, or mismatched durable, bundled, or projected evidence is authoritative invalid/revoked evidence for the affected scope and fails closed with `AUTH_SESSION_REVOKED` or the route's canonical invalid/revoked outcome.

#### Lifecycle-Only Logout Retry Exception

`AuthLogout` and `AuthLogoutAll` have a narrow retry branch that returns a lifecycle result and never creates authorization context. On the initial authorization attempt, or when no exact durable retry evidence matches, local signature/profile/time/subject validation is followed by exactly one bounded read of the `account-auth-evidence-bundle/v1` before Account authorizes and records the logout operation; the initial path does not assemble authority from separate reads, cached values, Redis, or the token alone. `AuthLogout` may replay only a stored result from a durable Account pending/committed intent or tombstone for the exact token and request identity. `AuthLogoutAll` may replay only durable evidence that a prior logout-all superseded the presented authority and matches the exact retry identity and request digest.

After that local validation, an exact stored-result/tombstone retry reads only the durable Account operation result or tombstone needed to prove that exact lifecycle outcome. It is exempt from a new registry or `account-auth-evidence-bundle/v1` read, and an unavailable or non-matching stored result does not become authorization. The retry returns no JWT, role, tenant, generation, or other auth context; its lifecycle result cannot be reused by another route or authorize any operation. A request without an exact matching durable result follows the initial path and therefore performs the one bundle read rather than being treated as a retry.

### Token Capture And Validation

- Every revocable Account JWT captures the current issuer and account generations when issued.
- A token containing tenant-scoped authority also captures the current tenant and membership generations for exactly the bounded tenant entries already present in its scoped claims. Generation metadata must not introduce scopes absent from those claims.
- Token issuance and authority revocation serialize on the same durable Account generation rows. Account reads the required generations and writes the authority snapshot plus durable issuance evidence in one Account transaction or equivalent Account CAS fenced by the expected generation versions. The Coordination Redis issued-token registry and any Game Session binding are separately fenced, idempotent postconditions reconciled by exact operation identity; no global transaction includes those stores. Account exposes the JWT only after those postconditions match the committed Account snapshot. If issuance commits first, a later revocation advances the generation and invalidates that token. If revocation commits first, the issuance predicate fails and must restart with the new generation before any token is exposed. No transaction may publish a JWT whose registry record and captured authority tuple were assembled on opposite sides of a committed generation advance.
- A global `platformAdmin` token does not acquire tenant claims or membership generations. For an explicitly matrix-allowlisted `tenant_regular` operational/control-plane route, and for `cross_tenant_data_bearing` routes that operate on one target tenant, Account reads the current target-tenant generation while authorizing the request and binds it into the bounded operator authorization reference; the owner redeems that exact reference and rejects it if the target-tenant generation has changed. This is a target-scope freshness fence, not tenant membership, and no caller-bound membership is invented or bypassed. The explicitly safe `cross_tenant_support_safe` and `cross_tenant_billing_safe` classes omit the target-tenant generation only under the closed allowlist below; they still require exact target scope, live global role/assurance, and audit. `platformAdmin` is not a caller-bound substitute for `billing_safe_tenant` and cannot use global role alone for gameplay admission or switching.
- Except for the lifecycle-only stored-result/tombstone retry branch above, after cryptographic/profile validation and the issued-token registry check for a registry-backed Account JWT, a consumer performs the one bounded `account-auth-evidence-bundle/v1` read above, proves exact projection value, source-version, and freshness equality, and then compares the token's captured generations with those current applicable generations. Missing, stale, unavailable, or unverifiable freshness evidence fails closed; every generation required for the route must match exactly. `gameplay-connect` and Gateway-signed connect context use the replay-fence and trusted-workload contracts stated above instead. The retry branch performs local validation, then reads only the exact durable lifecycle result/tombstone and creates no authorization context.
- A mismatch revokes that token's authority at the mismatched scope. Tenant-generation omission is not a general route exception: the only allowed route classifications are the closed allowlist below, and each still requires the listed issuer, account, global-role, or membership checks. A route is never exempt merely because a caller or service labels it billing-safe or support-safe.
- JWT `iat` remains a required audit and lifetime claim but is not bulk-revocation ordering authority.

### PlatformAdmin Tenant-Regular Branch

The `platformAdmin` branch of `tenant_regular` is enforceable only when the route-matrix entry explicitly declares the `tenant_regular` classification, the `control_ui_plus_current_role_and_role_appropriate_assurance` auth path, `accepted_token_profiles: [control-ui]`, `role_assurance: privileged_control_when_global_role`, and the operator branch fields below. The branch requires all of the following, conjunctively:

- the exact `control-ui` token profile and audience, a matching issued-token registry record, current issuer and account generations, and a live current global role of `platformAdmin`;
- an active server-side `privileged_control` assurance window backed by recent ordinary reauthentication and independent TOTP, bound to the current `control-ui` token `jti`, account generation, requested global role, and target operation;
- the exact requested target `tenantId` from the route or typed request, with `target_tenant_generation` bound and validated as the freshness fence, and `global_platform_admin_reference_generation_binding: target_tenant_generation` on the route entry; and
- no caller-bound tenant membership or membership-generation requirement, because this branch has no tenant membership authority. `tenantAdmin`/other tenant-role branches instead require live membership and membership-generation checks, while `support` and `billingAdmin` are rejected for `tenant_regular`.

The route entry must also declare `membership_authority_generation_applies: conditional_by_operator_role` with `membership_authority_generation_condition: {tenant_role: true, platformAdmin_global: false}`, `global_platform_admin_membership_required: false`, and a role set that explicitly includes `platformAdmin`. The common route checks must not unconditionally require the platform-admin-only predicates. The `platformAdmin_global` branch requires `current_operator_roles`, `current_global_role`, `role_appropriate_assurance`, and `target_tenant_generation` (plus route-specific domain checks). The `tenant_role` branch requires its live caller-bound membership, membership-generation, and tenant-role checks and does not inherit `current_global_role`, `role_appropriate_assurance`, or `target_tenant_generation` merely because the route entry also supports `platformAdmin_global`. Branch selection uses one fresh Account global-role result before either predicate is evaluated: conclusive `platformAdmin_global` presence selects only that branch, even when tenant membership also exists, and a failed or non-allowlisted global branch is denied without tenant-role fallback. Only conclusive `platformAdmin_global` absence permits the tenant-role branch; unavailable, stale, malformed, mismatched, or inconclusive role evidence denies both branches.

This branch never authorizes gameplay admission, gameplay switching, or any route whose matrix entry does not explicitly allow the override. A global role, selected UI tenant, scoped-role omission, issuer/account generation, or account-only assurance cannot substitute for the exact target-tenant generation or the required `privileged_control` window.

### Explicit Route-Class Generation Allowlist

Tenant generation applies by default to every tenant-bearing route. The closed omission allowlist for tenant-bearing route classes contains exactly these canonical classifications, and their other authority checks are mandatory:

- `billing_safe_tenant`: require current issuer and account generations, the exact caller-bound `{accountId, tenantId}` membership generation, the exact `membershipVersion` from the same authoritative membership snapshot, and the canonical live `current_operator_roles` check proving the caller's current `tenantAdmin` membership/role. Missing, stale, malformed, mismatched, or unavailable `membershipVersion` evidence rejects the operation; only the target-tenant generation remains omitted. The route must validate the exact requested `tenantId`; it may remain reachable during a tenant gameplay billing suspension, but never after the caller's membership or role is revoked.
- `cross_tenant_support_safe`: require current issuer and account generations, the live global `support` role or an explicitly allowed `platformAdmin` role, and global token scope. The target tenant is an exact input to the audited operation, not a membership or generation-map key. The `support` path does not require `privileged_control`; a `platformAdmin` path does.
- `cross_tenant_billing_safe`: require current issuer and account generations, the live global `billingAdmin` role or an explicitly allowed `platformAdmin` role, global token scope, and `privileged_control` assurance. The target tenant is exact, audited, and independently resolved; no tenant membership or target-tenant generation is inferred.

`cross_tenant_data_bearing` is not in the omission allowlist. A `platformAdmin` request in that class must bind and validate the current target-tenant generation just like a target-specific `tenant_regular` operation. `pending_deletion_scoped` is a separate no-target-tenant classification, not a member of this omission allowlist: its dedicated account/deletion-workflow credential and authority define its checks, and no target-tenant generation applies. Other no-target classifications are likewise outside this tenant-bearing omission set. A route may omit target generation only when its exact classification is one of the three allowlisted classes above or it is separately classified as having no target tenant; a role name, service name, or “cross-tenant” label cannot create an exception.

Every other tenant-bearing classification, including a newly introduced classification, requires tenant generation according to its route declaration. The allowlist is not inherited by route variants, internal callers, or operator references. Negative proof is performed separately for each route classification, not only for a shared middleware path: it must show that a tenant-generation advance denies `tenant_regular`, `cross_tenant_data_bearing`, and gameplay/admission routes, while each allowlisted class is denied when its own issuer/account, membership, live role, exact target-tenant binding, global scope, or required assurance predicate is absent, stale, mismatched, or unavailable. The proof must respect intentionally omitted predicates: `billing_safe_tenant` omits only target-tenant generation while requiring exact `membershipVersion` alongside caller membership generation and live `current_operator_roles`, and rejects missing, stale, or unavailable version evidence; `cross_tenant_support_safe` omits membership, target-tenant generation, and `privileged_control` on its support branch; `cross_tenant_billing_safe` omits membership and target-tenant generation but requires `privileged_control`; and `pending_deletion_scoped` uses its separate no-target credential contract rather than inheriting this allowlist. An intentionally omitted predicate is not converted into a required check or a hidden authority source, while any predicate declared applicable by the classification remains mandatory.

### Advancing Authority

- Account advances the applicable durable generation and `issuanceFence` in the same database transaction as the security, membership, role, billing, or issuer-authority change and its outbox/revocation evidence when the fence applies.
- The Redis projection is idempotent and set-if-greater. Delayed or replayed older events cannot regress a generation.
- A cutoff workflow does not report enforcement complete until the new generation is projected and its downstream cutoff obligations have reached their declared bound.
- Issuance and generation advancement must use Account-owned transactional ordering for the affected authority so a token cannot capture an old generation after the authority change has linearized.
- Private-token replacement issuance and account-wide logout/security cutoff lock or compare the same durable account generation and applicable `issuanceFence` in Account SQL. Logout advances those values in the same transaction as its audit/outbox state; replacement issuance commits only if the generation and fence validated at refresh authorization are still current, so a refresh cannot linearize across logout and resurrect authority.
- Per-token logout remains deletion of the one record from ADR 0035. Bulk revocation never depends on token-key scans or deletion.

### Gameplay Boundary

- Every new admission and protected route that uses a registry-backed Account JWT, including reconnect/resume/rebind, token issuance/refresh, and control-plane requests, compares the current applicable generations and remains default-deny; a committed generation advance therefore denies those operations immediately at their required authority check, with no stale cache, lease, or route label exception. In-band `PLAY` does not use generic JWT registry/generation middleware: it performs its explicit bound-session membership, lease, entitlement, routing, and authority-freshness admission checks. The `gameplay-connect` handshake and Gateway-signed connect context are excluded from these Account generation/evidence reads and follow ADR 0029 and ADR 0024 as stated above. Routine gameplay commands add no Account or generation reads, and active-gameplay outage behavior remains governed by ADR 0037.
- Routine gameplay commands do not read generation state. An already-admitted binding may continue during only the ADR 0037 token-authority-only outage exception when it has prior-positive Account authority and an unexpired ADR 0030 authority-freshness lease of no more than 60 seconds, together with the required live coordination-health predicate; new admission, protected routes, reconnect/resume/rebind, scope expansion, refresh, and lease renewal remain denied. Account events are the fast cutoff path for active bindings, while ADR 0030's periodic batched reconciliation bounds missed-event authority to 60 seconds without a per-command read.
- Bulk revocation capacity is scope-specific. The issuer, account, tenant, and caller-bound membership generation cutoffs each require their own finite worst-case active/provisional binding bound, partition-capacity proof, fenced termination throughput, and deployment-validated `<= 60 seconds` termination equation. Private-realm grant cutoffs use the same separate proof under ADR 0030. A passing issuer proof does not establish capacity for any other scope; if any applicable scope cannot prove its bound, admission or the affected cutoff is rejected or backpressured rather than relying on an unbounded queue.

## Consequences

- Same-second and cross-replica clock ambiguity is removed from revocation correctness.
- Token validation performs the same classes of current-authority reads the timestamp design required; integer comparison has negligible cost.
- Tokens with multi-tenant scoped claims carry corresponding bounded generation metadata, increasing token size in proportion to the tenant scopes they already contain.
- Account must durably maintain and correctly project four generation families, including non-regression and issuance/revocation ordering proof.
- Generation projections are rebuildable security state derived from Account's durable authority. Redis loss causes fail-closed reauthentication/rebuild behavior rather than silently accepting stale tokens.

## Alternatives Considered

### Keep UTC Epoch-Second Cutoffs

This is simple and directly comparable with `iat`, but no comparison operator correctly orders issuance and revocation within the same second.

### Use Millisecond Or Nanosecond Timestamps

Higher precision makes collisions less likely but preserves replica-clock ordering and skew as correctness dependencies.

### Scan And Delete Every Token

Scanning makes cutoff latency and correctness depend on key enumeration, races with concurrent issuance, and does not provide a bounded hot-path mechanism.

### Rely Only On Short Token Expiry

Short expiry avoids revocation state but cannot meet immediate account-security, membership-loss, tenant-suspension, or issuer-compromise requirements.

## Implementation and Proof Obligations

- Add durable issuer/account/tenant/membership generation columns or records and transactional increment APIs under Account ownership.
- Define bounded token claims and shared typed accessors for captured generations.
- Replace `session:auth:revoked_after:*` timestamp projections and comparisons directly in this pre-v1 system; do not retain dual timestamp/generation authority.
- Prove same-second issuance/revocation ordering, concurrent issuance and generation advancement, set-if-greater replay, missing state, reset/rebuild, and multi-tenant target-scope validation.
- Prove the closed route-class omission set independently per classification: tenant-generation advances deny every tenant-bearing route outside the exact three-class allowlist; the explicit `tenant_regular` `platformAdmin` branch requires the route-declared `control-ui` profile, current issuer/account/global role, exact target scope, target-tenant generation, and active `privileged_control` while requiring no membership, and tenant-role branches require live membership and membership generation; `billing_safe_tenant` still requires issuer, account, exact membership generation and `membershipVersion`, exact tenant binding, and the canonical live `current_operator_roles` check proving `tenantAdmin`, rejecting missing, stale, or unavailable version evidence while intentionally omitting only target-tenant generation; support-safe routes reject missing issuer/account/current global role and reject support's use of billing or data-bearing routes without adding `privileged_control` to the support branch; billing-safe cross-tenant routes reject missing issuer/account/global billing role, wrong target scope, and missing `privileged_control` while intentionally omitting membership and target-tenant generation; `pending_deletion_scoped` is separately proven as a no-target classification and is not treated as an allowlist member. Add negative tests proving a newly named class or route cannot inherit an allowlist entry, proving that intentionally omitted predicates do not become implicit requirements, and proving that initial `AuthLogout`/`AuthLogoutAll` authorization performs exactly one evidence-bundle read while exact locally validated stored-result/tombstone retries perform no new registry or bundle read and create no authorization context.
- Preserve the no-per-command gameplay-read boundary and prove the active-session 60-second reconciliation limit separately, including the bounded active-gameplay outage behavior in ADR 0037.
- Prove the separate bounded termination capacity for every issuer, account, tenant, caller-bound membership, and private-realm grant revocation scope; the proof must cover all affected Game Session partitions and Gateway termination work and must fail deployment or backpressure admission when any scope cannot satisfy the `<= 60 seconds` equation in ADR 0030.

## Required Documentation Alignment

- [Authentication and authorization](../system-architecture-authentication.md)
- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Redis architecture](../system-architecture-redis.md)

## Reversibility and Revisit Triggers

The generation claims and Redis values are versioned integers and can be widened without changing public gameplay protocols. Revisit only if Account token authority moves to an external identity/session provider or a different ordered revocation primitive can provide the same scope-specific cutoff and proof with lower operational cost.
