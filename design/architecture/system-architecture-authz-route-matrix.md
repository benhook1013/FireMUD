# FireMUD System Architecture: Authorization Route Matrix

This document is the normative source of truth for protected route classification across HTTP and gRPC APIs.

Every protected route must be listed here with:

- route identifier (service + method/path),
- classification (`public`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`),
- required allowlist scope,
- required role checks,
- tenant watermark applicability.

Services must enforce these classifications through shared middleware annotations/interceptors and CI policy checks. Routes that are protected but missing from this matrix are considered an architectural violation.

## Implemented Status

- Target state: every protected route is listed and CI fails for missing/unknown classifications.
- Current rollout is phase-driven (see Enforcement Rollout Phases below). During Phase 2, pre-existing legacy gaps may remain warning-only via an explicit, expiring allowlist. This is transitional governance only and does not change the target-state contract.

## Governance (Required)

- **Owner**: Platform Security + Account Service maintainers jointly own this matrix.
- **Machine-readable source**: `design/architecture/system-architecture-authz-route-matrix.yaml` is the enforcement source of truth; this Markdown file is the human-readable companion.
- **CI enforcement**:
  - Fail if a protected route is not present in the YAML matrix.
  - Fail if a route uses an unknown classification value.
  - Fail if a route is marked billing- or support-safe but lacks required redaction/authorization tests.
- **Default-deny classification**:
  - Any protected route that cannot be classified deterministically must be treated as `tenant_regular` (tenant watermark applies) until explicitly reviewed and added to the matrix.
  - No route may default to a billing-safe or support-safe class.
- **Change control**:
  - `billing_safe_tenant`, `cross_tenant_support_safe`, and `cross_tenant_billing_safe` changes require explicit security review approval.

### Enforcement Rollout Phases

To avoid breaking active development while still reaching strict governance, matrix enforcement follows an explicit phase timeline:

| Phase | CI behavior | Target completion |
| --- | --- | --- |
| Phase 1: visibility | Emit warnings for unclassified protected routes and unknown classes; do not fail merges. | Completed |
| Phase 2: guardrail | Fail CI for new/changed protected routes that are missing matrix entries or use unknown classes; existing legacy gaps stay warning-only while backlog is burned down. | March 31, 2026 |
| Phase 3: full enforcement | Fail CI for any protected route missing from the matrix, regardless of change scope. | June 30, 2026 |

During Phase 2, the allowlist of legacy warning-only gaps must be version-controlled and reduced over time; adding new entries to that allowlist requires security-owner approval and an expiration date.

### Critical-Domain Override (Effective Immediately)

Regardless of phase timeline above, the following domains are **full-fail now** in CI:

- Authentication/session admission routes (`LOGIN`/`PLAY` surfaces and equivalents).
- Billing-safe and support-safe routes.
- Subscription mutation and entitlement routes.

For these domains, protected routes missing from the YAML matrix must fail CI immediately (including legacy routes), and warning-only backlogs are not permitted.

CI should generate candidate inventories from OpenAPI/proto definitions and compare them against the YAML matrix so protected-route drift is detected automatically.

Critical-domain inventory artifacts (required):

- CI must persist generated candidate inventories for auth/session and billing/support domains (OpenAPI + proto derived) under version control (for example `design/architecture/authz-inventory/*.json`).
- Full-fail assertions in critical domains are valid only when these generated inventories are present and compared against the YAML matrix in the same run.
- Inventory generation must distinguish tenant-scoped and cross-tenant route variants explicitly; mixed-scope APIs must not be represented as a single ambiguous route key.

## Classification Rules

| Classification | Required allowlist | Tenant watermark applied? | Notes |
| --- | --- | --- | --- |
| `public` | none | No | No JWT required |
| `tenant_regular` | `account` + `tenant` | Yes | Gameplay-affecting and regular tenant control-plane operations |
| `billing_safe_tenant` | `account` | No | Must remain reachable during `suspended`/`canceled` |
| `cross_tenant_support_safe` | `account` + `global` | No | High-level troubleshooting only |
| `cross_tenant_billing_safe` | `account` + `global` | No | Billing operations for global billing roles |
| `cross_tenant_data_bearing` | `account` + `global` | Yes when operation targets tenant-scoped data | Platform-admin-only data-bearing operations |

## Seed Matrix (Current Required Entries)

| Service | Route | Classification | Required roles/capability |
| --- | --- | --- | --- |
| Account Service | `GetTenantEntitlements(tenantId)` tenant-scoped variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetTenantEntitlements(tenantId)` cross-tenant variant | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `GetSubscription(tenantId)` tenant-scoped high-level variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetSubscription(tenantId)` cross-tenant high-level variant | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptions` tenant-scoped high-level variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ListSubscriptions` support-safe cross-tenant high-level variant | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptions` billing-reports cross-tenant variant | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | `IssueConnectToken` | `tenant_regular` | Tenant-scoped role required; entitlement-gated |
| Account Service | `GetTenantMembership(accountId, tenantId)` tenant-scoped variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetTenantMembership(accountId, tenantId)` cross-tenant variant | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | invoice/payment method APIs tenant-scoped variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | invoice/payment method APIs cross-tenant variant | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Game Session Service | gameplay admission (`PLAY`, instance start/restart/stop control-plane routes) | `tenant_regular` | Tenant role required; entitlement-gated |

The matrix should be expanded as service API surfaces evolve. Service docs may include local excerpts, but this file is the canonical list used by governance checks.
