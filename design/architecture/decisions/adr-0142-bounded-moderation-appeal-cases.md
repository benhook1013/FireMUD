# ADR 0142: Bounded Moderation Appeal Cases

## Status

Accepted

## Implementation Status

This decision is not implemented. Bounded appeal cases, Account-authenticated handoff, evidence-reference protection, jurisdiction-aware review, owner outcome commands, and lifecycle proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-PO-MODERATION-APPEALS`
- Decision date: 2026-07-20
- Decision key: `MS-PO-MODERATION-APPEALS`
- Primary capability: `PO-1.2`
- Affected capabilities: `PO-1.1`, `PO-1.3`, `EA-2.4`, `AA-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of appeal ownership, eligibility, player ingress, jurisdiction, evidence protection, enforcement effects, and implementation scope

## Context

Moderation restrictions need a way for affected players to challenge severe or long-lived outcomes. Account can reliably authenticate the player and reach them through browser and notification flows, but moderation appeals are policy, evidence, jurisdiction, and reviewer decisions rather than account identity records. Making Account the case owner would copy moderation evidence and tenant policy into the global identity service.

Logging & Admin already owns moderation cases, policy intent, operator ingress, and audit, while Account, Game Session, and Social & Groups own enforcement state under the owner-local enforcement contract. Appeal review should preserve those directions. It must not rewrite history, let filing silently suspend enforcement, or grow into a general legal tribunal before the product requires one.

## Decision

### Split Authentication, Case, and Enforcement Ownership

Account authenticates the affected player, provides browser handoff and notifications, and owns account-security recovery. Logging & Admin owns the bounded moderation appeal case, evidence references, jurisdiction and review decision, and append-only audit. Account does not decide tenant moderation appeals, and Logging & Admin does not become account-security or runtime enforcement authority.

Account, Game Session, and Social & Groups continue to own their applicable restriction records. A modified or overturned appeal reaches that owner as a new monotonic digest-bound command under [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md) and the owner-local enforcement contract. Neither the original moderation action nor the original enforcement revision is edited or deleted.

### Eligibility Is Declared by Policy

Every moderation restriction policy declares whether and when the outcome is appealable. Severe or long-lived punitive restrictions require an appeal path. A brief automatically expiring mute may be ineligible for a full case when the policy supplies clear notice and expiry. Ineligibility returns a stable caller-safe result rather than creating an empty case.

Filing an appeal never changes, suspends, or stays enforcement. A separately authorized moderation action may change a restriction while review is pending, but appeal submission itself grants no gameplay, communication, or authentication authority.

### Minimal Case Lifecycle

The lifecycle is deliberately small:

```text
SUBMITTED -> UNDER_REVIEW -> DECIDED
```

The terminal decision is `UPHELD`, `MODIFIED`, or `OVERTURNED`. The case records at minimum:

- `appealCaseId`, appellant, submission/request identity, and its persisted canonical normalized submission digest;
- enforcement owner, restriction type, subject and exact tenant/platform scope;
- exact appealed enforcement revision and payload digest plus originating moderation action/case linkage;
- jurisdiction, eligibility policy version, safe player submission, and bounded evidence references;
- review actor, decision, safe player-facing rationale, and timestamps; and
- resulting owner command identity/digest/revision when modification or reversal applies.

An upheld decision creates no enforcement mutation or owner command. A modified or overturned decision sends a new command referencing the appeal case and exact appealed restriction. That owner command carries the exact appealed owner revision and payload digest as an expected-state precondition; if the current owner state no longer matches, the owner persists a durable terminal `IDEMPOTENCY_CONFLICT` case/outcome bound to the applicable phase-qualified state and ADR 0048's complete canonical `postAuthorizationExecutionTuple`, including its exact `controlPlaneRequestId` correlation field, target, expected revision/mutation, authority evidence, reservation owner/fence, and canonical digest, releases the active-case key, and returns the conflict without mutating the newer restriction. Reconciliation and retry compare that same complete tuple and return the same stored terminal conflict. The owner applies a matching command as a newer ordered transition and must not erase a later unrelated restriction. An exact complete-tuple match for the same `ownerEnforcementRequestId` replays the stored result; its owner payload digest must also match, while `controlPlaneRequestId` remains correlation-only. Original action, restriction, evidence, appeal transition, and review decision histories remain append-only.

