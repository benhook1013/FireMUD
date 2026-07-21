# FireMUD System Architecture: Authorization Route Matrix

The machine-readable [authorization route matrix](./system-architecture-authz-route-matrix.yaml) is normative for the route entries it contains. It is not yet a complete route inventory: source-stable OpenAPI/protobuf coverage and its comparison validation are incomplete. This document is the human-readable companion and must not define a competing policy.

Every protected route in a validated inventory must be listed here with:

- route identifier (service + method/path),
- classification (`public`, `account_scoped`, `caller_membership_scoped`, `player_bootstrap_tenant`, `pre_tenant_discovery`, `public_production_onboarding`, `tenant_regular`, `billing_safe_tenant`, `cross_tenant_support_safe`, `cross_tenant_billing_safe`, `cross_tenant_data_bearing`, `internal_workload`, `pending_deletion_scoped`),
- whether a matching Account issued-token registry record is required,
- required role checks,
- tenant-billing authority-generation applicability,
- caller-bound membership authority-generation applicability where relevant,
- required live authority checks for the route class,
- any response-profile or mutation-contract requirements needed for CI/security enforcement.
- any role-assurance or bounded privileged-elevation contract required before a global role may authorize the route.

**Canonical incomplete-inventory rule:** Until source-stable OpenAPI/protobuf inventory coverage is complete and validated, the YAML is declaration-only and must not generate runtime or default-deny policy for routes absent from the validated inventory. Runtime must reject unclassified protected routes and leave unclassified external routes unreachable or denied; CI and deployment inventory checks must fail validated candidate routes missing from the YAML.

Services must enforce these classifications through shared middleware annotations/interceptors immediately. A protected route missing from the current matrix is recorded as authorization drift/gap; the canonical incomplete-inventory rule above governs its runtime and CI/deployment handling rather than forwarding or approximating it.

## Implementation Status

- Public-production onboarding still has implementation drift: connect-token issuance and text `PLAY` may create a missing public-production membership implicitly, while the target requires explicit `JOIN` before character creation, connect-token issuance, or `PLAY`.
- The static Gateway route catalog and bounded internal/actuator blockers provide partial edge-exposure enforcement.
- The YAML is normative for declared entries, but the current route inventory is incomplete. CI inventory generation, source-stable OpenAPI/protobuf coverage, YAML completeness comparison, matrix-aware shared middleware, strict token-profile enforcement, and exact proof for remaining broad Gateway route families are not implemented. Missing coverage is a recorded drift/gap; the canonical incomplete-inventory rule above remains the immediate runtime and CI/deployment safeguard.

## Token Profile Vocabulary

The current JWT profile names are `control-ui`, `player-bootstrap`, the one-use `gameplay-connect` handshake token, and receiver-specific private player-delegation profiles. `gameplay-connect` uses audience `gameplay-connect` and is consumed by Gateway rather than accepted as gameplay command authorization. The current private delegation profile is `game-session-account-delegation` with audience `account-service`. A generic backend JWT profile and the `internal` audience are forbidden. A privileged-control window is an authorization condition, not a JWT profile.

## Governance (Required)

- **Owner**: Platform Security + Account Service maintainers jointly own this matrix.
- **Machine-readable source**: `design/architecture/system-architecture-authz-route-matrix.yaml` is the normative source for declared entries; this Markdown file is the human-readable companion. It is not a complete route registry until the inventory gate below passes.
- **CI enforcement**:
  - Once source-stable OpenAPI/protobuf inventories cover the governed surfaces and validate in the same run, CI and deployment policy checks independently fail if a protected route is not present in the YAML matrix.
  - Fail if a route uses an unknown classification value.
  - Fail if a route is marked billing- or support-safe but lacks required redaction/authorization tests.
  - Once the source-stable inventory gate above passes, fail if the same-run generated route inventory (OpenAPI/proto) differs from the YAML matrix for auth/session and billing/subscription domains. Before that gate passes, record the difference as drift without treating the incomplete inventory as an enforceable registry.
- **Default-deny behavior**:
  - The declared-entry `default_action: deny` is normative only for entries in the YAML; the canonical incomplete-inventory rule above governs routes absent from the validated inventory.
  - Runtime classification and CI/deployment inventory registration are separate gates; both follow the canonical incomplete-inventory rule above.
  - No route may default to `tenant_regular`, billing-safe, support-safe, or another executable class.
