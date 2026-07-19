# FireMUD System Architecture: Authorization Route Matrix

This document is the normative source of truth for protected route classification across HTTP and gRPC APIs.

Every protected route must be listed here with:

- route identifier (service + method/path),
- classification (`public`, `account_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `public_production_onboarding`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`),
- whether a matching Account issued-token registry record is required,
- required role checks,
- tenant-billing generation applicability,
- caller-bound membership generation applicability where relevant,
- required live authority checks for the route class,
- any response-profile or mutation-contract requirements needed for CI/security enforcement.

Services must enforce these classifications through shared middleware annotations/interceptors and CI policy checks. Routes that are protected but missing from this matrix are considered an architectural violation.

## Implementation Status

- The static Gateway route catalog and bounded internal/actuator blockers provide partial edge-exposure enforcement.
- CI inventory generation, YAML completeness comparison, matrix-aware shared middleware, strict token-profile enforcement, and exact proof for remaining broad Gateway route families are not implemented. Until they are, this target contract must not be reported as fully proven.

## Governance (Required)

- **Owner**: Platform Security + Account Service maintainers jointly own this matrix.
- **Machine-readable source**: `design/architecture/system-architecture-authz-route-matrix.yaml` is the enforcement source of truth; this Markdown file is the human-readable companion.
- **CI enforcement**:
  - Fail if a protected route is not present in the YAML matrix.
  - Fail if a route uses an unknown classification value.
  - Fail if a route is marked billing- or support-safe but lacks required redaction/authorization tests.
  - Fail if generated route inventory (OpenAPI/proto) differs from the YAML matrix for auth/session and billing/subscription domains.
- **Default-deny behavior**:
  - Any protected route that cannot be classified deterministically must be rejected until explicitly reviewed and added to the matrix.
  - No route may default to `tenant_regular`, billing-safe, support-safe, or another executable class.
- **Change control**:
  - `billing_safe_tenant`, `cross_tenant_support_safe`, and `cross_tenant_billing_safe` changes require explicit security review approval.

### Full-Fail Domains (Required)

The following domains are always full-fail in CI:

- Authentication/session admission routes (`LOGIN`/`PLAY` surfaces and equivalents).
- Billing-safe and support-safe routes.
- Subscription mutation and entitlement routes.

For these domains, protected routes missing from the YAML matrix must fail CI immediately, including pre-existing routes.

CI should generate candidate inventories from OpenAPI/proto definitions and compare them against the YAML matrix so protected-route drift is detected automatically.

Critical-domain inventory artifacts (required):

- CI must persist generated candidate inventories for auth/session and billing/support domains (OpenAPI + proto derived) under version control (for example `design/architecture/authz-inventory/*.json`).
- Full-fail assertions in critical domains are valid only when these generated inventories are present and compared against the YAML matrix in the same run.
- Inventory generation must distinguish tenant-scoped and cross-tenant route variants explicitly; mixed-scope APIs must not be represented as a single ambiguous route key.

## Classification Rules

| Classification | Required issued-token state | Tenant watermark applied? | Notes |
| --- | --- | --- | --- |
| `public` | none | No | No JWT required |
| `account_scoped` | One matching token record | No | Account-level control-plane routes with subject binding (`accountId == caller`), plus explicit route-level admin overrides |
| `player_bootstrap_tenant` | One matching token record | No tenant-billing generation; No membership generation | Player-bootstrap-authenticated routes targeting a tenant before gameplay socket auth is complete (for example `POST /auth/connect-token`); must declare live membership, entitlement, and admission-pointer checks |
| `pre_tenant_discovery` | One matching token record | No | Authenticated discovery surfaces that run before a single `tenantId` is selected (for example `WORLDS`) |
| `public_production_onboarding` | One matching token record after authentication | No tenant-billing generation before join; membership generation applies after join | Discovery and explicit open-enrollment join for the default public production realm. Brand-new authenticated accounts may discover it before membership exists, but `JOIN`/`Join & Play` creates the durable Account-owned membership before character creation, connect-token issuance, or `PLAY`; non-public realms require Account-owned grants |
| `tenant_regular` | One matching token record | Tenant-billing generation: Yes; membership generation: Yes | Gameplay-affecting and regular tenant control-plane operations |
| `billing_safe_tenant` | One matching token record | Tenant-billing generation: No; membership generation: Yes | Must remain reachable during `suspended`/`canceled`, but must fail immediately after caller-bound membership/role revocation |
| `cross_tenant_support_safe` | One matching token record | No | High-level troubleshooting only |
| `cross_tenant_billing_safe` | One matching token record | No | Billing operations for global billing roles |
| `cross_tenant_data_bearing` | One matching token record | Yes when operation targets tenant-scoped data | Platform-admin-only data-bearing operations |

Internal-service routes must additionally declare their **service caller policy** in the machine-readable matrix:

- whether the route is callable only by specific service identities,
- whether an end-user issued-token record and scope authorization are still evaluated on behalf of a delegated subject, and
- which token profile/audience the caller must present.

Without these fields, a route classification is incomplete for internal-only APIs.

