# ADR 0139: Tenant-Owned Runtime Lifecycle with Audited Break-Glass

## Status

Accepted

## Implementation Status

This decision is not implemented. Lifecycle routes remain primarily internal or operator-oriented; tenant-scoped authority, action-class gates, recovery availability, complete entitlement composition, and audited break-glass proof remain absent or partial.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `LIFE-01`
- Decision date: 2026-07-20
- Decision key: `LIFE-01`
- Primary capability: `AR-3.1`
- Affected capabilities: `AA-1.5`, `PO-1.1`, `AR-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of creator autonomy, runtime lifecycle authority, production gates, recovery availability, platform intervention, delegated roles, and discovery eligibility

## Context

FireMUD needs tenant creators to operate their realms without making a platform operator approve every routine launch, patch, rollback, or shutdown. The existing creator journey points in that direction, but lifecycle authority is spread across broad `platformAdmin`, operator, moderator, and service-local guards. The target does not yet enumerate the full lifecycle owned by `tenantAdmin`, distinguish capacity-creating operations from recovery and cleanup, or prove that platform intervention is a separate break-glass action rather than tenant impersonation.

The opposite extremes are both harmful. Requiring a platform operator for every lifecycle mutation creates an operational bottleneck and weakens self-hosting and creator autonomy. Allowing content authors or tenant administrators to bypass publication, entitlement, quota, compatibility, readiness, billing, safety, or integrity checks would let role ownership substitute for runtime correctness and platform obligations.

Marketplace publication and public discovery are adjacent but separate concerns. A tenant may be authorized to run a private or playtest realm without satisfying marketplace listing policy, and marketplace approval does not grant runtime lifecycle authority or make a release technically launchable.

## Decision

### Routine Lifecycle Authority

`tenantAdmin` is the accountable routine owner of runtime lifecycle within that tenant. This authority covers:

- launch and failed-launch cleanup;
- opening and closing player admission;
- bounded drain;
- stop and restart;
- playtest fork creation, reset, expiry, and retirement;
- script-patch pinning;
- replacement-instance preparation and cutover;
- rollback; and
- ordinary instance or realm retirement.

The lifecycle request records the authenticated actor, tenant, reason where the operation requires one, stable idempotency identity, requested target, and resulting owner workflow or state transition. Services enforce tenant scope against authoritative membership and do not infer authority merely from possession of a realm or instance identifier.

The `designer` role may author and publish content but gains no runtime lifecycle authority from publication. Publication proves a release transition, not permission to launch, open admission, consume capacity, cut over, or retire runtime state.

### Production and Capacity-Creating Gates

Starting production gameplay or increasing runtime capacity fails closed unless all applicable current gates pass:

- the selected release and every required patch, plugin, manifest, and asset are published and attested;
- runtime entitlement and billing state permit the exact operation;
- plan and operator quotas permit the requested instance count and resources;
- the frozen release tuple and source state pass compatibility and migration or remap requirements; and
- every required runtime owner reports readiness under the same launch or cutover proof.

The same gate classes apply to `tenantAdmin` and break-glass `platformAdmin`. No role may bypass data-integrity, release-cohesion, owner-readiness, fencing, or atomic cutover invariants.

Marketplace eligibility and public discovery policy remain separate gates. They may determine whether a realm or game is listed or publicly joinable, but they neither replace runtime preflight nor block authorized private operation unless an explicit safety or billing policy closes that operation.

### Recovery, Closure, and Repair Remain Available

Failure of a start or capacity-increasing gate does not disable operations that reduce exposure, converge failed work, or repair the blocking authority. Stop, close admission, drain, cleanup, billing-safe repair, and authorized audit remain available while production start or expansion is forbidden. Rollback or non-expanding recovery follows its separately documented entitlement freshness and compatibility contract; it does not silently become new capacity.

Platform safety or billing authority may force a realm or tenant closed or suspended. That action fences admission and runtime use under the owning safety or entitlement contract. Reopening is a new routine lifecycle action and must pass ordinary publication, entitlement, quota, compatibility, readiness, and any current safety gates; the prior force-close actor cannot pre-authorize a later reopen.

### Platform Break-Glass

`platformAdmin` intervention is a distinct control-plane action, not impersonation of a `tenantAdmin` and not an implicit tenant membership. Every break-glass mutation requires an authenticated platform actor, explicit target tenant and realm or instance, bounded action type, reason, audit record, and the same stable idempotency and owner-workflow contracts as the corresponding routine mutation.

Break-glass can force closure, suspension, drain, stop, cleanup, rollback, or another explicitly supported recovery action when platform safety, abuse, billing, or incident response requires it. It may initiate a launch or cutover only through the canonical preflight and lifecycle path and cannot override integrity or readiness failures. Break-glass grants no gameplay actor, hidden player presence, content authorship, or continuing tenant role.

### Delegation Is Deferred and Risk-Sensitive

V1 does not add fine-grained delegated lifecycle roles. `tenantAdmin` remains accountable for routine lifecycle actions. When creator teams demonstrate a concrete need, delegation may be introduced as explicit bounded capabilities rather than broadening `designer` or `moderator`.

Any future playtest delegation distinguishes low-risk fresh or published-seed playtests from access to production-derived snapshots. Authority to launch or reset `fresh` or `seeded` playtests does not imply permission to select, read, copy, or operate production-derived snapshot state. Production snapshot delegation requires a separately authorized data-bearing capability and audit contract.

## Consequences

- Tenant creators can launch, update, recover, and retire their own realms without routine platform-operator coordination.
- `tenantAdmin` becomes a consequential operational role and requires strong authentication, prompt revocation, tenant-scoped audit, and careful assignment.
- Lifecycle APIs must classify capacity creation, recovery, closure, repair, and audit instead of applying one broad availability guard.
- Failed entitlement or readiness checks cannot trap a tenant in a running or half-created state because closure, cleanup, and billing-safe repair remain reachable.
- Platform intervention remains possible but is conspicuous, reasoned, attributable, and cannot silently become tenant membership or bypass runtime correctness.
- Designers may publish independently from runtime operators, preserving separation of duties at the cost of requiring a tenant administrator for activation.
- Fine-grained team delegation is deferred, so larger creator organizations initially have a coarser administrative role model.

## Alternatives Considered

### Platform Operators Own Every Launch and Cutover

This centralizes consequential runtime changes and can simplify initial authorization. It makes ordinary creator operation depend on platform staffing, obstructs self-hosting, slows rollback and recovery, and turns operators into a product workflow bottleneck. It is rejected.

### Content Publication Grants Runtime Authority

Allow `designer` to launch or cut over any release they can publish. This reduces role ceremony but collapses content authorship, capacity spending, production operation, and data-bearing playtest access into one role. Publication is not runtime authorization and this alternative is rejected.

### Allow Tenant Administrators to Override Gates

Treat tenant ownership as authority to bypass billing, quota, compatibility, readiness, or safety blocks. This permits partial launches, unsupported release combinations, unentitled capacity, and unsafe reopen. Human authority selects an operation; it does not replace machine-verifiable integrity and policy prerequisites.

### Make Platform Administration an Implicit Tenant Role

Insert or interpret `platformAdmin` as `tenantAdmin` for every tenant. This simplifies reuse of routes but obscures who acted under what authority, risks accidental creator or gameplay access, and makes revocation and audit misleading. Break-glass remains a distinct route and audit class.

### Introduce Fine-Grained Lifecycle Roles Immediately

Create launcher, playtest manager, patch operator, rollback operator, and snapshot operator roles now. This could reduce privilege for large teams but adds authorization, UI, documentation, and support complexity without demonstrated v1 workflows. Explicit bounded capabilities may be added later, beginning with the production-data distinction for playtests.

## Implementation and Proof Obligations

The current implementation is not aligned. Lifecycle mutations remain exposed primarily as internal or operator-oriented hooks, and several guards accept broad operator or moderator authority rather than proving caller-bound `tenantAdmin` ownership or a distinct reasoned break-glass path. Complete entitlement enforcement, tenant-facing lifecycle surfaces, action-class gating, failed-launch cleanup authority, and break-glass audit proof are absent or partial.

Implementation must classify every lifecycle route by routine tenant authority, break-glass authority, and operation class. It must use authoritative tenant membership, immediate role-revocation behavior, typed idempotent lifecycle requests, owner-local fencing, and immutable audit attribution. Production and capacity-increasing paths must compose publication, attestation, entitlement freshness, quota, compatibility, remap, and readiness proof without allowing one successful check to stand in for another. Closure, cleanup, billing repair, and audit paths must not inherit a blanket gameplay-availability denial.

Proof must cover every listed routine operation as `tenantAdmin`; rejection of `designer`, `moderator`, unrelated-tenant administrators, `support`, and `billingAdmin`; immediate role revocation; tenant scope inferred from instance identity; duplicate and conflicting request identities; stale lifecycle epochs; and concurrent launch, stop, cutover, rollback, and retirement.

Gate proof must cover unpublished or unattested releases, incompatible tuples, missing remaps, stale readiness, entitlement outage, hard billing denial, quota excess, safety suspension, and successful cleanup after failed preparation. It must show stop, close, cleanup, billing repair, and audit remain reachable under the correct authority while start or expansion is blocked. Break-glass proof must cover mandatory reason and actor attribution, no tenant impersonation, no gameplay or design grant, no integrity bypass, forced suspension, and ordinary-gated reopen. Future delegated playtest proof must distinguish fresh or seeded operation from production-derived snapshot access.

## Reversibility and Revisit Triggers

Lifecycle workflow implementation, route shape, and audit storage may evolve while preserving tenant-owned routine authority, distinct platform break-glass, gate composition, and recovery availability. Revisit fine-grained delegation when real creator teams need separation below `tenantAdmin`; production-derived snapshot access remains a separate data-bearing capability. Revisit marketplace or public-discovery coupling only through their own product and safety decisions, not by broadening lifecycle authority.

## Required Documentation Alignment

- [Creator journeys](../../product/user-journeys/creators.md)
- [Operator journeys](../../product/user-journeys/operators.md)
- [Versioning and runtime](../system-architecture-versioning-runtime.md)
- [Authentication](../system-architecture-authentication.md)
