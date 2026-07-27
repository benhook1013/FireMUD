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
- Tenant roles, profiles, characters, purchases, subscriptions, entitlements, grants, and gameplay state are tenant-scoped relationships or records. They must carry and enforce their owning `tenantId` rather than becoming global account attributes.
- Leaving one game removes or deactivates the applicable tenant relationship under its retention rules. It does not delete or deactivate the global account or unrelated tenant relationships.
- Tenant operators may access only the minimum account identity and tenant-scoped data authorized for their tenant. Global security, recovery, external-identity, and unrelated-tenant data are not exposed through tenant authority.
- FireMUD accepts the consequences of global identity: username and email uniqueness are platform-wide; account compromise, security lock, recovery, and deletion have platform-wide blast radius; and authorized internal platform systems can correlate one account across games. Such correlation must not become tenant-operator visibility.
- Remove legacy `accounts.tenant_id` and its assumptions directly in this pre-v1 system. Do not preserve a compatibility field or infer a default tenant from the global account row.

## Consequences

- A person uses one credential and recovery identity across FireMUD while acquiring explicit relationships with individual games.
- Tenant isolation is expressed through membership and tenant-keyed domain records rather than duplicate accounts or a tenant-owned credential row.
- Registration and joining become separate lifecycle events with separate authorization, audit, and failure behavior.
- Global security actions can revoke access across every tenant, while leaving or losing access to one tenant does not affect other tenant relationships.
- Platform services handling identity, recovery, security, compliance, or account deletion have legitimate cross-game correlation capability and require correspondingly strict access control and audit.
- Tenant-scoped queries, exports, operator tools, and events must avoid leaking the existence or contents of unrelated tenant relationships.

## Alternatives Considered

### One Account Per Tenant

Tenant-owned accounts simplify local isolation, but duplicate credentials and external links, fragment recovery and security response, and make one person manage separate identities for every game.

### Global Account With A Default Owning Tenant

Keeping `accounts.tenant_id` as a default or owner preserves current implementation convenience, but creates a privileged tenant relationship and leaves registration, switching, deletion, and cross-tenant authority ambiguous.

### Global Account With Implicit Membership On Registration Or First Use

Implicit membership reduces one onboarding action, but conflates identity creation with game consent and authorization. FireMUD instead requires the explicit `JOIN` boundary.

## Implementation and Proof Obligations

- Remove `accounts.tenant_id` from the canonical schema, entities, DTOs, repositories, authentication flows, migrations, fixtures, and tests; remove every fallback that derives tenant authority from the account row.
- Make registration persist only global account identity and security state. Prove it creates no tenant membership or gameplay authority.
- Make `JOIN` the only open-enrollment creator of the durable tenant membership and prove it is explicit, idempotent, tenant-bound, and audited.
- Store and validate `tenantId` on tenant roles, profiles, characters, purchases, subscriptions, entitlements, grants, and gameplay relationships, including all read, write, export, event, and deletion paths.
- Prove leaving one tenant preserves the global account and every unrelated tenant relationship.
- Prove tenant operators cannot read global recovery/external-identity data or discover another tenant's memberships, roles, profiles, characters, purchases, subscriptions, entitlements, or gameplay state.
- Prove platform-wide username/email uniqueness and global compromise, lock, recovery, revocation, export, and deletion behavior across multiple tenant relationships.
- Add migration proof for removing legacy tenant ownership without losing valid global accounts or tenant relationships.

## Reversibility and Revisit Triggers

The global account identifier and tenant relationship keys are durable identity boundaries and expensive to reverse once live data exists. Revisit only if FireMUD introduces legally or operationally isolated tenant deployments that cannot share identity authority, or adopts an external identity provider whose tenancy model requires a different account boundary. Any revisit must preserve explicit tenant consent, isolation, recovery, deletion, and migration semantics.