- **Change control**:
  - `billing_safe_tenant`, `cross_tenant_support_safe`, and `cross_tenant_billing_safe` changes require explicit security review approval.

### Critical Domains And Inventory Gate

The following domains become full-fail in CI only after source-stable OpenAPI/protobuf inventories cover them and the same run validates the comparison:

- Authentication/session admission routes (`LOGIN`/`PLAY` surfaces and equivalents).
- Billing-safe and support-safe routes.
- Subscription mutation and entitlement routes.

Before that gate passes, protected routes missing from the YAML matrix are recorded as drift/gap, including pre-existing routes; the canonical incomplete-inventory rule above applies until the validated candidate inventory is compared.

CI should generate candidate inventories from OpenAPI/proto definitions and compare them against the YAML matrix so protected-route drift is detected automatically.

Critical-domain inventory artifacts (required):

- CI must persist generated candidate inventories for auth/session and billing/support domains (OpenAPI + proto derived) under version control (for example `design/architecture/authz-inventory/*.json`).
- Full-fail assertions and generated default-deny policy in critical domains are valid only when these generated inventories are present, source-stable, and compared against the YAML matrix in the same run.
- Inventory generation must distinguish tenant-scoped and cross-tenant route variants explicitly; mixed-scope APIs must not be represented as a single ambiguous route key.

## Classification Rules

| Classification | Required issued-token state | Tenant/Membership authority generations applied? | Notes |
| --- | --- | --- | --- |
| `public` | none | No | No JWT required |
| `account_scoped` | One matching token record for the exact profile declared by the route | No | Account-level control-plane routes with subject binding (`accountId == caller`), plus explicit route-level admin overrides |
| `caller_membership_scoped` | One matching token record for the exact profile declared by the route | No tenant authority generation; membership authority generation: Yes | Caller-bound lifecycle operations on the caller's own account-to-tenant membership. Any current membership role may act on itself; the route must perform a live subject-bound membership check and must not accept an arbitrary account target or global-role override |
| `player_bootstrap_tenant` | One matching `player-bootstrap` token record | No tenant authority generation; membership authority generation is route-specific and must be declared explicitly | Player-bootstrap-authenticated routes targeting a tenant before gameplay socket auth is complete. `IssueConnectToken` requires the current membership authority generation plus live membership, entitlement, and admission-pointer checks |
| `pre_tenant_discovery` | In-band gameplay commands may use the already-authenticated Game Session context without presenting a JWT; other routes require the exact token profile they declare | No | Authenticated discovery surfaces that run before a single `tenantId` is selected (for example `WORLDS`). A route using `game_session_authenticated_context` therefore declares `accepted_token_profiles: []`; this does not make the command anonymous. |
| `public_production_onboarding` | No JWT for in-band gameplay commands; otherwise the exact route-declared profile (`player-bootstrap` for Account bootstrap writes, one-use `gameplay-connect` for the Gateway WebSocket bootstrap) | No tenant authority generation before join; membership authority generation applies after join | **Target:** discovery and explicit open-enrollment join for the default public production realm. Brand-new authenticated accounts may discover it before membership exists, but `JOIN`/`Join & Play` creates the durable Account-owned membership before character creation, connect-token issuance, or `PLAY`. **Current drift:** connect-token issuance and text `PLAY` can still create public-production membership implicitly and must converge on `JOIN_REQUIRED`. Grant-backed private/playtest realms validate their grant and any separately required existing membership, skip `JOIN`, and never use this class to create membership. |
| `tenant_regular` | One matching token record for the exact profile declared by the route | Tenant authority generation: Yes; membership authority generation: Yes | Gameplay-affecting and regular tenant control-plane operations |
| `billing_safe_tenant` | One matching token record for the exact profile declared by the route | Tenant authority generation: No; membership authority generation: Yes | Must remain reachable during `suspended`/`canceled`, but must fail immediately after caller-bound membership/role revocation |
| `cross_tenant_support_safe` | One matching token record for the exact profile declared by the route | No | High-level troubleshooting only |
| `cross_tenant_billing_safe` | One matching token record for the exact profile declared by the route | No | Billing operations for global billing roles |
| `cross_tenant_data_bearing` | One matching token record for the exact profile declared by the route | Yes when operation targets tenant-scoped data | Platform-admin-only data-bearing operations |
| `internal_workload` | Route-specific: explicitly `none` or one exact delegated profile | Route-specific | Internal-only RPCs require exact mTLS workload identity and a method caller allowlist, and both constraints must pass. Each entry declares whether it carries delegated subject authority; this class never inherits an end-user token requirement implicitly. |
| `pending_deletion_scoped` | No JWT; one Account-issued pending-deletion access credential | No | Account-owned opaque credential bound to the pending-deletion workflow and server-side registry. It authorizes only status, cancellation, export, and necessary billing settlement; it is not normal account or gameplay authority. |