`DECIDED` is the terminal case/review fact; it is not proof that a `MODIFIED` or `OVERTURNED` owner command completed. That resulting command has its own distinct, appeal-linked `ownerEnforcementRequestId` and complete canonical `postAuthorizationExecutionTuple`; any `controlPlaneRequestId` is correlation-only, and its `mutationDigest` is an integrity field, not the idempotency identity. The command uses the phase-qualified states from [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md): `ACCOUNT_AUTHORIZATION/RESERVED`, `ACCOUNT_AUTHORIZATION/AUTHORIZATION_PENDING`, `ACCOUNT_AUTHORIZATION/AUTHORIZED`, or `ACCOUNT_AUTHORIZATION/NOT_EXECUTED_BEFORE_AUTHORIZATION`, followed by `OWNER_EXECUTION/OWNER_EXECUTION_PENDING`, `OWNER_EXECUTION/FENCE_REJECTED`, `OWNER_EXECUTION/COMMITTED`, `OWNER_EXECUTION/FAILED`, or `OWNER_EXECUTION/NOT_EXECUTED_AFTER_AUTHORIZATION`. A caller-facing accepted decision may report the case decision together with phase-qualified owner execution as pending, committed, failed, not executed, or explicitly `OWNER_EXECUTION/FENCE_REJECTED`, or provide poll/retry guidance. A revision conflict is the terminal `IDEMPOTENCY_CONFLICT` outcome described above, attached to the applicable owner-execution phase-qualified state rather than added as a new phase/state and distinct from `OWNER_EXECUTION/FENCE_REJECTED`; it releases the active-case key and exact complete-tuple reconciliation/retry returns that same outcome. Fence rejection is non-terminal: the active-case key remains held while that same command reconciles to `OWNER_EXECUTION/NOT_EXECUTED_AFTER_AUTHORIZATION` or another terminal owner outcome. `RETRYABLE` is guidance to poll or retry reconciliation of the same owner-command identity; it is never a persisted case or owner state, a rearm state, or permission to issue another command. A lost acknowledgement is reconciled read-only using that same complete tuple; a replacement command requires a new explicit action and fresh authorization.

### Jurisdiction and Access

Tenant-scoped `gameplay_ban`, `chat_mute`, and `chat_ban` appeals use the applicable tenant policy and authorized tenant moderation jurisdiction. Punitive `platform_access_ban` appeals use platform jurisdiction. Protective `account_security_lock` clearance remains Account-owned security recovery rather than a moderation appeal. Platform review of a tenant case exists only when an explicit platform policy grants escalation; global roles do not silently bypass tenant case isolation.

Case and evidence access is least-privilege and jurisdiction-scoped. Player status never exposes protected evidence, reporter identity, internal detection methods, unrelated subjects, or privileged operator notes. An essential restriction notice contains only safe category/scope, current effect and expiry where disclosable, appeal eligibility, and submission/status guidance.

### Gameplay-to-Web Bridge

Telnet and other gameplay clients do not collect appeal evidence or authentication secrets. An eligible appeal or status action yields an opaque, short-lived HTTPS URL whose server-side intent binds the authenticated account, gameplay session when applicable, exact case or restriction revision, action, expiry, and `requestId`. That handoff `requestId` is the stable appeal-submission identity persisted and correlated with `appealCaseId` for status; it is distinct from the later owner-command `ownerEnforcementRequestId`, while any `controlPlaneRequestId` on that command remains correlation-only. The browser independently authenticates through Account before reaching the caller-bound Logging & Admin submission or status surface.

The URL carries no account secret, case evidence, reporter identity, or completion authority. Gameplay may receive a safe status outcome but cannot approve, modify, or overturn a case.

### Rate Limits and Data Lifecycle

Submission is idempotent by that stable request identity, and only one active appeal may exist for the exact account and restriction revision. The appeal owner persists the canonical normalized submission digest with that request identity, using the existing digest-bound workflow/idempotency construction in [ADR 0078](./adr-0078-digest-bound-workflow-and-step-retry-identities.md) and [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md). An exact request identity with the same digest replays its stored semantic result; reuse with a different appellant, restriction revision, action, or normalized payload returns `IDEMPOTENCY_CONFLICT` before any case mutation. Bounded account-, tenant-, and status-polling limits return retry guidance without revealing another subject's case. Policies may set stricter limits for abuse while preserving the required appeal path for eligible restrictions.

Active-case admission uses a separate key over the caller-authorized appellant and exact appealed restriction identity/revision. Transactional uniqueness or an equivalent compare-and-set keeps that key active across `SUBMITTED` and `UNDER_REVIEW`; for a `DECIDED` `MODIFIED` or `OVERTURNED` outcome, it retains the key or an equivalent outcome-in-flight lock until the linked owner command reaches a terminal state, including the durable terminal `IDEMPOTENCY_CONFLICT` outcome, at which point the key is released. Distinct request identities racing on the same key have one winner; the loser or a repeat while the outcome command is unresolved receives a stable caller-safe existing-case result and creates no second case, evidence, audit, or owner-command side effects. This admission key is separate from the per-request idempotency identity.

Each appeal policy declares finite case and evidence-reference retention, authorized readers, redaction, legal-hold handling, export treatment, and terminal erasure or minimization. Legal hold is a separately authorized exception with recorded scope and review, not an indefinite default. A player's export may include their submission and safe decision summary; it excludes protected evidence and reporter identity unless a separate legal entitlement explicitly requires disclosure.

### Deliberately Limited Product

