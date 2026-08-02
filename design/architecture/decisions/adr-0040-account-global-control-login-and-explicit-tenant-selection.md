# ADR 0040: Account-Global Control Login and Explicit Tenant Selection

## Status

Accepted

## Implementation Status

`/auth/login` is tenantless and issues an audience-bound `control-ui` token through the account-global session path. The canonical single-record issued-token registry, authority-generation claims, multi-tenant scoped-role population, and full tenant-switching proof remain incomplete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.2` Account and tenant control-plane access
- Affected capabilities: `AA-1.1`, `EA-3.3`, `PO-1.1`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-CONTROL-LOGIN-SCOPE`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-AA-CONTROL-LOGIN-SCOPE`

## Context

An account may hold different roles in multiple tenants and global platform roles outside any tenant. Existing examples mixed a `tenantId` into control-plane login even though the response already carried account-global identity and multi-tenant scoped roles. Treating login as tenant-scoped would require token replacement or parallel tokens when a user switches tenants, uses multiple tabs, or performs an explicit global operation.

## Decision

- `/auth/login` authenticates the global platform account and issues the exact `control-ui` token profile. Its request does not accept an authoritative `tenantId`.
- A selected tenant is first-party UI navigation state, not token scope or authorization evidence. Switching tenants does not require reauthentication or token replacement.
- Every tenant-bound request names its target tenant in the route or request contract and independently validates the exact token profile, account subject, current issuer/account authority, and explicit target tenant before applying the route matrix. The matrix, not this ADR, defines the route class and whether the route requires live target membership, the applicable membership authority generation, and independent `membershipVersion`. This ADR creates no universal `platformAdmin` tenant access and the UI's selected tenant is never authorization evidence. All applicable predicates are evaluated from one immutable `account-auth-evidence-bundle/v1` at one Account linearization point, carrying the exact `{bundleVersion, sourceVersion, sourceFence, linearization}` reference.
- `tenant_regular` is not a global-role fallback: a route-declared tenant-role or global-role alternative is eligible only when that exact matrix entry permits it and all of its listed predicates pass. A tenant-role predicate requires the route-declared role/capability, live membership in the explicit target tenant, the caller-bound membership authority generation, the independent applicable `membershipVersion`, and fresh target-tenant authority-generation evidence from the same bundle. A route-specific `platformAdmin` alternative, if explicitly declared by the matrix, requires the live global role, current issuer/account and target-tenant authority, and ADR 0045's bounded elevated proof; it does not generalize to other tenant routes and never inherits from UI selection. Branch selection is deterministic rather than a fallback disjunction, so a failed or unavailable privileged branch cannot fall back to a tenant-role branch.
- The receiver selects the `tenant_regular` branch from one fresh, well-formed live Account global-role result in that bundle before evaluating either predicate. If the bundle or its version/source-fence/linearization reference is unavailable, stale, malformed, mismatched to the account or authority snapshot, or otherwise inconclusive, the request is denied and must not evaluate either branch. If it conclusively proves `platformAdmin_global` present, that branch has precedence even when the caller also has a tenant role: only the `platformAdmin_global` predicate is eligible. If the route does not explicitly allow that override, or the global predicate fails, the request is denied; it must never fall back to the tenant-role predicate. Only a conclusive `platformAdmin_global`-absent result makes the tenant-role branch eligible, and that branch retains the complete live membership, caller-bound membership-generation, target-tenant-generation, route-role, and reference-equality checks from the same bundle. Any later role, generation, or operator-reference read that does not match the bundle rejects the request. This makes dual-authority behavior deterministic and prevents a stale or unavailable privileged check from changing branch selection.
- Membership omission is not implied by a global role. Only the route matrix's explicitly allowed `cross_tenant_support_safe` and `cross_tenant_billing_safe` classes may omit target-tenant membership under this ADR, and each must enforce its own declared global role, assurance, scope, and current Account-owned issuer/account authority checks. Support-safe access requires the matrix-approved `support` or `platformAdmin` path with its required assurance; billing-safe access requires the matrix-approved `billingAdmin` or `platformAdmin` path with its required `privileged_control` assurance. `billing_safe_tenant`, `tenant_regular`, `cross_tenant_data_bearing`, and newly introduced classes retain the exact membership and target-generation predicates declared by the matrix. Any class not explicitly admitted there fails closed.
- Global-role actions never inherit authority from the UI's currently selected tenant, and a global role does not implicitly create tenant membership.
- Gameplay authentication remains the separate `player-bootstrap` profile and admission flow.

## Consequences

- Multi-tenant navigation, multiple browser tabs, and global administration do not need a token-switching protocol.
- Tenant authority remains explicit per request; selected UI state cannot accidentally authorize a tenant operation.
- The account-global control token can contain more multi-tenant claims and has a wider control-plane blast radius than a tenant-specific token. Issued-token registry checks and account, tenant, and membership generations bound that risk.
- Large or frequently changing role sets may eventually justify reference-based claims or authoritative lookup rather than embedding every role, without changing the login scope.

## Alternatives Considered

### Tenant-Scoped Control Sessions

This minimizes each token's tenant blast radius, but introduces tenant selection during login, token switching and refresh semantics, multi-tab ambiguity, and a separate path for global administrators.

### Let the Selected Tenant Implicitly Scope Requests

This is convenient for UI code, but makes navigation state an authority input and creates confused-deputy risk when routes, tabs, or cached state disagree.

## Implementation and Proof Obligations

- Remove `tenantId` from the canonical `/auth/login` request and issue only the `control-ui` profile there.
- Require every tenant-targeted route to carry its target explicitly and follow the route matrix's membership, role, generation, and assurance predicates from one exact `account-auth-evidence-bundle/v1` version/source-fence/linearization reference. Prove there is no universal `platformAdmin` tenant override; only an explicitly classified route may use that role, and only the allowed `cross_tenant_support_safe`/`cross_tenant_billing_safe` classes may omit target membership with their own global-role and assurance checks. Prove role revocation or generation advancement after bundle linearization and before predicate or operator-reference use rejects the request without branch fallback, and prove the reverse race cannot issue or redeem a reference from pre-change evidence.
- Keep global actions on explicit route classes with separately tested global-role policy.
- Treat tenant selection as untrusted presentation state in first-party clients and preserve independent `player-bootstrap` issuance for gameplay.
- Test multi-tab tenant switching without token replacement or authority leakage.

## Required Documentation Alignment

- [Authentication and authorization](../system-architecture-authentication.md)
- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)

## Reversibility and Revisit Triggers

Tenant-scoped delegated sessions could be added later for unusually sensitive operations without redefining ordinary login. Revisit if control-token size becomes material, an external identity provider changes session issuance, or measured cross-tenant control risk warrants short-lived step-up tokens for a narrow action class.
