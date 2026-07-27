# ADR 0041: Shared Tenant Infrastructure With Full-Environment Isolation Gate

## Status

Accepted

## Implementation Status

Partial. Shared multi-tenant services and tenant-qualified authorization and persistence are broadly implemented, but Account-owned tenant-bound entitlement freshness, complete owning-service quota enforcement, and the full negative-isolation and noisy-neighbour proof obligations remain incomplete. The decision below is the accepted target topology and isolation contract; acceptance does not imply those implementation and validation obligations are complete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.5` Entitlements, quotas, and hosting eligibility
- Affected capabilities: `PO-3.1`, `PO-3.2`, `SF-2.2`, `GR-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `TENANT-02`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TENANT-02`

## Context

FireMUD hosts independent games as tenants on one platform. A topology choice is required between one shared multi-tenant application environment, selectively dedicated services or data stores for some tenants, and complete per-tenant environments.

Selective dedication would add routing, provisioning, migration, backup, recovery, observability, and compatibility modes across every service and state boundary. FireMUD does not have a demonstrated hard-isolation requirement that justifies that complexity. The normal product instead needs economical hosting, consistent operations, and explicit tenant isolation within one environment.

## Decision

- Shared services, PostgreSQL, Coordination Redis, and Cache/Rate-Limit Redis are the only normal supported multi-tenant topology. Services scale horizontally for aggregate and region-partitioned load; FireMUD does not selectively dedicate services or data stores to individual tenants.
- Tenant isolation is logical and mandatory. Tenant-scoped APIs, persistence, Redis keys, object-storage paths, authorization, audit context, entitlements, and quotas carry and validate the authoritative `tenantId` at every applicable boundary.
- Global tables are an explicit exception to tenant-column rules. Platform-wide identity and other deliberately global authorities do not acquire a synthetic `tenantId`; relationships from global records to tenant-owned state use explicit tenant-scoped membership or ownership records.
- Account Service owns the canonical entitlement and plan-limit records, but every entitlement is bound to exactly one `tenantId` and is returned with that target identity and authority/version context. Gateway and TCP Proxy enforce edge-safety limits, while Game Session and other domain services enforce tenant-aware capacity, storage, and workload budgets at their owning boundaries. An account's entitlement for one tenant, a global role, or a cross-tenant read never supplies quota or capacity for another tenant; there is no cross-tenant entitlement inheritance or account-wide fallback.
- The operational blast radius of one environment is accepted. Infrastructure incidents, backup and restore, disaster recovery, platform maintenance, and environment-level security hardening may affect every tenant in that environment. The supported topology provides no tenant-local residency, disaster-recovery, restore, maintenance, or infrastructure-failure boundary.
- FireMUD will not build a hybrid dedicated-data-plane mode. If a demonstrated legal, residency, security, scale, or contractual requirement later needs hard infrastructure isolation, the candidate boundary is a separate complete FireMUD environment reviewed as its own deployment and operating model. It is not a selectively dedicated database, Redis deployment, or service subset inside the shared environment.

## Consequences

- One topology keeps deployment, scaling, upgrades, observability, backup, restore, and incident response consistent and avoids a second tenant-placement and migration control plane.
- Shared capacity improves utilization and keeps ordinary tenant hosting cost lower than dedicated stacks.
- Correct tenant scoping and quota enforcement are security and availability boundaries rather than optional application conventions.
- A tenant-filter or authorization defect can expose cross-tenant data, and a noisy tenant can affect others if quotas or workload isolation fail. Focused negative isolation and capacity proof are therefore release obligations.
- Environment-wide incidents and recovery can interrupt every tenant together. FireMUD does not promise tenant-local recovery times, maintenance windows, data residency, or independent infrastructure availability within one environment.
- A future separately isolated environment has higher fixed cost and operational overhead and requires explicit provisioning, routing, identity, release, backup, restore, monitoring, and support design.

## Alternatives Considered

### Selectively Dedicated Tenant Data Planes

Keep global identity and control services shared while assigning selected tenants dedicated PostgreSQL, Redis, workers, or storage. This can improve isolation and offer premium capacity, but it creates mixed routing and lifecycle modes, tenant-migration workflows, split backup and recovery contracts, and cross-version operational complexity throughout the platform. FireMUD rejects this topology without a demonstrated requirement.

### Dedicated Environment Per Tenant

Run a complete FireMUD environment for every tenant. This provides the strongest routine isolation and independent recovery but multiplies idle capacity, deployment, observability, credential, upgrade, and support cost. It is not the normal topology; it remains the future hard-isolation gate when a separate review establishes the need.

### Shared Infrastructure Without Enforced Quotas

Rely only on tenant identifiers and horizontal scaling. This is operationally simpler but leaves noisy-neighbour behavior uncontrolled and makes shared hosting unsafe under load. Logical data isolation alone is insufficient.

## Implementation and Proof Obligations

- Remove obsolete tenant columns from deliberately global tables and document the global-table exception in schema and tenancy checks.
- Require tenant-qualified keys, constraints, repository methods, API contracts, authorization checks, audit fields, and object-storage paths for every tenant-owned resource.
- Complete Account-owned, exact-`tenantId` entitlement and quota response fields, authoritative freshness/versioning, event invalidation, and owning-service enforcement for sessions, ticks, commands, automation, storage, and other bounded resources. Prove that cross-tenant reads, global roles, and missing target bindings cannot inherit or reuse another tenant's entitlement.
- Prove cross-tenant denial for reads, writes, exports, billing-safe variants, gameplay admission, cache access, Redis coordination, and operator workflows.
- Prove quota exhaustion contains load to the offending tenant while preserving platform invariants and bounded service health for other tenants.
- Keep environment-wide backup, restore, maintenance, incident, and security-hardening documentation explicit about the accepted all-tenant blast radius.
- Do not add tenant-placement flags, selective database or Redis routing, or dedicated-service modes without a new accepted decision.

## Reversibility and Revisit Triggers

Logical tenant contracts remain usable if a future isolated environment is introduced, but separating an existing tenant requires an explicit export, migration, identity, routing, and cutover design. Revisit only when a concrete legal or residency mandate, verified security requirement, measured scale limit, contractual isolation commitment, or demonstrated recovery objective cannot be met inside the shared topology. Any revisit must compare a complete separate environment against the operational and product cost of continuing shared hosting; selectively dedicated service or datastore modes are not presumed as an intermediate step.
