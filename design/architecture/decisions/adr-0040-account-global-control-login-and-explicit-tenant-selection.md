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
- Every tenant-bound request names its target tenant in the route or request contract and independently validates the exact token profile, account subject, current issuer/account authority, and explicit target tenant before branch-specific authorization. Membership and membership-generation checks are not common checks; they apply only to the tenant-role branch below.
- `tenant_regular` authorization has two explicit predicates, but branch selection is deterministic rather than a fallback disjunction. The tenant-role predicate requires the route-declared tenant role/capability, live membership in the explicit target tenant, the caller-bound membership authority generation, and fresh target-tenant authority-generation evidence. This complete tenant-role contract applies equally to `tenantAdmin` and `moderator` sensitive actions. The allowlisted `platformAdmin_global` predicate is available only when the route-matrix entry explicitly declares the `platformAdmin` override; it requires the live global role and role-freshness evidence, current issuer/account authority, the explicit target tenant and fresh `target_tenant_generation`, and ADR 0045's bounded elevated proof of recent ordinary reauthentication plus independent TOTP elevation. It does not require or imply target-tenant membership or membership-generation authority. Neither branch may use the UI's selected tenant as evidence, and no other global role inherits this branch.
- The receiver selects the `tenant_regular` branch from a fresh, well-formed live Account global-role result before evaluating either predicate. If that result is unavailable, stale, malformed, mismatched to the account or authority snapshot, or otherwise inconclusive, the request is denied and must not evaluate either branch. If it conclusively proves `platformAdmin_global` present, that branch has precedence even when the caller also has a tenant role: only the `platformAdmin_global` predicate is eligible. If the route does not explicitly allow that override, or the global predicate fails, the request is denied; it must never fall back to the tenant-role predicate. Only a conclusive `platformAdmin_global`-absent result makes the tenant-role branch eligible, and that branch retains the complete live membership, caller-bound membership-generation, target-tenant-generation, and route-role checks, including for `tenantAdmin` and `moderator`. This makes dual-authority behavior deterministic and prevents a stale or unavailable privileged check from changing branch selection.
- Explicit global and cross-tenant routes do not require current membership in every target tenant unless their declared contract says so. They must instead enforce their declared target scope, global role, assurance requirement, and current Account-owned issuer/account/target authority generations under [ADR 0036](./adr-0036-monotonic-authority-generations-for-bulk-token-revocation.md) and the route matrix. Target-tenant-generation omission eligibility is owned exclusively by ADR 0036's closed allowlist rather than a second list in this ADR; any cross-tenant class not admitted there, including `cross_tenant_data_bearing` and newly introduced classes, requires an exact target-tenant generation and fail-closed freshness validation.
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
- Require every tenant-targeted route to carry its target explicitly and prove the separate tenant-role and allowlisted `platformAdmin` `tenant_regular` predicates, including membership-generation, target-tenant-generation, stale-generation, wrong-profile, and insufficient-role rejection.
- Keep global actions on explicit route classes with separately tested global-role policy.
- Treat tenant selection as untrusted presentation state in first-party clients and preserve independent `player-bootstrap` issuance for gameplay.
- Test multi-tab tenant switching without token replacement or authority leakage.

## Required Documentation Alignment

- [Authentication and authorization](../system-architecture-authentication.md)
- [JWT and token contracts](../system-architecture-jwt-and-token-contracts.md)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)

## Reversibility and Revisit Triggers

Tenant-scoped delegated sessions could be added later for unusually sensitive operations without redefining ordinary login. Revisit if control-token size becomes material, an external identity provider changes session issuance, or measured cross-tenant control risk warrants short-lived step-up tokens for a narrow action class.
