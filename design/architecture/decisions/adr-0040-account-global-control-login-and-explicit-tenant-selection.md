# ADR 0040: Account-Global Control Login and Explicit Tenant Selection

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.2` Account and tenant control-plane access
- Affected capabilities: `AA-1.1`, `EA-3.3`, `PO-1.1`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-CONTROL-LOGIN-SCOPE`

## Context

An account may hold different roles in multiple tenants and global platform roles outside any tenant. Existing examples mixed a `tenantId` into control-plane login even though the response already carried account-global identity and multi-tenant scoped roles. Treating login as tenant-scoped would require token replacement or parallel tokens when a user switches tenants, uses multiple tabs, or performs an explicit global operation.

## Decision

- `/auth/login` authenticates the global platform account and issues the exact `control-ui` token profile. Its request does not accept an authoritative `tenantId`.
- A selected tenant is first-party UI navigation state, not token scope or authorization evidence. Switching tenants does not require reauthentication or token replacement.
- Every tenant-targeted request names its target tenant in the route or request contract and independently validates the exact token profile, account subject, scoped roles, current membership and authority generations, and route class.
- Global-role actions use explicit global or cross-tenant routes. They never inherit authority from the UI's currently selected tenant.
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
- Require every tenant-targeted route to carry its target explicitly and prove cross-tenant, stale-generation, wrong-profile, and insufficient-role rejection.
- Keep global actions on explicit route classes with separately tested global-role policy.
- Treat tenant selection as untrusted presentation state in first-party clients and preserve independent `player-bootstrap` issuance for gameplay.
- Test multi-tab tenant switching without token replacement or authority leakage.

## Reversibility and Revisit Triggers

Tenant-scoped delegated sessions could be added later for unusually sensitive operations without redefining ordinary login. Revisit if control-token size becomes material, an external identity provider changes session issuance, or measured cross-tenant control risk warrants short-lived step-up tokens for a narrow action class.
