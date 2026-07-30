# ADR 0042: Global Account and Tenant-Scoped Game Relationships

## Status

Accepted

## Implementation Status

The global-account and explicit-membership model is target state and is not yet fully implemented or proved. Current registration DTOs, service call sites, fixtures, and persistence still carry legacy `tenantId`/`accounts.tenant_id` assumptions, so registration remains tenant-aware implementation drift rather than evidence that the target global-only registration contract is live. Direct convergence must make registration create only the global account and leave tenant membership to explicit `JOIN`.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.1` Account and profile identity
- Affected capabilities: `AA-1.2`, `AA-1.3`, `AA-1.4`, `AA-1.5`, `SF-2.1`, `PO-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human resolution of `MS-AA-GLOBAL-TENANT-BOUNDARY`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-AA-GLOBAL-TENANT-BOUNDARY`

## Context

FireMUD needs one stable identity for a person who may play, create, operate, or purchase across multiple games without making a tenant the owner of credentials or recovery. The boundary must also prevent tenant operators from gaining visibility into unrelated platform or other-tenant data.

The current implementation still carries legacy `accounts.tenant_id` drift. That shape conflates global identity with one game relationship and makes cross-game use, account recovery, compromise response, and deletion semantics ambiguous.

## Decision

- One global platform account owns credentials, platform-wide username and email identity, recovery state, linked external identities, and account-security lifecycle.
- Registration creates only the global account. It does not create tenant membership, roles, a profile, a character, an entitlement, or gameplay authority.
- Explicit `JOIN` creates the durable account-to-tenant membership for one game according to the accepted admission contract.
- Tenant roles, profiles, characters, tenant-bound purchases, subscriptions, entitlements, grants, and gameplay state are tenant-scoped relationships or records. They must carry and enforce their owning `tenantId` rather than becoming global account attributes.
- Explicit account-scoped purchases, grants, and donations belong to the global account and carry no `tenantId`. Applying one to a tenant-scoped game feature requires an explicit tenant binding or consumption record at the owning boundary.
- Leaving one game removes or deactivates the applicable tenant relationship under its retention rules. It does not delete or deactivate the global account or unrelated tenant relationships.
- Tenant operators may access only the minimum account identity and tenant-scoped data authorized for their tenant. Global security, recovery, external-identity, and unrelated-tenant data are not exposed through tenant authority.
- FireMUD accepts the consequences of global identity: username and email uniqueness are platform-wide; account compromise, security lock, recovery, and deletion have platform-wide blast radius; and authorized internal platform systems can correlate one account across games. Such correlation must not become tenant-operator visibility.
- Converge away from legacy `accounts.tenant_id` in explicit phases. First, a preservation phase audits every legacy `(account_id, tenant_id)` pair, backfills every valid pair into exactly one canonical `account_tenant_membership` row, records durable source-to-target evidence, and quarantines invalid or conflicting rows without dropping the legacy column. Next, deploy all entity, DTO, repository, authentication-flow, fixture, and test readers and writers against canonical membership so no runtime or test path derives tenant authority from `accounts.tenant_id`. Only then may a separately gated forward Flyway migration prove both complete preservation and the absence of legacy readers before dropping the column. Never edit an applied migration or preserve a compatibility field, and do not infer a default tenant from the global account row.

## Consequences

- A person uses one credential and recovery identity across FireMUD while acquiring explicit relationships with individual games.
- Tenant isolation is expressed through membership and tenant-keyed domain records rather than duplicate accounts or a tenant-owned credential row.
- Registration and joining become separate lifecycle events with separate authorization, audit, and failure behavior.
- Global security actions can revoke access across every tenant, while leaving or losing access to one tenant does not affect other tenant relationships.
- Platform services handling identity, recovery, security, compliance, or account deletion have legitimate cross-game correlation capability and require correspondingly strict access control and audit.
- Tenant-scoped queries, portable-data exports, operator tools, and events must avoid leaking the existence or contents of unrelated tenant relationships.

## Alternatives Considered

### One Account Per Tenant

Tenant-owned accounts simplify local isolation, but duplicate credentials and external links, fragment recovery and security response, and make one person manage separate identities for every game.

### Global Account With A Default Owning Tenant

Keeping `accounts.tenant_id` as a default or owner preserves current implementation convenience, but creates a privileged tenant relationship and leaves registration, switching, deletion, and cross-tenant authority ambiguous.

### Global Account With Implicit Membership On Registration Or First Use

Implicit membership reduces one onboarding action, but conflates identity creation with game consent and authorization. FireMUD instead requires the explicit `JOIN` boundary.

## Implementation and Proof Obligations

### Membership Reconciliation, Code Convergence, And Drop Gate

The forward migration uses this deterministic reconciliation order:

1. An existing canonical membership for the exact `(account_id, tenant_id)` pair takes precedence over the legacy `accounts.tenant_id` value. The legacy value is migration input only; other tenant-scoped rows must not synthesize a membership or select a default tenant.
2. A valid legacy pair with no canonical membership is backfilled as exactly one canonical membership and receives durable source-to-target preservation evidence.
3. Exact duplicate canonical rows with identical relationship payload and lifecycle state retain the lowest canonical membership row ID as the survivor. Before a losing row can be retired, one deterministic reconciliation operation locks the duplicate group and every dependent row, remaps every dependent identity and foreign key to the survivor, and records the old-to-new mapping, affected-row digest, and equality proof in immutable audit evidence. The remap and loser retirement commit in one transaction; retrying the same operation is idempotent and replays its committed result, while a changed source set or digest is a conflict. Immutable history may retain the losing ID only as source mapping evidence; it must not remain an authority-bearing reference. A duplicate with any differing relationship, role, admission, lifecycle, or authority/version payload is a conflict, not a candidate for merging.
4. Invalid account or tenant references, malformed or missing required identity fields, conflicting canonical rows, and any source pair that cannot be mapped to exactly one canonical membership are quarantined with row-level evidence and an explicit remediation outcome. No precedence or row-ID tie-breaker may resolve such a conflict.

Dropping `accounts.tenant_id` is a separate gated step after code convergence. It is blocked while any source row lacks a preservation disposition, any valid legacy pair lacks exactly one canonical membership, any duplicate/conflict quarantine remains unresolved, any dependent identity or foreign key still points at a retiring loser, or the migration cannot prove the complete source-to-target audit set. The deployed-code scan must persist a release-versioned no-reader attestation containing the exact release/artifact identity, schema target, scan/tool version, covered entity/DTO/repository/authentication/fixture/test surfaces, evidence digest, and completion time. Deployment preflight validates that attestation together with the complete preservation evidence and rejects a missing, stale, mismatched, incomplete, or differently versioned result. Only those two durable gates permit the separate forward Flyway migration to drop the column; the migration must verify both gates again in the same release operation and fail closed rather than remove `accounts.tenant_id` when either gate is absent, stale, or mismatched.

- Add a forward preservation migration or equivalent durable migration phase, without editing any applied migration, that first snapshots and validates every legacy `accounts.tenant_id` relationship, backfills every valid `(account_id, tenant_id)` pair into exactly one canonical membership row, and records row-level preservation evidence without removing the legacy column. Matching canonical rows must remain the sole relationship, duplicate canonical rows must be reconciled with the deterministic precedence, tie-breaker, quarantine, and drop-gate rules above, and invalid or conflicting source data must fail or quarantine the preservation phase; no relationship may be silently omitted or assigned a default tenant. Deploy the converged schema readers, entities, DTOs, repositories, authentication flows, fixtures, and tests, remove every fallback that derives tenant authority from the account row, and persist the release-versioned no-reader attestation. Finally, run a separate forward Flyway drop migration only after deployment preflight validates that attestation and a complete preservation proof pass; that migration must verify both durable gates against the same release identity in the same release operation before dropping `accounts.tenant_id`.
- Make registration persist only global account identity and security state. Prove it creates no tenant membership or gameplay authority.
- Make `JOIN` the only open-enrollment creator of the durable tenant membership and prove it is explicit, idempotent, tenant-bound, and audited.
- Store and validate `tenantId` on tenant roles, profiles, characters, tenant-bound purchases, subscriptions, entitlements, grants, and gameplay relationships, including all read, write, export, event, and deletion paths. Keep account-scoped purchases, grants, and donations account-owned with no fabricated `tenantId`, and create an explicit tenant binding or consumption record when they are used by tenant-scoped features.
- Prove leaving one tenant preserves the global account and every unrelated tenant relationship.
- Prove tenant operators cannot read global recovery/external-identity data or discover another tenant's memberships, roles, profiles, characters, purchases, subscriptions, entitlements, or gameplay state.
- Prove platform-wide username/email uniqueness and global compromise, lock, recovery, revocation, portable-data export, and deletion behavior across multiple tenant relationships.
- Prove the forward migration removes legacy tenant ownership without editing applied migrations: every valid legacy `(account_id, tenant_id)` pair has exactly one preserved canonical membership row, duplicate/conflict handling has durable audit evidence, every dependent identity and foreign key is remapped to the deterministic survivor in one idempotent transaction before a loser is retired, invalid source rows are explicitly reported and not discarded, and the preservation proof completes before the legacy column is dropped.

## Reversibility and Revisit Triggers

The global account identifier and tenant relationship keys are durable identity boundaries and expensive to reverse once live data exists. Revisit only if FireMUD introduces legally or operationally isolated tenant deployments that cannot share identity authority, or adopts an external identity provider whose tenancy model requires a different account boundary. Any revisit must preserve explicit tenant consent, isolation, recovery, deletion, and migration semantics.
