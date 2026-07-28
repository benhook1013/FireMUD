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
- Missing, malformed, unavailable, stale, or regressed generation state never means generation zero. A Redis projection is usable only when its source-generation/freshness evidence is confirmed against the current durable Account authority; otherwise protected operations fail closed until that authority is established or the projection is rebuilt.

### Token Capture And Validation

- Every revocable Account JWT captures the current issuer and account generations when issued.
- A token containing tenant-scoped authority also captures the current tenant and membership generations for exactly the bounded tenant entries already present in its scoped claims. Generation metadata must not introduce scopes absent from those claims.
- A global `platformAdmin` token does not acquire tenant claims or membership generations. For an explicitly matrix-allowlisted `tenant_regular` operational/control-plane route, and for `cross_tenant_data_bearing` routes that operate on one target tenant, Account reads the current target-tenant generation while authorizing the request and binds it into the bounded operator authorization reference; the owner redeems that exact reference and rejects it if the target-tenant generation has changed. This is a target-scope freshness fence, not tenant membership, and no caller-bound membership is invented or bypassed. The explicitly safe `cross_tenant_support_safe` and `cross_tenant_billing_safe` classes omit the target-tenant generation only under the closed allowlist below; they still require exact target scope, live global role/assurance, and audit. `platformAdmin` is not a caller-bound substitute for `billing_safe_tenant` and cannot use global role alone for gameplay admission or switching.
- After cryptographic/profile validation and the issued-token registry check, a consumer confirms that each applicable projection is fresh against durable Account authority, then compares the token's captured generations with the current applicable projections. Missing, stale, unavailable, or unverifiable freshness evidence fails closed; every generation required for the route must match exactly.
- A mismatch revokes that token's authority at the mismatched scope. Tenant-generation omission is not a general route exception: the only allowed route classifications are the closed allowlist below, and each still requires the listed issuer, account, global-role, or membership checks. A route is never exempt merely because a caller or service labels it billing-safe or support-safe.
- JWT `iat` remains a required audit and lifetime claim but is not bulk-revocation ordering authority.

### PlatformAdmin Tenant-Regular Branch

The `platformAdmin` branch of `tenant_regular` is enforceable only when the route-matrix entry explicitly declares the `tenant_regular` classification, the `control_ui_plus_current_role_and_role_appropriate_assurance` auth path, `accepted_token_profiles: [control-ui]`, `role_assurance: privileged_control_when_global_role`, and the operator branch fields below. The branch requires all of the following, conjunctively:

- the exact `control-ui` token profile and audience, a matching issued-token registry record, current issuer and account generations, and a live current global role of `platformAdmin`;
- an active server-side `privileged_control` assurance window backed by recent ordinary reauthentication and independent TOTP, bound to the current `control-ui` token `jti`, account generation, requested global role, and target operation;
- the exact requested target `tenantId` from the route or typed request, with `target_tenant_generation` bound and validated as the freshness fence, and `global_platform_admin_reference_generation_binding: target_tenant_generation` on the route entry; and
- no caller-bound tenant membership or membership-generation requirement, because this branch has no tenant membership authority. `tenantAdmin`/other tenant-role branches instead require live membership and membership-generation checks, while `support` and `billingAdmin` are rejected for `tenant_regular`.

The route entry must also declare `membership_authority_generation: conditional_by_operator_role` with `tenant_role: true` and `platformAdmin_global: false`, `global_platform_admin_membership_required: false`, and a role set that explicitly includes `platformAdmin`. Its required live checks must include `current_operator_roles`, `current_global_role`, `role_appropriate_assurance`, and `target_tenant_generation` (plus any route-specific domain checks). The tenant-role branch uses the same route entry only when its `tenant_role` predicate is true and must instead satisfy live membership and membership-generation checks.

This branch never authorizes gameplay admission, gameplay switching, or any route whose matrix entry does not explicitly allow the override. A global role, selected UI tenant, scoped-role omission, issuer/account generation, or account-only assurance cannot substitute for the exact target-tenant generation or the required `privileged_control` window.

### Explicit Route-Class Generation Allowlist

Tenant generation applies by default. Only these canonical route classifications may omit the target tenant generation, and their other authority checks are mandatory:

