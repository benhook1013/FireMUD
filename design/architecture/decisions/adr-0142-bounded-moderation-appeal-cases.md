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

Filing an appeal never stays enforcement. A separately authorized moderation action may change a restriction while review is pending, but appeal submission itself grants no gameplay, communication, or authentication authority.

### Minimal Case Lifecycle

The lifecycle is deliberately small:

```text
SUBMITTED -> UNDER_REVIEW -> DECIDED
```

The terminal decision is `UPHELD`, `MODIFIED`, or `OVERTURNED`. The case records at minimum:

- `appealCaseId`, appellant and submission/request identity;
- enforcement owner, restriction type, subject and exact tenant/platform scope;
- exact appealed enforcement revision and payload digest plus originating moderation action/case linkage;
- jurisdiction, eligibility policy version, safe player submission, and bounded evidence references;
- review actor, decision, safe player-facing rationale, and timestamps; and
- resulting owner command identity/digest/revision when modification or reversal applies.

An upheld decision creates no enforcement mutation. A modified or overturned decision sends a new command referencing the appeal case and exact appealed restriction. The owner applies it as a newer ordered transition and must not erase a later unrelated restriction. Original action, restriction, evidence, appeal transition, and review decision histories remain append-only.

### Jurisdiction and Access

Tenant-scoped `gameplay_ban`, `chat_mute`, and `chat_ban` appeals use the applicable tenant policy and authorized tenant moderation jurisdiction. Punitive `platform_access_ban` appeals use platform jurisdiction. Protective `account_security_lock` clearance remains Account-owned security recovery rather than a moderation appeal. Platform review of a tenant case exists only when an explicit platform policy grants escalation; global roles do not silently bypass tenant case isolation.

Case and evidence access is least-privilege and jurisdiction-scoped. Player status never exposes protected evidence, reporter identity, internal detection methods, unrelated subjects, or privileged operator notes. An essential restriction notice contains only safe category/scope, current effect and expiry where disclosable, appeal eligibility, and submission/status guidance.

### Gameplay-to-Web Bridge

Telnet and other gameplay clients do not collect appeal evidence or authentication secrets. An eligible appeal or status action yields an opaque, short-lived HTTPS URL whose server-side intent binds the authenticated account, gameplay session when applicable, exact case or restriction revision, action, expiry, and `requestId`. The browser independently authenticates through Account before reaching the caller-bound Logging & Admin submission or status surface.

The URL carries no account secret, case evidence, reporter identity, or completion authority. Gameplay may receive a safe status outcome but cannot approve, modify, or overturn a case.

### Rate Limits and Data Lifecycle

Submission is idempotent by stable request identity, and only one active appeal may exist for the exact account and restriction revision. Bounded account-, tenant-, and status-polling limits return retry guidance without revealing another subject's case. Policies may set stricter limits for abuse while preserving the required appeal path for eligible restrictions.

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

Proof must cover eligible and ineligible restrictions; duplicate, concurrent, and rate-limited submission; exact caller and restriction binding; stale or unknown revisions; tenant/platform jurisdiction and cross-tenant denial; safe Telnet/browser handoff; protected evidence and reporter redaction; upheld, modified, and overturned decisions; owner crash/lost acknowledgement/retry; a newer unrelated restriction; no automatic stay; Account security recovery separation; essential notice delivery during `chat_ban`; finite expiry/minimization, legal hold, export, erasure, and absence of a general evidence-disclosure path.

## Reversibility and Revisit Triggers

UI, case schema details, rate limits, retention periods, reviewer workflow, and notification transport may evolve while preserving ownership, exact revision linkage, no automatic stay, protected evidence, append-only history, and monotonic owner commands. Revisit multiple review levels or formal response-time commitments only when concrete scale, law, marketplace policy, or tenant governance requires them.

## Required Documentation Alignment

- `design/architecture/microservices/logging-admin-service/moderation-policies.md`
- `design/architecture/microservices/logging-admin-service/api-contracts.md`
- `design/architecture/microservices/logging-admin-service/runtime-and-data.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/decisions/adr-0048-durable-idempotent-operator-write-execution.md`
- `design/architecture/microservices/logging-admin-service/moderation-policies.md`