This decision does not build a general tribunal, hearings, evidence discovery, mandatory multi-reviewer panels, arbitrary escalation levels, or one universal response-time SLA. A deployment or policy may declare review targets, but FireMUD does not claim one global timing promise for every tenant and restriction.

## Consequences

- Players receive a coherent appeal path for consequential restrictions without moving moderation cases into Account.
- Tenant and platform jurisdiction remain explicit, and protected reporters/evidence do not leak through player notifications or status.
- Filing cannot be used as an automatic enforcement bypass.
- Modified or overturned outcomes preserve audit history and converge through the same owner-local monotonic enforcement model as the original restriction.
- Eligible restrictions add case retention, review authorization, notification, handoff, and outcome-command work.
- Brief auto-expiring mutes can remain lightweight when policy declares them ineligible.
- Atomic active-case admission makes the one-active-appeal invariant deterministic under concurrent distinct submissions without using the request identity as the admission key.

## Alternatives Considered

### Account Owns Every Appeal

Account could own submission, evidence, review, and decision because it authenticates the player and owns security recovery. This would place tenant moderation policy and evidence in the global identity service and create a second moderation-case system. Account remains the ingress and recovery owner only.

### Logging & Admin Directly Changes Runtime State

Logging & Admin could clear a restriction after a successful appeal. That would bypass the owner-local enforcement authority and monotonic command contract. It instead sends a new digest-bound command and waits for owner acknowledgement.

### Filing Automatically Stays Enforcement

Automatic suspension protects an appellant from an erroneous restriction during review, but also lets every submission temporarily bypass safety policy. A stay, if ever required, is a separate authorized moderation decision.

### Require a Full Tribunal for Every Restriction

Mandate multiple reviewers, hearings, universal deadlines, and full evidence disclosure for every mute or ban. This exceeds current product needs, exposes sensitive evidence, and makes brief automatic restrictions disproportionately expensive. The selected lifecycle is intentionally bounded.

### Offer No Appeals

This is operationally simplest but leaves severe or long-lived erroneous restrictions without a structured correction path and weakens accountability. Eligibility is policy-defined rather than absent.

## Implementation and Proof Obligations

No complete moderation appeal implementation or proof currently exists. There is no canonical appeal table, player submission/status contract, Account browser handoff, essential-notice redaction, evidence-reference lifecycle, jurisdiction-aware review, idempotent/rate-limited case creation, decision-to-owner command, or retention/export/erasure enforcement.

Implementation must add the bounded case schema and transitions, exact immutable restriction linkage, caller- and jurisdiction-bound APIs, opaque Account handoff, protected notification projection, evidence-reference authorization, append-only decisions, ADR-0048-compatible outcome commands, owner acknowledgement, rate limits, and declared data lifecycle.

Proof must cover eligible and ineligible restrictions; duplicate, concurrent, same-request-ID conflict/replay, and rate-limited submission, including changed appellant, restriction revision, action, and normalized payload; exact caller and restriction binding; stale or unknown revisions; tenant/platform jurisdiction and cross-tenant denial; safe Telnet/browser handoff; protected evidence and reporter redaction; upheld, modified, and overturned decisions; owner revision conflict with durable terminal `IDEMPOTENCY_CONFLICT` bound to phase-qualified state and the complete canonical `postAuthorizationExecutionTuple`, no mutation of newer state, active-case release, and stable exact-tuple reconciliation/retry; owner crash/lost acknowledgement/retry; a newer unrelated restriction; no automatic stay; Account security recovery separation; essential notice delivery during `chat_ban`; finite expiry/minimization, legal hold, export, erasure, and absence of a general evidence-disclosure path. Distinct-request concurrency proof must race two caller-authorized request identities for one active-case key and show one winner, one stable caller-safe existing-case result, one active case, and no duplicate case, evidence, or audit side effects. It must also submit or retry while a `DECIDED` `MODIFIED`/`OVERTURNED` owner command is unresolved, show the retained active-case key or equivalent outcome-in-flight lock, stable caller-safe duplicate result, no duplicate side effects, and release only after the linked owner command reaches a terminal state.

## Reversibility and Revisit Triggers

UI, case schema details, rate limits, retention periods, reviewer workflow, and notification transport may evolve while preserving ownership, exact revision linkage, no automatic stay, protected evidence, append-only history, and monotonic owner commands. Revisit multiple review levels or formal response-time commitments only when concrete scale, law, marketplace policy, or tenant governance requires them.

## Required Documentation Alignment

- [Moderation policies](../microservices/logging-admin-service/moderation-policies.md)
- [Logging & Admin API contracts](../microservices/logging-admin-service/api-contracts.md)
- [Logging & Admin runtime and data](../microservices/logging-admin-service/runtime-and-data.md)
- [Account API contracts](../microservices/account-service/api-contracts.md)
- [ADR 0048 durable idempotent operator-write execution](./adr-0048-durable-idempotent-operator-write-execution.md)