Any route that accepts more than one token profile must declare an `accepted_token_profile_audiences` YAML map that binds each accepted profile to its exact audience rather than using an implicit shared audience. The mapping must cover exactly the accepted profiles and must match the audience declared by the token-profile vocabulary.

Internal-service routes must additionally declare their **service caller policy** in the machine-readable matrix:

- whether the route is callable only by specific service identities,
- whether an end-user issued-token record and scope authorization are still evaluated on behalf of a delegated subject, and
- which token profile/audience the caller must present.

Each `caller_policies` entry is one complete alternative. Alternatives are disjunctive, while the `caller` method identity, `mtls_identity` certificate identity, token state/profile, applicable authority generations, delegated context, and live checks inside one entry are conjunctive. A caller name is not sufficient without its exact certificate identity, and an mTLS identity is not sufficient without the method caller policy. When alternatives use different token states, generation-applicability fields belong inside each caller policy rather than at route level. The YAML `caller_policies_schema` makes these two identity fields and their conjunctive relation explicit without introducing a broader policy framework.

Without these fields, a route classification is incomplete for internal-only APIs.

Critical routes may also require explicit machine-readable fields for:

- `applicability` when multiple entries share one transport route or command; predicates must be explicit and mutually exclusive so classification is deterministic
- `membership_authority_generation_applies`
- `tenant_billing_authority_generation_applies`
- `required_live_checks`, whose values must come from the closed YAML `required_live_check_vocabulary`, such as `membership`, `conditional_realm_access_grant`, `membership_generation`, `runtime_entitlements`, `admission_pointer`, `connect_token_single_use_consume`, `replay_protection_available`, `replay_admission_fence_match`, and `connect_scope_match`
- `mutation_contract` such as `shared_instrument_ack_required`
- `canonical_errors` that CI and contract tests must expect for route-specific security rejections
- `realm_grant_authority` and `realm_grant_version` when grant-gated access depends on Account-owned realm authority
- `canonical_target` when the entry records the converged target contract alongside known implementation drift
- `implementation_status` when the current implementation is known not to satisfy that target

For first-party gameplay, the `/ws/game/**` route declares `operation: websocket_upgrade`, while `POST /ws/game/connect-token/revoke` declares `operation: connect_token_cookie_revoke`; these applicability predicates are mutually exclusive even though both use `connection_mode: first_party_web`.

Without these fields where applicable, a route entry is incomplete for governance and CI enforcement.

### Privileged Role Assurance

Global-role possession is not sufficient to enter a privileged control window. `platformAdmin` authority always requires the machine-readable `privileged_control` elevation contract; `billingAdmin` requires it when exercising cross-tenant authority. Account Service creates that bounded server-side, role-scoped state only through `EnterPrivilegedControlWindow` after recent ordinary reauthentication and independent TOTP. The state is bound to the account, current `control-ui` token `jti`, account generation, requested global role, issue time, and expiry, and is invalidated by token revocation, generation change, role loss, or expiry. It is not a JWT profile or gameplay authority.

Route authorization never becomes in-game elevation. If a global-role account passes the ordinary caller-bound join and admission flow, gameplay presence, command, and actor-capability resolution ignore its global roles. Any moderator, administrator, game-master, or equivalent gameplay authority requires an explicit tenant-scoped gameplay grant; no route classification creates a support impersonation or hidden-observer session.

### Operator Delegation

Account Service is the operator-delegation authority. Human issuance validates the current `control-ui` `jti`, account generation, current tenant or global role, tenant/scope, action family, canonical mutation digest, and any role-required `privileged_control` window. Tenant-scoped `tenantAdmin` and `moderator` actions require live membership and role but not a fabricated global elevation. A global `platformAdmin` tenant operation instead binds the current target-tenant generation without inventing membership. Unattended issuance validates exact allowlisted mTLS workload identity plus a current versioned automation policy and the same mutation digest. The resulting opaque reference is bound either to the human token/account/role evidence or to the workload/policy evidence; automation never impersonates a user.

