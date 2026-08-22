# ADR 0141: Fixed Safety Restriction Categories and Independent Lifecycles

## Status

Accepted

## Implementation Status

This decision is not implemented. Fixed category records, owner-local enforcement, independent stacking, category-specific notices, and focused player-safety proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SAFETY-01`
- Decision date: 2026-07-20
- Decision key: `SAFETY-01`
- Primary capability: `EA-2.4`
- Affected capabilities: `PO-1.2`, `AA-1.3`, `PO-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of protective account security, punitive platform enforcement, gameplay and communication scopes, independent restriction lifecycles, player notices, and owner-local enforcement

## Context

The earlier moderation taxonomy distinguished one account-wide ban, a gameplay ban, and chat restrictions. It incorrectly combined two account-wide states with different purposes and clearance rules: a protective lock for suspected credential compromise and a punitive platform-access ban. If both use a generic `account_ban`, ordinary credential recovery can appear to clear punishment, while punitive moderation can accidentally be treated as a security-recovery problem.

The existing code reinforces that ambiguity. Logging & Admin accepts free-form action strings including `ban` and `account_ban`, persists a generic tenant/account action, and answers synchronous policy reads from Game Session and Social & Groups. It does not represent exact category scope, independent stacking, monotonic category revisions, digest-bound owner commands, or category-specific notices. Account's complete protective-lock and punitive platform-ban paths are also not implemented.

The canonical [Moderation Policies](../microservices/logging-admin-service/moderation-policies.md) contract establishes that Logging & Admin owns external moderation ingress, cases, policy intent, and audit while the service protecting a boundary owns durable enforcement state. This decision fixes the restriction vocabulary and lifecycle carried through that owner-local model.

## Decision

### Five Fixed Categories

FireMUD supports exactly five safety restriction categories at this boundary:

| Category | Purpose | Enforcement owner | Required scope and behavior |
| --- | --- | --- | --- |
| `account_security_lock` | Protective response to verified or high-confidence credential or account compromise | Account Service | Global account subject. Revokes ordinary account and bootstrap authority and is cleared only by the Account-owned security-recovery workflow. It is not punitive moderation. |
| `platform_access_ban` | Punitive denial of ordinary FireMUD platform access | Account Service | Global account subject across tenants. Credential reset or successful security recovery never clears it; only a later authorized moderation revision may modify, expire, or remove it. |
| `gameplay_ban` | Punitive denial of gameplay | Game Session Service | Exact tenant scope or exact tenant-and-realm scope. It fences `PLAY`, new gameplay command admission, and active bindings covered by that scope. |
| `chat_mute` | Restriction on sending communication | Social & Groups Service | Exact tenant scope, tenant-and-realm scope, or tenant/realm/channel scope. It blocks sending within that scope while ordinary receipt remains available. |
| `chat_ban` | Restriction on ordinary communication participation | Social & Groups Service | Exact tenant scope, tenant-and-realm scope, or tenant/realm/channel scope. It blocks ordinary participation, sending, and history access while preserving essential moderation and system notices. |

Scope is explicit data. An omitted realm or channel means the declared broader tenant scope, not an inferred current realm or wildcard assembled by the enforcement owner. Cross-tenant or platform scope is valid only for `account_security_lock` and `platform_access_ban`; tenant restrictions cannot silently widen to other tenants.

Logging & Admin owns policy intent, punitive case decisions, operator ingress, and audit for `platform_access_ban`, `gameplay_ban`, `chat_mute`, and `chat_ban`. Account-owned security policy and recovery own protective `account_security_lock` decisions and may consume operator security evidence without turning the lock into punitive moderation. Account remains the enforcement owner for both account-wide categories.

### Independent Stacking and Clearance

Restrictions stack independently. Every boundary evaluates all categories applicable to that subject and exact scope. An applicable broader restriction can make a narrower denial temporarily irrelevant to the current request, but it does not delete, suspend, merge, or rewrite the narrower record.

Expiry, removal, recovery, or correction of one category has no effect on any other category. In particular:

