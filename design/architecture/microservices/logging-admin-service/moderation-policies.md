# Moderation Policies

This file defines the moderation-policy boundary and example policy categories for hosted FireMUD games. Operators may adapt policy definitions to community needs, but recording or evaluating a policy action is not itself destructive enforcement.

For details on moderation tooling see the [Logging & Admin Service overview](./README.md), [API contracts](./api-contracts.md), and [runtime model](./runtime-and-data.md).

## Core Policies

1. **Hate Speech** - policy definitions may classify discriminatory language and recommend sanctions for an authorized moderator or owning runtime to apply.
2. **Profanity Filtering** - a tenant may define chat-filtering policy for Social & Groups to enforce at its authoritative send boundary. See the [Social & Groups Service design](../social-groups-service/README.md) for the communication-delivery contract.
3. **Harassment and Threats** - policy definitions may classify threats and recommend bounded account or gameplay consequences; Account and Game Session remain the authoritative enforcement owners.
4. **Spam Prevention** - Social & Groups may enforce configured chat-send limits and mute policy at its authoritative boundary.
5. **Cheating and Exploits** - policy evaluation may produce an auditable recommendation, while the runtime that owns the affected capability applies any consequence.

## Profanity Filters

Tenant-configurable word lists, normalization, masking, and bypass detection belong to the Social & Groups chat-send policy boundary. These are target policy capabilities rather than evidence that automatic filtering, tenant word-list administration, or Unicode-lookalike detection is currently implemented.

## Enforcement Workflow

1. Offending logs or reports are flagged in the Logging & Admin Service dashboards. These dashboards are described in [Analytics Dashboards](./analytics-dashboards.md).
2. Moderators review the context and determine the severity.
3. Actions enter through `ApplyModerationAction` gRPC calls (see [`logging_admin_service.proto`](../../../../protos/logging-admin/v1/logging_admin_service.proto)). Punitive ingress accepts only `platform_access_ban`, `gameplay_ban`, `chat_mute`, or `chat_ban`; unknown and legacy generic strings fail closed before successful intent recording or forwarding. Logging & Admin durably records the actor, safe notice/reason, exact subject and scope, case and source action, effective/expiry time, monotonic revision, payload digest, and one idempotency identity before forwarding. Recording a case or evaluating policy alone does not enforce a restriction.
4. Under [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md), Logging & Admin sends the same scoped digest-bound idempotent command to the owner: Account for `platform_access_ban`, Game Session for `gameplay_ban`, and Social & Groups for `chat_mute` or `chat_ban`. The owner validates current authority and scope and atomically persists a subject/category/scope monotonic enforcement record with its idempotent result. Logging & Admin reports success only after owner acknowledgement.
5. A later expiry, removal, or correction is another monotonic owner command. Delayed, duplicated, or reordered delivery cannot remove a newer restriction or restore an older one.
6. Account separately owns protective `account_security_lock`, authentication generations, credential state, security recovery, and account-wide token/session revocation. Successful security recovery clears only that protective lock. Punitive `platform_access_ban` is also enforced by Account but is not cleared by credential reset or recovery; it changes only through another authorized moderation revision. Logging & Admin owns the deliberately bounded moderation appeal case; Account authenticates the affected player and provides notifications and browser handoff without becoming the moderation-case owner.

The complete fixed taxonomy and stacking rules are defined in [ADR 0141](../../decisions/adr-0141-fixed-safety-restriction-categories-and-independent-lifecycles.md). `gameplay_ban` always names an exact tenant or tenant-and-realm scope. `chat_mute` and `chat_ban` name an exact tenant, realm, or channel scope. Every category stacks independently; expiry, removal, recovery, or correction of one never changes another.

## Runtime Enforcement Semantics

- Game Session reads its own durable `gameplay_ban` state for `PLAY` and command admission. A newly committed ban stops new command admission and closes the active gameplay binding. Work already durably admitted may finish idempotently so enforcement does not create partial domain writes.
- Social & Groups reads its own durable restriction state. `chat_mute` blocks sending while ordinary receipt remains available. `chat_ban` blocks ordinary participation, sending, and history access; essential system and moderation notices remain deliverable.
- These routine decisions do not call Logging & Admin. Owners begin with indexed local database reads and add a local cache only if measurements justify it; any cache remains rebuildable and non-authoritative.
- Failure to read required owner-local enforcement state fails closed. Logging & Admin, audit-reporting, analytics, or observability outages do not block runtime enforcement while owner-local state remains readable.

Player block/ignore relationships and personal mute preferences remain Social-owned player safety features, not staff restriction categories. Player reports are evidence and case ingress only and never create an enforcement record automatically.

## Appeals

Every moderation policy declares appeal eligibility. Severe or long-lived punitive restrictions require an appeal path. A brief automatically expiring mute may be ineligible for a full case when the policy instead supplies clear notice and expiry. Filing never pauses or weakens enforcement.

Logging & Admin owns a small appeal lifecycle: `SUBMITTED`, `UNDER_REVIEW`, and terminal `DECIDED`. A decision outcome is `UPHELD`, `MODIFIED`, or `OVERTURNED`. Ineligible submissions return an explicit outcome without creating a case. The case binds the exact enforcement owner, subject, tenant or platform scope, restriction type, restriction revision and digest, originating moderation action/case, evidence references, appellant submission, jurisdiction, and final review decision.

Tenant-scoped gameplay and communication restrictions are reviewed under the applicable tenant policy by authorized tenant moderation actors. Platform-wide restrictions and moderation-originated account-security restrictions use platform jurisdiction. Account-owned credential recovery is not reclassified as a moderation appeal. Platform escalation of a tenant case exists only when an explicit platform policy grants it.

An upheld decision creates no enforcement mutation. A modified or overturned decision sends the applicable owner a new monotonic, digest-bound command referencing the appeal case and exact appealed restriction revision. The owner advances current enforcement state without rewriting either the original action or later unrelated restrictions. Appeal and enforcement histories remain append-only.

Account authenticates the player, sends an essential notice, and provides the HTTPS browser handoff. Telnet or another gameplay client receives only an opaque, short-lived URL for submission or status; the URL contains no evidence, reporter identity, credential, or decision authority and still requires independent Account authentication. Essential notices disclose only safe restriction scope, effect/expiry, appeal eligibility, and status guidance.

Submission is idempotent and permits at most one active appeal for the exact account and restriction revision. Account-, tenant-, and status-polling rate limits return bounded retry guidance without revealing another case. Each policy declares case/evidence access, finite retention, redaction, legal-hold exception, export treatment, and terminal erasure or minimization. Player status/export may include the player's own submission and safe decision summary but never protected evidence or reporter identity.

This is not a general tribunal. V1 does not require multiple reviewers, arbitrary hearings, discovery, cross-tenant case browsing, or one universal response-time SLA. No complete appeal ingress, case lifecycle, evidence-reference, review, outcome-command, notice/handoff, or end-to-end proof currently exists.
