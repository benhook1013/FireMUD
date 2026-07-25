# ADR 0025: Explicit Open-Enrollment Membership

## Status

Accepted

## Implementation Status

The accepted explicit-join decision is target state. Current runtime paths still have implicit membership behavior in connect-token issuance and `PLAY`, and the membership transaction, monotonic versioning, and durable audit/outbox boundary remain incomplete. No runtime completion is claimed by this ADR.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.2` Tenant membership, invitations, and player roles
- Affected capabilities: `AA-1.1`, `AA-3.2`, `AA-2.1`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-06`

## Context

A tenant may deliberately expose one default production realm for public discovery. Joining that game should create a durable Account-owned player membership: the relationship powers the player's game library, later discovery, return-to-game flows, creator membership views, moderation, and account/tenant data handling.

The previous flow created membership implicitly during first-party connect-token issuance or text-client `PLAY`. That makes a durable relationship an invisible transport side effect and can create membership when a token, socket, character flow, or `PLAY` is abandoned. The current implementation also separates Redis replay state and best-effort audit logging from the membership transaction.

## Decision

### Open Enrollment With Explicit Intent

- A realm explicitly marked as the tenant's public production realm is open enrollment for authenticated platform accounts. Joining does not require an invitation, creator approval, or an existing tenant role.
- The player must explicitly invoke the Account-owned public-production join operation, surfaced as `JOIN <world>` or an equivalently clear `Join & Play` control, before membership is created. Discovery may show the public game before membership exists.
- The join action creates or returns the Account-owned tenant `player` membership. This is an intended durable product relationship, not temporary admission state.
- Successful membership powers the player's “my games”/return discovery even if the first connection or later `PLAY` attempt fails. A failed join transaction creates nothing.
- Connect-token issuance, character creation, and `PLAY` require the resulting membership and never create it implicitly. If it is missing, they return `JOIN_REQUIRED` with recovery guidance.
- `IssueConnectToken` must re-read the current caller-bound membership and membership authority generation at issuance time. A stale bootstrap token, discovery result, or cached membership decision cannot issue a connect token after membership authority has advanced or been revoked.
- Fresh authority failure semantics are explicit: a fresh authoritative entitlement result that records a billing denial, grace state, or any state that does not allow public joining returns `TENANT_BILLING_BLOCKED`; inability to establish fresh authoritative entitlement returns `ENTITLEMENT_UNAVAILABLE` instead. A stale, future-dated, target-mismatched, version-mismatched, incomplete, or otherwise unsafe result cannot authorize a join and, if it cannot be replaced by fresh authority, follows the unavailable outcome.
- Non-public and non-production realms retain their explicit Account-owned grant requirements and cannot use open enrollment.

### Membership Transaction

Account Service is the sole join writer. The canonical operation is `JoinPublicProductionMembership`, surfaced through text `JOIN`, first-party `POST /auth/bootstrap/join` / `Join & Play`, and the internal Account join boundary. It accepts caller-bound account identity plus `{tenantId, worldSlug, realmSlug, requestId}` and:

- revalidates that the realm is still the explicit public production realm, publicly visible, entitlement-eligible, and backed by an unambiguous current admission pointer;
- obtains a fresh ADR 0028 entitlement evaluation/snapshot immediately before the membership commit. The evaluation must be fresh at the commit gate, must authorize explicit public join, and must be tied to the current caller, target, and entitlement authority version; a failed refresh returns `ENTITLEMENT_UNAVAILABLE`, and a stale, future-dated, mismatched, or otherwise unsafe snapshot cannot authorize the join;
- treats `requestId` as the idempotency identity and makes concurrent joins converge on one membership;
- advances a monotonic `membershipVersion` rather than exposing the membership row ID as a change version;
- commits the membership and durable audit/outbox event in one database transaction; and
- returns the existing successful membership for an already joined account without creating duplicate audit history.

Redis may accelerate replay responses but is not the join transaction or audit authority. A successful join remains successful if the subsequent token, socket, character creation, or gameplay admission fails.

### Lifecycle and Abuse Controls

- Closing public enrollment blocks new joins but does not revoke existing memberships. Existing members remain in the player's game library and retain access subject to current membership, moderation, realm, and entitlement policy.
- Account provides a clear caller-bound leave-game surface. Leaving removes future membership-based admission and discovery; character, audit, purchase, and legally required retained data follow their owning retention decisions rather than being silently deleted.
- Join creation is rate-limited and observable by account, source, and tenant using bounded abuse controls. Creators receive meaningful member counts that distinguish durable joined members from current online presence.

## Consequences

- Public games remain one deliberate click or command away with no creator approval queue.
- Tenant membership accurately means “this account joined this game” and supports return discovery.
- One explicit onboarding action is added before character creation, connect-token issuance, and `PLAY` for first-time players.
- A successful join can legitimately remain after later connection failure; this is no longer classified as partial admission state.
- Account needs transactional outbox/audit, a real monotonic membership version, leave semantics, and join abuse controls.

## Alternatives Considered

### Implicit Membership During Connect or `PLAY`

This removes one interaction but creates durable membership from a transport/admission attempt, makes consent unclear, and produces abandoned membership rows when later steps fail.

### Invitation or Creator Approval for Every Join

This gives creators maximum control but contradicts the deliberately public production-realm product and adds approval/moderation workflow before ordinary play.

### Ephemeral Visitor Access Without Membership

This avoids durable rows for casual visits but creates a second class of gameplay authority and weakens game-library, moderation, ownership, character, and return-to-game semantics.

## Implementation and Proof Obligations

- Add explicit browser/mobile join and text `JOIN` flows before first character creation/connect/`PLAY`.
- Converge the implicit `EnsurePublicProductionPlayerMembership` call sites onto the explicit join operation; `POST /auth/connect-token` and `PLAY` must not write membership.
- Commit membership plus durable audit/outbox atomically and make SQL membership/operation state authoritative for replay.
- Gate every new membership commit on the immediately preceding fresh ADR 0028 entitlement evaluation. Prove that a failed refresh returns `ENTITLEMENT_UNAVAILABLE`, and that no membership, audit, or outbox record is committed after a failed, stale, future-dated, mismatched, or otherwise invalid evaluation, including evaluation/commit races.
- Implement monotonic membership versioning and prove races/retries return one membership and one logical join event.
- Prove a successful join survives later token/socket/`PLAY` failure, while a failed join creates no membership, audit event, character, or admission state.
- Prove closing enrollment blocks new joins without silently removing existing members, and prove the leave surface removes future membership authority without over-deleting retained data.
- Prove rate limits and abuse telemetry without high-cardinality public metrics.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-frontend.md`
- `design/architecture/system-architecture-authz-route-matrix.md`
- `design/architecture/user-journeys-players.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/game-session-service/protocols.md`