- clearing `account_security_lock` after successful security recovery does not clear `platform_access_ban`;
- removing `platform_access_ban` does not clear a security lock that still requires recovery;
- removing a `gameplay_ban` does not remove chat restrictions;
- and removing one chat restriction or scope does not alter another category or scope.

When multiple categories owned by the same service deny one boundary, the deterministic player-facing precedence is the category with the broader effect: Account returns `platform_access_ban` ahead of `account_security_lock`, and Social & Groups returns `chat_ban` ahead of `chat_mute`; Game Session has only `gameplay_ban` in this category set. The response names only that winning category and supplies its safe notice or next-step information. It does not expose lower-priority active categories, internal evidence, moderator notes, hidden policy, or restrictions unrelated to the attempted action. This precedence selects the safe response only; it never deletes, suspends, merges, or rewrites the independently active records.

### Restriction Revision Contract

Every creation, extension, expiry, removal, recovery clearance, or correction is a new immutable revision under the owning service's monotonic subject/category/scope ordering. Each revision records at least:

- exact category, subject, and normalized scope;
- effective time and optional expiry time;
- authenticated human or workload actor;
- case identity or protective security-event identity;
- source action and source request identity;
- a player-safe notice and safe reason code distinct from protected evidence;
- monotonic owner revision or enforcement epoch;
- one idempotency identity;
- and an immutable payload digest.

The owner atomically commits the revision, current effective state, and idempotent result. Same-identity/same-digest replay returns the prior result; reuse with a different digest is rejected. Duplicate, delayed, or reordered revisions cannot erase or resurrect newer state.

Logging & Admin rejects unknown or legacy generic category strings at operator ingress before recording a successful intent or forwarding an owner command. `ban`, `account_ban`, `account_security_ban`, or another unqualified alias is not heuristically translated into one of the five categories. Protective Account security ingress applies the same closed vocabulary to its own security transitions.

### Owner-Local Enforcement

The owner-local enforcement contract remains normative:

- Account enforces `account_security_lock` and `platform_access_ban` from its own durable state and advances the required account authorization generations.
- Game Session enforces `gameplay_ban` from owner-local indexed state at `PLAY` and command admission. A newly effective ban stops new admission and closes an active covered binding.
- Social & Groups enforces `chat_mute` and `chat_ban` from owner-local indexed state at sending, participation, and history boundaries.
- Essential moderation and system notices remain deliverable through a chat ban so the affected account can receive the restriction notice and appeal or support instructions.
- Work already durably admitted before a gameplay restriction became effective may finish idempotently. Enforcement does not partially unwind it or admit new work.
- Routine auth, `PLAY`, command, communication, and history decisions do not synchronously query Logging & Admin.

Owner-local read failure at a protected boundary fails closed with category-appropriate safe diagnostics. Observability, analytics, or Logging & Admin unavailability does not disable an already committed owner restriction while the owner can read its state.

### Player Safety Features Are Separate

Player-controlled block, ignore, or personal mute relationships are Social-owned safety or relationship features, not operator restriction categories. They do not create `chat_mute`, `chat_ban`, or gameplay authority and do not appear as moderator actions.

A player report is evidence and case ingress only. Submitting or persisting a report does not automatically enforce a restriction. Any resulting punitive action follows the fixed category, revision, acknowledgement, and owner-local enforcement contract above.

## Consequences

- Credential recovery can safely clear protective compromise state without accidentally lifting punitive platform enforcement.
- Operators must choose an exact category and scope rather than relying on a convenient generic ban string.
- Independent stacking makes effective-state reads and player-response selection more explicit but avoids destructive cross-category side effects.
- Account needs separate durable protective-lock and punitive-ban lifecycles even though it enforces both at account access boundaries.
- Game Session and Social require scoped indexed records and category-specific behavior rather than one remote allow/deny query.
- Safe notices require a deliberately minimized field separate from protected case evidence.
- Player reports and personal block/ignore behavior remain useful without being confused with staff enforcement.

## Alternatives Considered

### One Generic Account Ban

Use one account-wide state for compromise, severe platform abuse, and punitive suspension. This simplifies auth checks but gives credential recovery ambiguous authority over punishment and gives moderation workflows ambiguous authority over security recovery. It is rejected.

