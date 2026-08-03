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
- The join action applies the Account-owned membership lifecycle: a missing relationship creates `ACTIVE` and an `INACTIVE` relationship is restored to `ACTIVE`; either transition advances `membershipVersion`, while `membershipAuthorityGeneration` advances independently only when the same committed snapshot records `callerBoundAuthorityInvalidated=true`; each create or restore transition commits exactly one logical transition audit/outbox event. An `ACTIVE` relationship returns its exact current snapshot without another row, transition event, or authority/version advance; every other lifecycle state rejects. The operation evidence and retry rules are defined by the Membership Transaction below. This is an intended durable product relationship, not temporary admission state.
- Successful membership powers the player's “my games”/return discovery even if the first connection or later `PLAY` attempt fails. A definitively failed join creates no membership, transition audit/outbox event, character, or admission state, but it persists the ADR 0042 operation ID, digest, status, and deterministic terminal failure outcome. An uncertain authority or commit outcome does not imply rollback: it persists `PENDING` evidence for reconciliation.
- For a public-production realm, connect-token issuance, character creation, and `PLAY` require the resulting `ACTIVE` membership and never create or restore it implicitly. If membership is missing or `INACTIVE`, fresh Account entitlement must return `allowPublicJoin=true` and fresh selected-target catalog/pointer evidence must identify the visible public-production target before the flow returns `JOIN_REQUIRED`; entitlement outage returns `ENTITLEMENT_UNAVAILABLE`, while `allowPublicJoin=false` returns `PUBLIC_PRODUCTION_ADMISSION_DENIED` without membership mutation. `JOIN_REQUIRED` is not a private, playtest, or other non-production enrollment path.
- `IssueConnectToken` must obtain an exact fresh current caller-bound membership snapshot from Account at the issuance commit gate on every issuance, including the first issuance immediately after a successful join, and compare `membershipLifecycleState`, independent `membershipVersion`, and opaque monotonic `membershipAuthorityGeneration` from that same snapshot before issuing. A stale bootstrap token, discovery result, or cached membership decision cannot substitute, even when its generation appears current; a stale, unavailable, regressed, or mismatched snapshot fails closed.
- Fresh authority failure semantics are explicit: only a fresh authoritative entitlement result that records a billing denial or a billing grace state that blocks joining returns `TENANT_BILLING_BLOCKED`. A fresh nonbilling public-production policy or admission denial returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`. Inability to establish fresh authoritative entitlement, or an unavailable, stale, future-dated, target-mismatched, version-mismatched, incomplete, or otherwise unsafe entitlement result that cannot be replaced by fresh authority, returns `ENTITLEMENT_UNAVAILABLE`.
- Authority availability is separate from the membership state and entitlement decision. An unavailable membership, registry, or other required non-routing, non-entitlement authority cannot be interpreted as missing or `INACTIVE` membership and returns `AUTH_UNAVAILABLE`; an unavailable or unsafe entitlement authority returns `ENTITLEMENT_UNAVAILABLE`. Only an available authoritative membership result can produce `JOIN_REQUIRED`.
- Private, playtest, and other non-public or non-production realms require an active tenant membership and current realm-specific grant/entitlement before connect-token issuance, character creation, or `PLAY`; they never expose public-production `JOIN` or return `JOIN_REQUIRED`.
- Any reconnect or return shortcut that skips both explicit join and character selection requires a complete unexpired discovery snapshot, a fresh authoritative `ACTIVE` membership with both membership version fields, and a valid current character for the resolved target. An unavailable membership, registry, grant, or other required non-entitlement authority returns `AUTH_UNAVAILABLE` and is retried before missing/`INACTIVE` membership can produce `JOIN_REQUIRED` or character repair can begin. An `ACTIVE` member may skip only the join step and proceed through current character discovery or creation. A cached or expired selection cannot authorize the full shortcut; missing or `INACTIVE` membership returns `JOIN_REQUIRED` only after the fresh authority gates pass, while an absent, stale, or invalid character requires current character discovery or creation.

### Membership Transaction

Account Service is the sole join writer. The canonical operation is `JoinPublicProductionMembership`, surfaced through credential-bearing text `JOIN`, first-party `POST /auth/bootstrap/join` / `Join & Play`, and the internal Account join boundary. Public player-bootstrap surfaces derive account identity from the authenticated caller. The internal boundary accepts only the exact Game Session mTLS workload with typed caller-bound `PlayerExecutionContext`, and Account validates that context's account identity before using the target-only scope. The operation accepts caller-bound account identity plus `{connectScopeId, requestId}` and:

- resolves and verifies `connectScopeId` for the caller, then revalidates the same fresh authoritative Game Session routing snapshot for selected-target catalog/pointer evidence: the selected realm is still the explicit public production realm, publicly visible, and backed by an unambiguous current admission pointer. `catalogRevision` and `pointerVersion` must both be present in that snapshot; missing, unavailable, malformed, ambiguous, stale, or regressed routing evidence returns `ADMISSION_POINTER_UNAVAILABLE`, while a changed scope-bound pair returns `CONNECT_SCOPE_MISMATCH`. `catalogRevision` comes from the authoritative Game Session routing record's reference to the separately versioned catalog/policy snapshot; it is not sourced from or substituted by Account entitlement. Raw client-supplied tenant, world, realm, or game-instance fields are not an authority substitute for the verified selector;
- obtains a fresh ADR 0028 entitlement evaluation/snapshot immediately before the membership commit. The [Account runtime membership and entitlement authority contract](../microservices/account-service/runtime-and-data.md#membership-and-entitlement-authority) owns the response shape. The evaluation must be fresh at the commit gate, must authorize explicit public join with `allowPublicJoin=true`, and must be tied to the current caller, target, and entitlement authority version; a failed refresh returns `ENTITLEMENT_UNAVAILABLE`, a definitive `allowPublicJoin=false` returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`, and a stale, future-dated, mismatched, or otherwise unsafe snapshot cannot authorize the join. This Account entitlement gate is separate from the selected-target catalog/pointer/public-production gate;
- binds `requestId` to a versioned target and policy digest whose canonical preimage explicitly contains `operationKind=JOIN`, authenticated `accountId`, the verified caller binding, the verified `connectScopeId`, the resolved `{tenantId, worldSlug, realmSlug, gameInstanceId, catalogRevision, pointerVersion}`, and the exact authoritative `allowPublicJoin` result plus its Account entitlement `entitlementVersion`. Account persists those fields with the operation record. Reusing a request ID with a different operation, account, selector, resolved target, policy result, or policy version returns `IDEMPOTENCY_CONFLICT`; a stale or mismatched verified scope returns `CONNECT_SCOPE_MISMATCH`; concurrent matching joins converge on one membership and one logical join outcome;
- applies the lifecycle transition atomically: a missing relationship creates `ACTIVE` and `INACTIVE` restores to `ACTIVE`; either transition advances `membershipVersion`, while `membershipAuthorityGeneration` advances only when `callerBoundAuthorityInvalidated=true`; each create or restore commits exactly one logical transition audit/outbox event; `ACTIVE` returns the exact current snapshot without another row, transition event, or authority/version advance. For every attempt, including an idempotent retry, a failed outcome, or an uncertain outcome, the ADR 0042 operation ID, digest, status, and outcome are durable retry/reconciliation evidence. Every other state rejects without a membership transition or transition audit/outbox event but persists that operation outcome and never admits gameplay; an uncertain authority or commit outcome remains `PENDING` rather than being classified as a failed terminal result;
- advances a monotonic `membershipVersion` rather than exposing the membership row ID as a change version;
- advances the caller-bound `membershipAuthorityGeneration` when the membership or tenant-role authority changes, separately from the membership content/version counter;
- binds the verified `connectScopeId`, exact target/policy digest, and both `catalogRevision` and `pointerVersion`, together with the immediately preceding fresh entitlement evaluation, to the membership commit. The Account transaction conditionally commits a create or restore only while the selector still resolves to the same target and exact catalog/pointer pair, the catalog still identifies the public-production realm, the Account entitlement still has `allowPublicJoin=true` with the exact bound `entitlementVersion`, and that entitlement authority remains current. A create or restore commits membership, its operation outcome, and one transition audit/outbox event atomically; a definitive authority, selector, target-digest, policy, catalog, or pointer failure commits no membership or transition event and leaves a durable terminal failure outcome, while an uncertain evaluation or commit does not imply rollback and leaves `PENDING` operation evidence for reconciliation to the stored committed result or deterministic failure.
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
- Carry the verified `connectScopeId` through `JoinPublicProductionMembership`, bind the exact `catalogRevision` and `pointerVersion` pair plus the authoritative `allowPublicJoin` result and `entitlementVersion` into the versioned request/policy digest and committed transition evidence, and prove the policy, digest, selector, catalog, and pointer are rechecked at the membership commit gate. Prove that `JOIN_REQUIRED` is emitted only after an authoritative `allowPublicJoin=true` result; `allowPublicJoin=false` returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`, and stale or last-known-good entitlement cannot authorize join or restore `INACTIVE` membership.
- Replace the current differently named proto seam `EnsurePublicProductionPlayerMembership` with the canonical `JoinPublicProductionMembership` operation rather than retaining a compatibility adapter. Removal is complete only after the Account proto/service, authenticated caller, Gateway/auth routing and allowlists, configuration, tests, and generated references use the canonical operation and the old symbol is absent. In the target state, `POST /auth/connect-token` and `PLAY` must require membership and must not write it.
- Prove every `IssueConnectToken` call, including the first call after explicit join, rereads the exact current caller-bound runtime membership snapshot at its Account issuance commit gate, including `membershipLifecycleState`, independent `membershipVersion`, and opaque `membershipAuthorityGeneration`; cached or bootstrap evidence cannot substitute even when its generation appears current.
- Commit membership, its `membershipAuthorityGeneration`/`membershipVersion` changes, operation outcome, and exactly one transition audit/outbox event for create or restore atomically; an `ACTIVE` retry must remain event-free, and SQL membership/operation state is authoritative for replay.
- Gate every new membership commit on the immediately preceding fresh ADR 0028 entitlement evaluation. Prove that unavailable or unsafe entitlement authority returns `ENTITLEMENT_UNAVAILABLE`, billing denial or a billing grace state that blocks joining returns `TENANT_BILLING_BLOCKED`, and a nonbilling public-production policy denial returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`; no membership, audit, or outbox record is committed after a failed, stale, future-dated, mismatched, or otherwise invalid evaluation, including evaluation/commit races. Prove that public-production alone can return `JOIN_REQUIRED` or invoke public `JOIN`, while private/playtest/non-production admission requires active membership and its current grant/entitlement and never invokes public join.
- Implement monotonic membership versioning and prove create/restore races and retries return one membership and one logical transition event, while an `ACTIVE` retry returns the exact snapshot without an event.
- Prove a successful join survives later token/socket/`PLAY` failure, while a definitively failed join creates no membership, transition audit event, character, or admission state and still persists the ADR 0042 operation evidence; uncertain authority or commit does not imply rollback and remains `PENDING` until reconciliation.
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