Critical routes may also require explicit machine-readable fields for:

- `membership_generation_applies`
- `tenant_billing_generation_applies`
- `required_live_checks` such as `membership`, `runtime_entitlements`, `admission_pointer`
- `mutation_contract` such as `shared_instrument_ack_required`
- `canonical_errors` that CI and contract tests must expect for route-specific security rejections

Without these fields where applicable, a route entry is incomplete for governance and CI enforcement.

Route authorization never becomes in-game elevation. If a global-role account passes the ordinary caller-bound join and admission flow, gameplay presence, command, and actor-capability resolution ignore its global roles. Any moderator, administrator, game-master, or equivalent gameplay authority requires an explicit tenant-scoped gameplay grant; no route classification creates a support impersonation or hidden-observer session.

## Seed Matrix (Current Required Entries)

| Service | Route | Classification | Required roles/capability |
| --- | --- | --- | --- |
| Game Session Service | `LOGIN` / `LOGON` | `public` | Credential entrypoint only; no JWT required |
| Game Session Service | `WORLDS` | `pre_tenant_discovery` | Authenticated account discovery only; no pre-existing tenant role is required. Tenant visibility is derived server-side from membership, public-production visibility, and entitlement state; global roles do not widen gameplay discovery |
| Game Session Service | `REALMS` | `public_production_onboarding` | Visible realms for a selected world; no pre-existing tenant role is required. The default public production realm may be discoverable before membership exists, while additional realms still require explicit Account Service grant authority |
| Game Session Service | `JOIN` | `public_production_onboarding` | Explicit caller-bound open-enrollment action for the current public production realm; Account commits durable membership plus audit/outbox idempotently |
| Game Session Service | `CHARS` / `PLAY` | `public_production_onboarding` | Requires an existing caller-bound membership plus any non-public realm grant and current entitlements. Missing public-game membership returns `JOIN_REQUIRED`; `PLAY` never creates it. First-party `/ws/game/**` `PLAY` also enforces connect-context scope |
| Entity Management Service | `POST /characters` | `public_production_onboarding` | Bootstrap-authenticated character creation requires an existing caller-bound membership plus applicable realm visibility/grant and runtime entitlement checks |
| Game Session Service | `StartSession` / `RestartSession` / `StopSession` / `RefreshRoles` | `tenant_regular` | `tenantAdmin`/`platformAdmin` |
| Account Service | `AuthLogin` | `public` | Browser auth entrypoint |
| Account Service | `PlayerBootstrapLogin` | `public` | First-party gameplay bootstrap entrypoint; issues `player-bootstrap` token profile only |
| Account Service | `JoinPublicProductionMembership` | `public_production_onboarding` | Explicit caller-bound `Join & Play` action for a discovery-selected public production realm; transactional membership and durable audit/outbox |
| Account Service | `AuthLogout` / `AuthLogoutAll` | `account_scoped` | Authenticated account scope |
| Account Service | `GetProfile` / `UpdateProfile` (`/profiles/{accountId}`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only |
| Account Service | `ExportAccount` / `DeleteAccount` / `LinkExternalAccount` (`/accounts/{accountId}/...`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only. `DeleteAccount` also requires no nonterminal owned subscriptions |
| Account Service | `IssueConnectToken` | `player_bootstrap_tenant` | Caller-bound player-bootstrap auth only; global roles alone never grant gameplay admission or connect-token issuance. Live tenant membership, runtime entitlement, and admission-pointer checks remain required |
| Account Service | `GetTenantEntitlementsForRuntime` | `tenant_regular` | Internal gameplay/runtime caller only; not edge exposed |
| Account Service | `GetTenantEntitlementsTenant` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetTenantEntitlementsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `GetSubscriptionTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ExportTenantData` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped); tenant-bounded export only |
| Account Service | `GetSubscriptionCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ListSubscriptionsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsCrossTenantBillingSafeReports` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | `GetCallerTenantMembershipTenant` | `billing_safe_tenant` | `tenantAdmin` (subject bound to caller); caller-bound membership generation applies |
| Account Service | `GetTenantMembershipForAccountCrossTenant` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | invoice/payment method APIs tenant-scoped variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped); shared-instrument acknowledgement contract required when mutation affects account-wide payment instrument |
| Account Service | invoice/payment method APIs cross-tenant variant | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |

### Public Production Onboarding Example

1. A brand-new authenticated account completes `LOGIN` and issues `WORLDS`.
2. `WORLDS` may list the world's default public production realm even though no tenant membership exists yet.
3. `REALMS <world>` returns that default public production realm plus any separately granted additional realms.
4. The player explicitly uses `JOIN <world>` or `Join & Play`; Account atomically creates membership and durable audit/outbox.
5. `CHARS`, character creation, connect-token issuance, and `PLAY` require that membership and never create it implicitly.
6. Global roles alone never bypass this flow or grant gameplay admission/connect-token issuance without the same live checks.

The matrix should be expanded as service API surfaces evolve. Service docs may include local excerpts, but this file is the canonical list used by governance checks.