### One Generic Moderation Restriction

Store an arbitrary action and let each consuming service interpret it. This matches the current implementation shape but permits vocabulary drift, inconsistent scope, accidental over-enforcement, and unsafe aliases. Fixed categories are required.

### Hierarchical Restrictions That Delete Narrower State

Applying a platform ban could replace gameplay and chat restrictions, or lifting a broad ban could clear all narrower restrictions. This reduces active rows but loses independent reasons, expiry, appeals, and ownership. Broader restrictions may dominate one request without mutating narrower state.

### Clear Every Account-Wide Denial Through Recovery

Treat successful identity proof as sufficient to restore platform access. This is correct for a protective lock but not for punitive enforcement unrelated to credential control. `platform_access_ban` requires its own moderation revision.

### Treat Player Reports or Blocks as Staff Enforcement

Automatically mute, ban, or hide a subject after a report or personal block. This enables abuse of the reporting system and grants one player authority over another's platform access. Reports remain evidence; personal blocks remain viewer-specific social policy.

## Implementation and Proof Obligations

The current implementation is not aligned. Logging & Admin stores `moderation_actions` with generic action text and accepts `ban`, `account_ban`, `gameplay_ban`, `chat_mute`, and `chat_ban` aliases. Its row and public command lack the complete fixed category, exact scope, actor/case/source action, safe notice, monotonic revision, idempotency identity, payload digest, and owner acknowledgement contract.

Game Session synchronously calls Logging & Admin during `PLAY`, and Social & Groups synchronously calls it during chat send. There are no complete owner-local gameplay/chat enforcement records, command-admission or active-binding containment path, differentiated mute receipt versus chat-ban participation/history behavior, essential-notice proof, expiry/correction ordering, or cross-category stacking proof. Account has a documented `security_locked` lifecycle state but does not prove the complete `account_security_lock` revision/recovery contract or a separate `platform_access_ban` lifecycle. Current code and tests also do not prove player-facing safe notices, in-game reporting, or player block/ignore safety behavior.

Implementation must replace generic action strings with one closed category enum and typed scope union at every ingress and owner contract. Development and test rows using legacy aliases must be reset or rewritten before the new path is claimed. If objectively retained operational rows exist, each must be one-time exactly classified and migrated with audit linkage, or quarantined/read-only when it is not mappable; runtime code must not retain a legacy alias fallback. Each owner must persist its revision and current-state projection transactionally, implement owner-local indexed reads, and expose bounded operator reconciliation without giving Logging & Admin direct owner-table mutation.

Proof must cover every category and permitted scope, unknown and legacy category rejection, cross-tenant isolation, scope normalization, same-request replay, conflicting digest, duplicate/delayed/reordered revisions, expiry, removal, correction, and concurrent revisions. Stacking proof must cover every pair of account-wide and tenant restrictions and demonstrate that recovery or removal affects only its exact category and scope.

Account proof must cover compromise lock, token/generation revocation, successful and failed security recovery, punitive platform denial surviving credential reset and lock recovery, and explicit later platform-ban removal. Game Session proof must cover `PLAY`, active binding closure, new command fencing, already-admitted durable work, and realm-versus-tenant scope. Social proof must distinguish mute sending/receipt, chat-ban participation/send/history, essential notices, realm/channel scopes, and owner-local read failure. Report and personal-block proof must show neither path creates staff enforcement automatically.

## Reversibility and Revisit Triggers

Storage schemas, safe reason vocabularies, indexes, cache implementation, and operator UI may evolve while preserving the five fixed categories, explicit scopes, independent lifecycles, monotonic revisions, and owner-local enforcement. Adding another category or scope class requires human review of its owner, enforcement boundary, stacking, notice, appeal, and failure semantics; implementations must not introduce one through a free-form action string.

## Required Documentation Alignment

- `design/architecture/system-architecture-overview.md`
- `design/architecture/microservices/logging-admin-service/moderation-policies.md`
- `design/product/user-journeys/players.md`
- `design/product/user-journeys/operators.md`