- `billing_safe_tenant`: require current issuer and account generations, the caller-bound `{accountId, tenantId}` membership generation, and a live `tenantAdmin` membership/role check. The route must validate the exact requested `tenantId`; it may remain reachable during a tenant gameplay billing suspension, but never after the caller's membership or role is revoked.
- `cross_tenant_support_safe`: require current issuer and account generations, the live global `support` role or an explicitly allowed `platformAdmin` role, and global token scope. The target tenant is an exact input to the audited operation, not a membership or generation-map key. The `support` path does not require `privileged_control`; a `platformAdmin` path does.
- `cross_tenant_billing_safe`: require current issuer and account generations, the live global `billingAdmin` role or an explicitly allowed `platformAdmin` role, global token scope, and `privileged_control` assurance. The target tenant is exact, audited, and independently resolved; no tenant membership or target-tenant generation is inferred.

`cross_tenant_data_bearing` is not in the omission allowlist. A `platformAdmin` request in that class must bind and validate the current target-tenant generation just like a target-specific `tenant_regular` operation. A route may omit target generation only when its exact classification is one of the three allowlisted classes above; a role name, service name, or “cross-tenant” label cannot create an exception.

Every other tenant-bearing classification, including a newly introduced classification, requires tenant generation according to its route declaration. The allowlist is not inherited by route variants, internal callers, or operator references. Negative proof must demonstrate that a tenant-generation advance denies `tenant_regular` and gameplay/admission routes, while each allowlisted class is still denied when its issuer/account, membership, live role, exact target-tenant binding, global scope, or required assurance predicate is absent, stale, mismatched, or unavailable.

### Advancing Authority

- Account advances the applicable durable generation in the same database transaction as the security, membership, role, billing, or issuer-authority change and its outbox event.
- The Redis projection is idempotent and set-if-greater. Delayed or replayed older events cannot regress a generation.
- A cutoff workflow does not report enforcement complete until the new generation is projected and its downstream cutoff obligations have reached their declared bound.
- Issuance and generation advancement must use Account-owned transactional ordering for the affected authority so a token cannot capture an old generation after the authority change has linearized.
- Private-token replacement issuance and account-wide logout/security cutoff lock or compare the same durable account-auth generation in Account SQL. Logout advances that generation in the same transaction as its audit/outbox state; replacement issuance commits only if the generation validated at refresh authorization is still current, so a refresh cannot linearize across logout and resurrect authority.
- Per-token logout remains deletion of the one record from ADR 0035. Bulk revocation never depends on token-key scans or deletion.

### Gameplay Boundary

- Protected control-plane and admission operations compare current applicable generations; reads may be pipelined or batched but remain default-deny.
- Routine gameplay commands do not read generation state. Account events are the fast cutoff path for active bindings, while ADR 0030's periodic batched reconciliation bounds missed-event authority to 60 seconds.

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
- Prove the closed route-class allowlist: tenant-generation advances deny every non-allowlisted route; the explicit `tenant_regular` `platformAdmin` branch requires the route-declared `control-ui` profile, current issuer/account/global role, exact target scope, target-tenant generation, and active `privileged_control` while requiring no membership, and tenant-role branches require live membership and membership generation; `billing_safe_tenant` still requires issuer, account, membership generation, exact tenant binding, and live `tenantAdmin`; support-safe routes reject missing issuer/account/current global role and reject support's use of billing or data-bearing routes; billing-safe cross-tenant routes reject missing issuer/account/global billing role, wrong target scope, and missing `privileged_control`. Add negative tests proving a newly named class or route cannot inherit an allowlist entry.
- Preserve the no-per-command gameplay-read boundary and prove the active-session 60-second reconciliation limit separately.

## Required Documentation Alignment

- [Authentication and authorization](../system-architecture-authentication.md)
- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Redis architecture](../system-architecture-redis.md)

## Reversibility and Revisit Triggers

The generation claims and Redis values are versioned integers and can be widened without changing public gameplay protocols. Revisit only if Account token authority moves to an external identity/session provider or a different ordered revocation primitive can provide the same scope-specific cutoff and proof with lower operational cost.
