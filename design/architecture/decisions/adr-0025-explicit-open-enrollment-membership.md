# ADR 0025: Explicit Open-Enrollment Membership

## Status

Accepted

## Implementation Status

The accepted explicit-join decision is target state. Until it converges, current first-party connect-token issuance and text `PLAY` can invoke `EnsurePublicProductionPlayerMembership`, which creates or returns implicit public-production membership when it is absent. Target connect-token issuance and `PLAY` instead require existing membership and return `JOIN_REQUIRED`; neither target path invokes the membership writer. Explicit `JOIN` / `Join & Play`, the membership transaction, monotonic versioning, durable audit/outbox boundary, and the exact current membership snapshot plus independent `membershipVersion` and `membershipAuthorityGeneration` reread at every connect-token issuance remain incomplete. No runtime completion is claimed by this ADR.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.2` Tenant membership, invitations, and player roles
- Affected capabilities: `AA-1.1`, `AA-3.2`, `AA-2.1`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-06`

## Context

A tenant may deliberately expose one default production realm for public discovery. Joining that game should create a durable Account-owned player membership: the relationship powers the player's game library, later discovery, return-to-game flows, creator membership views, moderation, and account/tenant data handling.

The current flow creates membership implicitly during first-party connect-token issuance or text-client `PLAY` through `EnsurePublicProductionPlayerMembership`. That makes a durable relationship an invisible transport side effect and can create membership when a token, socket, character flow, or `PLAY` is abandoned. The current implementation also separates Redis replay state and best-effort audit logging from the membership transaction.

## Decision

### Open Enrollment With Explicit Intent

