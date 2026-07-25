# FireMUD System Architecture: Authorization Route Matrix

The machine-readable [authorization route matrix](./system-architecture-authz-route-matrix.yaml) is normative for the route entries it contains. It is not yet a complete route inventory: source-stable OpenAPI/protobuf coverage and its comparison validation are incomplete. This document is the human-readable companion and must not define a competing policy.

Every protected route in a validated inventory must be listed here with:

- route identifier (service + method/path),
- classification (`public`, `account_scoped`, `caller_membership_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `public_production_onboarding`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, `internal_workload`),
- whether a matching Account issued-token registry record is required,
- required role checks,
- tenant-billing authority-generation applicability,
- caller-bound membership authority-generation applicability where relevant,
- required live authority checks for the route class,
- any response-profile or mutation-contract requirements needed for CI/security enforcement.

Before that inventory is validated, an unclassified protected or external route must remain conservatively denied or unreachable; this safeguard is not generated policy from the incomplete YAML.

Services must enforce these classifications through shared middleware annotations/interceptors and CI policy checks once the inventory gate is complete. A protected route missing from the current matrix is recorded as authorization drift/gap; before the gate completes it must be denied or unreachable rather than forwarded, and it must not be treated as evidence that the incomplete matrix is a complete canonical registry.

## Implementation Status

- The static Gateway route catalog and bounded internal/actuator blockers provide partial edge-exposure enforcement.
- The YAML is normative for declared entries, but the current route inventory is incomplete. CI inventory generation, source-stable OpenAPI/protobuf coverage, YAML completeness comparison, matrix-aware shared middleware, strict token-profile enforcement, and exact proof for remaining broad Gateway route families are not implemented. Missing coverage is a recorded drift/gap; before source-stable coverage is complete and validated, unclassified protected/external routes remain denied or unreachable, and the incomplete YAML must not generate their policy.

## Token Profile Vocabulary

The current JWT profile names are `control-ui`, `player-bootstrap`, the one-use `gameplay-connect` handshake token, and receiver-specific private player-delegation profiles. `gameplay-connect` uses audience `gameplay-connect` and is consumed by Gateway rather than accepted as gameplay command authorization. The current private delegation profile is `game-session-account-delegation` with audience `account-service`. A generic backend JWT profile and the `internal` audience are forbidden. A privileged-control window is an authorization condition, not a JWT profile.

## Governance (Required)

- **Owner**: Platform Security + Account Service maintainers jointly own this matrix.
- **Machine-readable source**: `design/architecture/system-architecture-authz-route-matrix.yaml` is the normative source for declared entries; this Markdown file is the human-readable companion. It is not a complete route registry until the inventory gate below passes.
- **CI enforcement**:
  - Once source-stable OpenAPI/protobuf inventories cover the governed surfaces and validate in the same run, fail if a protected route is not present in the YAML matrix.
  - Fail if a route uses an unknown classification value.
  - Fail if a route is marked billing- or support-safe but lacks required redaction/authorization tests.
  - Fail if generated route inventory (OpenAPI/proto) differs from the YAML matrix for auth/session and billing/subscription domains.
- **Default-deny behavior**:
  - The declared-entry `default_action: deny` is normative only for entries in the YAML. It does not generate policy for routes absent from this incomplete inventory.
  - Before the inventory gate passes, any discovered but unlisted protected or external route is `drift/gap` and requires explicit review and matrix entry; it remains denied or unreachable. No generated default-deny policy is produced from the incomplete inventory; the conservative deny/unreachable safeguard is an ingress boundary, not an incomplete-YAML policy artifact.
  - After the inventory gate passes, any protected route that cannot be classified deterministically must be rejected until explicitly reviewed and added to the matrix.
  - No route may default to `tenant_regular`, billing-safe, support-safe, or another executable class.
- **Change control**:
  - `billing_safe_tenant`, `cross_tenant_support_safe`, and `cross_tenant_billing_safe` changes require explicit security review approval.

### Critical Domains And Inventory Gate

The following domains become full-fail in CI only after source-stable OpenAPI/protobuf inventories cover them and the same run validates the comparison:

- Authentication/session admission routes (`LOGIN`/`PLAY` surfaces and equivalents).
- Billing-safe and support-safe routes.
- Subscription mutation and entitlement routes.

Before that gate passes, protected routes missing from the YAML matrix are recorded as drift/gap, including pre-existing routes; they remain denied or unreachable, but the incomplete matrix must not generate default-deny behavior for them.

CI should generate candidate inventories from OpenAPI/proto definitions and compare them against the YAML matrix so protected-route drift is detected automatically.

Critical-domain inventory artifacts (required):

- CI must persist generated candidate inventories for auth/session and billing/support domains (OpenAPI + proto derived) under version control (for example `design/architecture/authz-inventory/*.json`).
- Full-fail assertions and generated default-deny policy in critical domains are valid only when these generated inventories are present, source-stable, and compared against the YAML matrix in the same run.
- Inventory generation must distinguish tenant-scoped and cross-tenant route variants explicitly; mixed-scope APIs must not be represented as a single ambiguous route key.

## Classification Rules

| Classification | Required issued-token state | Authority generations applied? | Notes |
| --- | --- | --- | --- |
| `public` | none | No | No JWT required |
| `account_scoped` | One matching token record for the exact profile declared by the route | No | Account-level control-plane routes with subject binding (`accountId == caller`), plus explicit route-level admin overrides |
| `caller_membership_scoped` | One matching token record for the exact profile declared by the route | No tenant authority generation; membership authority generation: Yes | Caller-bound lifecycle operations on the caller's own account-to-tenant membership. Any current membership role may act on itself; the route must perform a live subject-bound membership check and must not accept an arbitrary account target or global-role override |
| `player_bootstrap_tenant` | One matching `player-bootstrap` token record | No tenant authority generation; membership authority generation is route-specific and must be declared explicitly | Player-bootstrap-authenticated routes targeting a tenant before gameplay socket auth is complete. `IssueConnectToken` requires the current membership authority generation plus live membership, entitlement, and admission-pointer checks |
| `pre_tenant_discovery` | In-band gameplay commands may use the already-authenticated Game Session context without presenting a JWT; other routes require the exact token profile they declare | No | Authenticated discovery surfaces that run before a single `tenantId` is selected (for example `WORLDS`). A route using `game_session_authenticated_context` therefore declares `accepted_token_profiles: []`; this does not make the command anonymous. |
| `public_production_onboarding` | No JWT for in-band gameplay commands; otherwise the exact route-declared profile (currently `player-bootstrap` for Account bootstrap writes) | No tenant authority generation before join; membership authority generation applies after join | **Target:** discovery and explicit open-enrollment join for the default public production realm. Brand-new authenticated accounts may discover it before membership exists, but `JOIN`/`Join & Play` creates the durable Account-owned membership before character creation, connect-token issuance, or `PLAY`. **Current drift:** connect-token issuance and text `PLAY` can still create public-production membership implicitly and must converge on `JOIN_REQUIRED`. Grant-backed private/playtest realms validate their grant and any separately required existing membership, skip `JOIN`, and never use this class to create membership. |
| `tenant_regular` | One matching token record for the exact profile declared by the route | Tenant authority generation: Yes; membership authority generation: Yes | Gameplay-affecting and regular tenant control-plane operations |
| `billing_safe_tenant` | One matching token record for the exact profile declared by the route | Tenant authority generation: No; membership authority generation: Yes | Must remain reachable during `suspended`/`canceled`, but must fail immediately after caller-bound membership/role revocation |
| `cross_tenant_support_safe` | One matching token record for the exact profile declared by the route | No | High-level troubleshooting only |
| `cross_tenant_billing_safe` | One matching token record for the exact profile declared by the route | No | Billing operations for global billing roles |
| `cross_tenant_data_bearing` | One matching token record for the exact profile declared by the route | Yes when operation targets tenant-scoped data | Platform-admin-only data-bearing operations |
| `internal_workload` | Route-specific: explicitly `none` or one exact delegated profile | Route-specific | Internal-only RPCs require exact mTLS workload identity and a method caller allowlist, and both constraints must pass. Each entry declares whether it carries delegated subject authority; this class never inherits an end-user token requirement implicitly. |

Internal-service routes must additionally declare their **service caller policy** in the machine-readable matrix:

- whether the route is callable only by specific service identities,
- whether an end-user issued-token record and scope authorization are still evaluated on behalf of a delegated subject, and
- which token profile/audience the caller must present.
- When a route accepts more than one profile, its `accepted_token_profile_audiences` YAML map must bind each accepted profile to its exact audience rather than using an implicit shared audience.

Each `caller_policies` entry is one complete alternative. Alternatives are disjunctive, while the caller identity, mTLS identity, token state/profile, applicable authority generations, delegated context, and live checks inside one entry are conjunctive. A caller name is not sufficient without its exact certificate identity, and an mTLS identity is not sufficient without the method caller policy. When alternatives use different token states, generation-applicability fields belong inside each caller policy rather than at route level.

Without these fields, a route classification is incomplete for internal-only APIs.

Critical routes may also require explicit machine-readable fields for:

- `membership_authority_generation_applies`
- `tenant_billing_authority_generation_applies`
- `required_live_checks` such as `membership`, `membership_generation`, `runtime_entitlements`, `admission_pointer`
- `mutation_contract` such as `shared_instrument_ack_required`
- `canonical_errors` that CI and contract tests must expect for route-specific security rejections
- `realm_grant_authority` and `realm_grant_version` when grant-gated access depends on Account-owned realm authority
- `canonical_target` when the entry records the converged target contract alongside known implementation drift
- `implementation_status` when the current implementation is known not to satisfy that target

Without these fields where applicable, a route entry is incomplete for governance and CI enforcement.

Route authorization never becomes in-game elevation. If a global-role account passes the ordinary caller-bound join and admission flow, gameplay presence, command, and actor-capability resolution ignore its global roles. Any moderator, administrator, game-master, or equivalent gameplay authority requires an explicit tenant-scoped gameplay grant; no route classification creates a support impersonation or hidden-observer session.

## Seed Matrix (Current Required Entries)

| Service | Route | Classification | Required roles/capability |
| --- | --- | --- | --- |
| Game Session Service | `LOGIN` / `LOGON` | `public` | Credential entrypoint only; no JWT required |
| Game Session Service | `WORLDS` anonymous browse | `public` | Public-production catalog only; no account-specific membership, grant, or character information |
| Game Session Service | `WORLDS` authenticated discovery | `pre_tenant_discovery` | No pre-existing tenant role is required. Tenant visibility is derived server-side from membership or public-production visibility plus entitlement state; global roles do not widen gameplay discovery |
| Game Session Service | `REALMS` | `public_production_onboarding` | Visible realms for a selected world; no pre-existing tenant role is required. The default public production realm may be discoverable before membership exists, while additional realms still require explicit Account Service grant authority |
| Game Session Service | `JOIN` | `public_production_onboarding` | Explicit caller-bound open-enrollment action for the current public production realm; Account commits durable membership plus audit/outbox idempotently |
| Game Session Service | `CHARS` | `public_production_onboarding` | Requires an existing caller-bound membership plus any non-public realm grant and current entitlements. It resolves characters for the selected catalog realm but does not apply `PLAY`'s `admission_pointer` live-admission check. Missing public-game membership returns `JOIN_REQUIRED`. |
| Game Session Service | `PLAY` | `public_production_onboarding` | Requires an existing caller-bound membership plus any non-public realm grant, current entitlements, and the current admissible realm pointer. Missing public-game membership returns `JOIN_REQUIRED`; `PLAY` never creates it. First-party `/ws/game/**` also enforces connect-context scope, and unavailable/ambiguous routing returns `ADMISSION_POINTER_UNAVAILABLE`. |
| Account Service | `GET /auth/bootstrap/worlds` | `pre_tenant_discovery` | Accepts only the caller-bound `player-bootstrap` profile; derives visible worlds before a tenant is selected and applies the membership/public-production visibility contract plus live runtime entitlement filtering |
| Account Service | `GET /auth/bootstrap/worlds/{worldSlug}/realms` | `public_production_onboarding` | Accepts only the caller-bound `player-bootstrap` profile; applies live realm visibility and runtime entitlement checks, including public-production visibility or an applicable Account-owned realm-access grant |
| Account Service | `GET /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters` | `public_production_onboarding` | Accepts only the caller-bound `player-bootstrap` profile; requires live membership, applicable realm-grant and entitlement checks, and revalidates the signed `connectScopeId` against the current admission pointer |
| Account Service | `POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters` | `public_production_onboarding` | Bootstrap-authenticated Account facade derives `accountId` from the authenticated caller subject, requires a matching signed discovery `connectScopeId`, existing caller-bound membership, and applicable realm visibility/grant and runtime entitlement checks, then delegates persistence to internal Entity Management `CreateCharacter` |
| Game Session Service | `StartSession` / `RestartSession` / `StopSession` / `RefreshRoles` | `tenant_regular` | `tenantAdmin`/`platformAdmin` |
| Account Service | `AuthLogin` | `public` | `control-ui` auth entrypoint |
| Account Service | `PlayerBootstrapLogin` | `public` | First-party gameplay bootstrap entrypoint; issues `player-bootstrap` token profile only |
| Account Service | `EnsurePublicProductionPlayerMembership` | `public_production_onboarding` | Current Account proto RPC name for the caller-bound public-production membership seam; target behavior is an explicit `JOIN`/`Join & Play` action with transactional membership and durable audit/outbox |
| Account Service | `DELETE /tenants/{tenantId}/memberships/me` | `caller_membership_scoped` | Caller-bound membership exit remains available while billing-blocked to any current member role, requires live subject-bound membership, and advances membership authority atomically |
| Account Service | `AuthLogout` / `AuthLogoutAll` | `account_scoped` | Accepts `control-ui` (`audience: control-ui`) or `player-bootstrap` (`audience: player-bootstrap`) with registry-backed authenticated account scope. Bounded retries may return no-op success only from a durable committed exact-token tombstone (`AuthLogout`) or durable proof that a prior logout-all superseded the token (`AuthLogoutAll`); registry absence alone is insufficient and the retry exception grants no reusable authorization context |
| Account Service | `GetProfile` / `UpdateProfile` (`/profiles/{accountId}`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only |
| Account Service | `ExportAccount` / `DeleteAccount` / `LinkExternalAccount` (`/accounts/{accountId}/...`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only. `DeleteAccount` also requires no nonterminal owned subscriptions |
| Account Service | `IssueConnectToken` | `player_bootstrap_tenant` | Caller-bound player-bootstrap auth only; current caller-bound membership authority generation, live membership, runtime entitlement, and admission-pointer checks are required. Global roles alone never grant gameplay admission or connect-token issuance |
| Account Service | `Authenticate` | `internal_workload` | Exact Game Session mTLS identity, no pre-existing issued token, and trusted server-derived credential source context |
| Account Service | `RefreshGameplayServiceToken` | `internal_workload` | Exact Game Session mTLS identity plus current `game-session-account-delegation` authority with audience `account-service` |
| Account Service | `GetTenantMembershipForRuntime` / `GetRealmAccessGrant` / `ListRealmAccessGrantsForAccount` | `internal_workload` | Exact Game Session mTLS identity plus validated typed player context; no circular end-user token prerequisite |
| Account Service | `GetTenantEntitlementsForRuntime` | `internal_workload` | Exactly one complete caller-policy alternative: Game Session mTLS plus current private delegation and typed player context for player admission, or World Management mTLS plus tenant/operation-bound instance-lifecycle context without a player delegation. Constraints within the selected alternative are conjunctive; no `control-ui` or Logging and Admin caller; not edge exposed |
| Account Service | `GetTenantEntitlementsTenant` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetTenantEntitlementsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `GetSubscriptionTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ExportTenantData` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped); tenant-bounded export only |
| Account Service | `GetSubscriptionCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ListSubscriptionsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsCrossTenantBillingSafeReports` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | `GetCallerTenantMembershipTenant` | `billing_safe_tenant` | `tenantAdmin` (subject bound to caller); caller-bound membership authority generation applies |
| Account Service | `GetTenantMembershipForAccountCrossTenant` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | invoice/payment method APIs tenant-scoped variant | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped); shared-instrument acknowledgement contract required when mutation affects account-wide payment instrument |
| Account Service | invoice/payment method APIs cross-tenant variant | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |

### Target Public Production Onboarding Example

1. A brand-new authenticated account completes `LOGIN` and issues `WORLDS`.
2. `WORLDS` may list the world's default public production realm even though no tenant membership exists yet.
3. `REALMS <world>` returns that default public production realm plus any separately granted additional realms.
4. The player explicitly uses `JOIN <world>` or `Join & Play`; Account atomically creates membership and durable audit/outbox.
5. `CHARS`, character creation, connect-token issuance, and `PLAY` require that membership and never create it implicitly.
6. Global roles alone never bypass this flow or grant gameplay admission/connect-token issuance without the same live checks.

Current implementation drift is that connect-token issuance and text `PLAY` can still call the membership writer implicitly. That is not target onboarding behavior; until the explicit join boundary is implemented, the route remains a recorded membership drift and must not be described as complete.

The matrix should be expanded as service API surfaces evolve. Service docs may include local excerpts, but this file is the canonical policy for declared entries; current omissions remain an explicit inventory drift/gap until source-stable coverage and validation complete the registry.