Logging and Admin records the operator intent and actor kind, then forwards the same reference, `controlPlaneRequestId`, and mutation digest to the owner. The owner call is `internal_workload`: exact mTLS caller identity plus Account redemption/validation of the reference, followed by owner recomputation of the digest and owner-side domain facts, fencing, and idempotency checks. Logging and Admin role, actor, or automation-policy assertions alone are never authority, and owner-side admission RPCs do not accept an end-user JWT.

The current route inventory includes feature-flag toggle, moderation action, admission pointer and version-upgrade writes, tick pause/resume, and the adjacent Game Session session lifecycle mutation surfaces. Quota override has no current OpenAPI or owner route and is recorded as coverage drift; no route is invented for it. The current moderation endpoint records policy input, but no owner-side moderation-enforcement RPC exists, so that owner call is also coverage drift.

## Seed Matrix (Current Required Entries)

For every first public-production text entry, the target command sequence is `LOGIN` -> conditional `JOIN` -> `PLAY`; returning members skip `JOIN`. The current direct `LOGIN` -> `PLAY` compatibility path is implementation drift where it can create membership implicitly, not a second target contract.

| Service | Route | Classification | Required roles/capability |
| --- | --- | --- | --- |
| Game Session Service | `LOGIN` / `LOGON` | `public` | Credential entrypoint only; no JWT required |
| Game Session Service | `WORLDS` anonymous browse | `public` | Public-production catalog only; no account-specific membership, grant, or character information |
| Game Session Service | `WORLDS` authenticated discovery | `pre_tenant_discovery` | No pre-existing tenant role is required. Tenant visibility is derived server-side from membership or public-production visibility plus entitlement state; global roles do not widen gameplay discovery |
| Game Session Service | `REALMS` | `public_production_onboarding` | Visible realms for a selected world; no pre-existing tenant role is required. The default public production realm may be discoverable before membership exists, while additional realms still require explicit Account Service grant authority |
| Game Session Service | `JOIN` | `public_production_onboarding` | Explicit caller-bound open-enrollment action for the current public production realm; Account commits durable membership plus audit/outbox idempotently |
| Game Session Service | `CHARS` | `public_production_onboarding` | Requires an existing caller-bound membership plus any non-public realm grant and current entitlements. It resolves characters for the selected catalog realm but does not apply `PLAY`'s `admission_pointer` live-admission check. Missing public-game membership returns `JOIN_REQUIRED`. |
| Game Session Service | `PLAY` | `public_production_onboarding` | **Target:** requires an existing caller-bound membership plus any non-public realm grant, current entitlements, and the current admissible realm pointer; missing public-game membership returns `JOIN_REQUIRED`, and `PLAY` never creates it. **Current drift:** connect-token issuance and text `PLAY` may still invoke the membership writer implicitly. First-party `/ws/game/**` also enforces connect-context scope, and unavailable/ambiguous routing returns `ADMISSION_POINTER_UNAVAILABLE`; a deliberately closed realm returns `REALM_UNAVAILABLE`. |
| Spring Cloud Gateway | `/ws/game/**` (connect-token bootstrap) | `public_production_onboarding` | Applies to every public non-proxy WebSocket bootstrap: first-party browsers use the protected cookie and explicitly classified non-browser clients use the dedicated handshake header. Its applicability is `connection_mode: first_party_web` plus `operation: websocket_upgrade`; tokenless public WebSocket support does not exist. Gateway consumes exactly one `gameplay-connect` token with audience `gameplay-connect`, strips its carrier, atomically enforces single use, proves replay protection is available, matches the signed connect scope to the request, and emits the signed connect context for Game Session. The current internal connection-mode value `first_party_web` denotes this verified connect-token path regardless of carrier. Non-`101` outcomes use the bounded `CONNECT_TOKEN_MISSING`, `CONNECT_TOKEN_EXPIRED`, `CONNECT_TOKEN_REPLAYED`, `CONNECT_SCOPE_MISMATCH`, `CONNECT_REPLAY_PROTECTION_UNAVAILABLE`, `CONNECT_TOKEN_REJECTED`, `POLICY_DENY`, `POLICY_PRESSURE`, `BACKEND_UNAVAILABLE`, `PROTOCOL_MISMATCH`, or `INTERNAL_ERROR` handshake classes. |
| Spring Cloud Gateway | `/ws/game/**` (trusted TCP Proxy bridge) | `public_production_onboarding` | Applies only on the internal mTLS listener when the exact TCP Proxy workload identity is authenticated. Gateway canonicalizes trusted proxy metadata and emits `X-Firemud-Connection-Mode: trusted_tcp_proxy`; this path does not accept or consume a gameplay connect token, and public clients cannot select it. |
| Spring Cloud Gateway | `POST /ws/game/connect-token/revoke` | `public_production_onboarding` | **Target; not currently implemented.** Its applicability is `connection_mode: first_party_web` plus `operation: connect_token_cookie_revoke`, mutually exclusive with the websocket-upgrade route. First-party browser logout reads only the HttpOnly `Firemud-Connect-Token` cookie, requires the exact configured Origin and anti-CSRF proof, records a bounded deny marker when a token is present, clears the cookie, and is idempotently successful when it is absent. It never accepts a query, body, or caller-readable token. |
| Account Service | `GET /auth/bootstrap/worlds` | `pre_tenant_discovery` | Accepts only the caller-bound `player-bootstrap` profile; derives visible worlds before a tenant is selected and applies the membership/public-production visibility contract plus live runtime entitlement filtering |
| Account Service | `GET /auth/bootstrap/worlds/{worldSlug}/realms` | `public_production_onboarding` | Accepts only the caller-bound `player-bootstrap` profile; applies live realm visibility and runtime entitlement checks, including public-production visibility or an applicable Account-owned realm-access grant |
| Account Service | `GET /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters` | `public_production_onboarding` | Accepts only the caller-bound `player-bootstrap` profile; its target binding and `connect_scope_match` check require the opaque server-issued `connectScopeId` to match the selected route target, alongside live membership, applicable realm-grant, entitlement, and admission-pointer checks |
| Account Service | `POST /auth/bootstrap/worlds/{worldSlug}/realms/{realmSlug}/characters` | `public_production_onboarding` | Bootstrap-authenticated Account facade derives `accountId` from the authenticated caller subject, and its target binding plus `connect_scope_match` check require a matching opaque server-issued discovery `connectScopeId`; existing caller-bound membership, applicable realm visibility/grant, runtime entitlement, and admission-pointer checks precede delegation to internal Entity Management `CreateCharacter` |
| Account Service | `POST /auth/bootstrap/join` | `public_production_onboarding` | **Target; not currently implemented.** Accepts only the caller-bound `player-bootstrap` profile, derives `accountId` from the authenticated subject, and its target binding plus `connect_scope_match` check require the opaque server-issued discovery `connectScopeId` to match the selected public-production target before creating membership atomically |
| Game Session Service | `StartSession` / `RestartSession` / `StopSession` / `RefreshRoles` | `tenant_regular` | `tenantAdmin`/`platformAdmin` |
| Account Service | `AuthLogin` | `public` | `control-ui` auth entrypoint |
| Account Service | `PlayerBootstrapLogin` | `public` | First-party gameplay bootstrap entrypoint; issues `player-bootstrap` token profile only |
| Account Service | `EnsurePublicProductionPlayerMembership` (target `JoinPublicProductionMembership`) | `internal_workload` | Internal explicit-join mutation called only by the exact Game Session mTLS workload with typed caller-bound player execution context; it accepts no end-user token and commits membership plus durable audit/outbox transactionally |
| Account Service | `DELETE /tenants/{tenantId}/memberships/me` | `caller_membership_scoped` | Caller-bound membership exit remains available while billing-blocked to any current member role, requires live subject-bound membership, and advances membership authority atomically |
| Account Service | `AuthLogout` / `AuthLogoutAll` | `account_scoped` | Accepts `control-ui` (`audience: control-ui`) or `player-bootstrap` (`audience: player-bootstrap`) with registry-backed authenticated account scope. Bounded retries may return no-op success only from a durable committed exact-token tombstone (`AuthLogout`) or durable proof that a prior logout-all superseded the token (`AuthLogoutAll`); registry absence alone is insufficient and the retry exception grants no reusable authorization context |
| Account Service | `GetProfile` / `UpdateProfile` (`/profiles/{accountId}`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only |
| Account Service | `ExportAccount` / `DeleteAccount` / `LinkExternalAccount` (`/accounts/{accountId}/...`) | `account_scoped` | Subject-bound to caller `accountId`; `platformAdmin` override only. `DeleteAccount` also requires no nonterminal owned subscriptions |
| Account Service | `IssueConnectToken` | `player_bootstrap_tenant` | Caller-bound player-bootstrap auth only; the target requires a live membership reread and current membership authority generation, plus runtime entitlement, admission-pointer, scope-match, and replay-fence checks. Canonical target: existing caller-bound membership is required and connect-token issuance never creates membership. Current implementation drift is that it may still invoke `EnsurePublicProductionPlayerMembership` and does not reread membership authority generation after bootstrap/discovery validation. These gaps are recorded implementation status only; they do not weaken the target checks. Global roles alone never grant gameplay admission or connect-token issuance |
| Account Service | `Authenticate` | `internal_workload` | Exact Game Session mTLS identity, no pre-existing issued token, and trusted server-derived credential source context |
| Account Service | `RefreshGameplayServiceToken` | `internal_workload` | Exact Game Session mTLS identity plus current `game-session-account-delegation` authority with audience `account-service` |
| Account Service | `GetTenantMembershipForRuntime` / `GetRealmAccessGrant` / `ListRealmAccessGrantsForAccount` | `internal_workload` | Exact Game Session mTLS identity plus validated typed player context; no circular end-user token prerequisite |
| Account Service | `GetTenantEntitlementsForRuntime` | `internal_workload` | Exactly one complete caller-policy alternative: Game Session mTLS plus current private delegation and typed player context for player admission, or World Management mTLS plus tenant/operation-bound instance-lifecycle context without a player delegation. Constraints within the selected alternative are conjunctive; no `control-ui` or Logging and Admin caller; not edge exposed |
| Account Service | `GetTenantEntitlementsTenant` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GetTenantEntitlementsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `GetSubscriptionTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `GET /tenant-admin/tenants/{tenantId}/export` (`ExportTenantData`) | `billing_safe_tenant` | Live `tenantAdmin` membership for the path tenant; tenant-wide tenant-owned export only, with no account subject selector. The target route is not currently routable and current account-targeted implementation/proto behavior is `drift_found` |
| Account Service | `GetSubscriptionCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsTenantHighLevel` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped) |
| Account Service | `ListSubscriptionsCrossTenantSupportSafe` | `cross_tenant_support_safe` | `support`/`platformAdmin` |
| Account Service | `ListSubscriptionsCrossTenantBillingSafeReports` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | `GetCallerTenantMembershipTenant` | `billing_safe_tenant` | `tenantAdmin` (subject bound to caller); caller-bound membership authority generation applies |
| Account Service | `GetTenantMembershipForAccountCrossTenant` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | `BillingArtifactsTenant` | `billing_safe_tenant` | `tenantAdmin` (tenant-scoped); shared-instrument acknowledgement contract required when mutation affects account-wide payment instrument |
| Account Service | `BillingArtifactsCrossTenant` | `cross_tenant_billing_safe` | `billingAdmin`/`platformAdmin` |
| Account Service | account payment-instrument wallet | drift-found | No current routable wallet/payment-instrument contract. See ADR 0044; the missing contracts are wallet-to-account/provider customer mapping, explicit per-subscription binding, safe instrument detachment, and billing ownership transfer |

### Target Public Production Onboarding Example

1. A brand-new authenticated account completes `LOGIN` and issues `WORLDS`.
2. `WORLDS` may list the world's default public production realm even though no tenant membership exists yet.
3. `REALMS <world>` returns that default public production realm plus any separately granted additional realms.
4. The player explicitly uses `JOIN <world>` or `Join & Play`; Account atomically creates membership and durable audit/outbox.
5. `CHARS`, character creation, connect-token issuance, and `PLAY` require that membership and never create it implicitly.
6. Global roles alone never bypass this flow or grant gameplay admission/connect-token issuance without the same live checks.

Current implementation drift is that connect-token issuance and text `PLAY` can still call the membership writer implicitly. That is not target onboarding behavior; until the explicit join boundary is implemented, the route remains a recorded membership drift and must not be described as complete.

The `IssueConnectToken` matrix entry records the same contract explicitly: its canonical target is `existing_caller_bound_membership_required` with `issue_connect_token_never_creates_membership`, while its current implementation status records both the possible implicit membership writer and the absent membership-authority-generation reread. The target `membership`, `membership_generation`, and related live checks remain mandatory.

The matrix should be expanded as service API surfaces evolve. Service docs may include local excerpts, but this file is the canonical policy for declared entries; current omissions remain an explicit inventory drift/gap until source-stable coverage and validation complete the registry.