- A realm explicitly marked as the tenant's public production realm is open enrollment for authenticated platform accounts. Joining does not require an invitation, creator approval, or an existing tenant role.
- The player must explicitly invoke the Account-owned public-production join operation, surfaced as `JOIN <world>` or an equivalently clear `Join & Play` control, before membership is created. Discovery may show the public game before membership exists.
- The join action applies the Account-owned membership lifecycle: a missing relationship creates `ACTIVE`; an `INACTIVE` relationship is restored to `ACTIVE`, advancing both `membershipAuthorityGeneration` and `membershipVersion`; each create or restore transition commits exactly one logical transition audit/outbox event; an `ACTIVE` relationship returns its exact current snapshot idempotently without another row, audit/outbox event, or authority/version advance; every other lifecycle state rejects. This is an intended durable product relationship, not temporary admission state.
- Successful membership powers the player's “my games”/return discovery even if the first connection or later `PLAY` attempt fails. A failed join transaction creates nothing.
- For a public-production realm, connect-token issuance, character creation, and `PLAY` require the resulting `ACTIVE` membership and never create or restore it implicitly. If membership is missing or `INACTIVE`, they return `JOIN_REQUIRED` with recovery guidance. `JOIN_REQUIRED` is not a private, playtest, or other non-production enrollment path.
- `IssueConnectToken` must obtain an exact fresh current caller-bound membership snapshot from Account at the issuance commit gate on every issuance, including the first issuance immediately after a successful join, and compare `membershipLifecycleState`, independent `membershipVersion`, and opaque monotonic `membershipAuthorityGeneration` from that same snapshot before issuing. A stale bootstrap token, discovery result, or cached membership decision cannot substitute, even when its generation appears current; a stale, unavailable, regressed, or mismatched snapshot fails closed.
- Fresh authority failure semantics are explicit: only a fresh authoritative entitlement result that records a billing denial or a billing grace state that blocks joining returns `TENANT_BILLING_BLOCKED`. A fresh nonbilling public-production policy or admission denial returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`. Inability to establish fresh authoritative entitlement, or an unavailable, stale, future-dated, target-mismatched, version-mismatched, incomplete, or otherwise unsafe entitlement result that cannot be replaced by fresh authority, returns `ENTITLEMENT_UNAVAILABLE`.
- Authority availability is separate from the membership state and entitlement decision. An unavailable membership, registry, or other required non-routing, non-entitlement authority cannot be interpreted as missing or `INACTIVE` membership and returns `AUTH_UNAVAILABLE`; an unavailable or unsafe entitlement authority returns `ENTITLEMENT_UNAVAILABLE`. Only an available authoritative membership result can produce `JOIN_REQUIRED`.
- Private, playtest, and other non-public or non-production realms require an active tenant membership and current realm-specific grant/entitlement before connect-token issuance, character creation, or `PLAY`; they never expose public-production `JOIN` or return `JOIN_REQUIRED`.
- Any reconnect or return shortcut that skips explicit join or character selection requires a complete unexpired discovery snapshot, a fresh authoritative `ACTIVE` membership with both membership version fields, and a valid current character for the resolved target. A cached or expired selection cannot authorize the shortcut; missing or `INACTIVE` membership returns `JOIN_REQUIRED`, while an absent, stale, or invalid character requires current character discovery or creation.

### Membership Transaction

Account Service is the sole join writer. The canonical operation is `JoinPublicProductionMembership`, surfaced through credential-bearing text `JOIN`, first-party `POST /auth/bootstrap/join` / `Join & Play`, and the internal Account join boundary. It accepts caller-bound account identity plus `{connectScopeId, requestId}` and:

- resolves and verifies `connectScopeId` for the caller, then revalidates that the selected realm is still the explicit public production realm, publicly visible, entitlement-eligible, and backed by an unambiguous current admission pointer; raw client-supplied tenant, world, realm, or game-instance fields are not an authority substitute for the verified selector;
- obtains a fresh ADR 0028 entitlement evaluation/snapshot immediately before the membership commit. The evaluation must be fresh at the commit gate, must authorize explicit public join, and must be tied to the current caller, target, and entitlement authority version; a failed refresh returns `ENTITLEMENT_UNAVAILABLE`, and a stale, future-dated, mismatched, or otherwise unsafe snapshot cannot authorize the join;
- binds `requestId` to a versioned target digest containing `JoinPublicProductionMembership`, `accountId`, the verified `connectScopeId`, and the resolved `{tenantId, worldSlug, realmSlug, gameInstanceId, catalogRevision, pointerVersion}`. Reusing a request ID with a different operation, account, selector, or resolved target is an idempotency conflict; concurrent matching joins converge on one membership and one logical join outcome;
- applies the lifecycle transition atomically: a missing relationship creates `ACTIVE`; `INACTIVE` restores to `ACTIVE` and advances both `membershipAuthorityGeneration` and `membershipVersion`; each create or restore commits exactly one logical transition audit/outbox event; `ACTIVE` returns the exact current snapshot without another row, audit/outbox event, or authority/version advance; every other state rejects without membership, audit, or outbox changes and never admits gameplay;
- advances a monotonic `membershipVersion` rather than exposing the membership row ID as a change version;
- advances the caller-bound `membershipAuthorityGeneration` when the membership or tenant-role authority changes, separately from the membership content/version counter;
- binds the verified `connectScopeId`, exact target digest, and both `catalogRevision` and `pointerVersion`, together with the immediately preceding fresh entitlement evaluation, to the membership commit. The Account transaction conditionally commits only while the selector still resolves to the same target and exact catalog/pointer pair and the entitlement authority remains current, together with the membership, operation outcome, and one transition audit/outbox event for a create or restore; an authority, selector, target-digest, catalog, or pointer race, or an uncertain evaluation, commits none of them; and
- returns the existing successful `ACTIVE` membership for an already joined account without creating duplicate audit history or any new event; an `INACTIVE` relationship follows the restore transition above rather than the event-free idempotent path.

Redis may accelerate replay responses but is not the join transaction or audit authority. A successful join remains successful if the subsequent token, socket, character creation, or gameplay admission fails.

### Lifecycle and Abuse Controls

- Closing public enrollment blocks new joins but does not revoke existing memberships. Existing members remain in the player's game library and retain access subject to current membership, moderation, realm, and entitlement policy.
- Account provides a clear caller-bound leave-game surface. `LeaveTenantMembership` is an idempotent Account transaction keyed by the caller-bound account/tenant and an operation request ID plus digest. It conditionally removes membership-based admission and discovery, advances both `membershipVersion` and `membershipAuthorityGeneration`, and commits the operation outcome plus one durable audit/outbox event atomically. A matching retry or already-left membership returns the stored/no-op outcome; a conflicting request digest fails without changing the existing evidence. Consumers fence existing admission artifacts from the committed authority generation, while character, audit, purchase, and legally required retained data follow their owning retention decisions rather than being silently deleted.
- Join creation is rate-limited and observable by account, source, and tenant using bounded abuse controls. Creators receive meaningful member counts that distinguish durable joined members from current online presence.

## Consequences

- Public games remain one deliberate click or command away with no creator approval queue.
- Tenant membership accurately means “this account joined this game” and supports return discovery.
- One explicit onboarding action is added before character creation, connect-token issuance, and `PLAY` for first-time players.
- A successful join can legitimately remain after later connection failure; this is no longer classified as partial admission state.
- Account needs transactional outbox/audit, a real monotonic membership version, leave semantics, and join abuse controls.

## Alternatives Considered

### Implicit Membership During Connect or `PLAY`

This is the current `EnsurePublicProductionPlayerMembership` compatibility behavior, but it removes one interaction by creating durable membership from a transport/admission attempt, makes consent unclear, and produces abandoned membership rows when later steps fail. The accepted target therefore rejects it in favor of explicit `JOIN`.

### Invitation or Creator Approval for Every Join

This gives creators maximum control but contradicts the deliberately public production-realm product and adds approval/moderation workflow before ordinary play.

### Ephemeral Visitor Access Without Membership

This avoids durable rows for casual visits but creates a second class of gameplay authority and weakens game-library, moderation, ownership, character, and return-to-game semantics.

## Implementation and Proof Obligations

- Add explicit browser/mobile join and text `JOIN` flows before first character creation/connect/`PLAY`.
- Carry the verified `connectScopeId` through `JoinPublicProductionMembership`, bind the exact `catalogRevision` and `pointerVersion` pair into the versioned request/target digest and committed transition evidence, and prove the digest, selector, catalog, and pointer are rechecked at the membership commit gate.
- Replace the current differently named proto seam `EnsurePublicProductionPlayerMembership` with the canonical `JoinPublicProductionMembership` operation rather than retaining a compatibility adapter. Removal is complete only after the Account proto/service, authenticated caller, Gateway/auth routing and allowlists, configuration, tests, and generated references use the canonical operation and the old symbol is absent. In the target state, `POST /auth/connect-token` and `PLAY` must require membership and must not write it.
- Prove every `IssueConnectToken` call, including the first call after explicit join, rereads the exact current caller-bound runtime membership snapshot at its Account issuance commit gate, including `membershipLifecycleState`, independent `membershipVersion`, and opaque `membershipAuthorityGeneration`; cached or bootstrap evidence cannot substitute even when its generation appears current.
- Commit membership, its `membershipAuthorityGeneration`/`membershipVersion` changes, operation outcome, and exactly one transition audit/outbox event for create or restore atomically; an `ACTIVE` retry must remain event-free, and SQL membership/operation state is authoritative for replay.
- Gate every new membership commit on the immediately preceding fresh ADR 0028 entitlement evaluation. Prove that unavailable or unsafe entitlement authority returns `ENTITLEMENT_UNAVAILABLE`, billing denial or a billing grace state that blocks joining returns `TENANT_BILLING_BLOCKED`, and a nonbilling public-production policy denial returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`; no membership, audit, or outbox record is committed after a failed, stale, future-dated, mismatched, or otherwise invalid evaluation, including evaluation/commit races. Prove that public-production alone can return `JOIN_REQUIRED` or invoke public `JOIN`, while private/playtest/non-production admission requires active membership and its current grant/entitlement and never invokes public join.
- Implement monotonic membership versioning and prove create/restore races and retries return one membership and one logical transition event, while an `ACTIVE` retry returns the exact snapshot without an event.
- Prove a successful join survives later token/socket/`PLAY` failure, while a failed join creates no membership, audit event, character, or admission state.
- Prove closing enrollment blocks new joins without silently removing existing members, and prove the leave surface removes future membership authority without over-deleting retained data.
- Prove rate limits and abuse telemetry without high-cardinality public metrics.

## Required Documentation Alignment

- [Authentication and authorization](../system-architecture-authentication.md)
- [Frontend architecture](../system-architecture-frontend.md)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)
- [Player journeys](../user-journeys-players.md)
- [Account Service API contracts](../microservices/account-service/api-contracts.md)
- [Account Service runtime and data](../microservices/account-service/runtime-and-data.md)
- [Game Session protocols](../microservices/game-session-service/protocols.md)
